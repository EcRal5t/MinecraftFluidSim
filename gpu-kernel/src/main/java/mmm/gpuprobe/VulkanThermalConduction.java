package mmm.gpuprobe;

import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalTransport;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.TreeSet;
import static mmm.gpuprobe.VulkanThermalConduction.Field.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Conservative conduction on unique inventory owners. Spatial contacts may share an owner. */
final class VulkanThermalConduction implements AutoCloseable {
    static final int IMPLICIT=9, COMPLETE=10;
    static final double TEMPERATURE_TOLERANCE=1e-4;
    enum Field {
        COUNTS(4),OWNER_ENERGY(8),OWNER_INITIAL_T(8),TERM_OFFSETS(4),TERM_MATERIAL(4),TERM_MASS(8),
        CONTACT_A(4),CONTACT_B(4),CONTACT_G(8),CONTACT_BATH_T(8),
        KNOTS(8),MATERIAL_CP(8),MATERIAL_MIN_CP(8),MIN_CP(8),ROOT_CP(8),BEFORE_ENERGY(8),TEMPERATURE(8),TRIAL_T(8),
        DEGREE_COUNTS(4),DEGREE_OFFSETS(4),DEGREE_CURSORS(4),INCIDENT(4),DEGREE(8),FLUX(8),
        ENERGY_CHANGE(8),RESERVOIR_CHANGE(8),MODE(4),ACCEPTED(4),SUMMARY(8),PARTIALS(8),BATH_PARTIALS(8),DISPATCH(4),SCAN(4),
        STARTS(4),COLUMNS(4),VALUES(8),ROWS(4),PRESSURE(8),WORK(8),META(4),PROBLEM(8),CONTROL(8),PCG_PARTIALS(8),
        TILE_COUNTS(4),TILE_OFFSETS(4),TILE_OWNERS(4),TILE_PARTIALS(8),BATH_INDEX(4),BATH_EDGES(4);
        final int width;Field(int width){this.width=width;}
    }
    /** Common knots retain every registered caloric break. Cp includes endpoint extension intervals. */
    record Tables(double[] knots,double[] heatCapacity,double[] minimumCapacity,int materials) {
        int segments(){return knots.length+1;}
        static Tables fromMaterials(List<ThermalMaterialDefinition> materials){
            var knots=new TreeSet<Double>();
            for(var material:materials){var segments=material.caloricSegments();
                for(int i=0;i+1<segments.size();i++)knots.add(segments.get(i).maximumTemperatureK());}
            double[] upper=knots.stream().mapToDouble(Double::doubleValue).toArray();
            int segments=upper.length+1;double[] cp=new double[Math.multiplyExact(materials.size(),segments)],minimum=new double[materials.size()];
            for(int m=0;m<materials.size();m++){
                var curve=materials.get(m).caloricSegments();int part=0;minimum[m]=Double.POSITIVE_INFINITY;
                for(int s=0;s<segments;s++){
                    while(part+1<curve.size()&&s>0&&curve.get(part).maximumTemperatureK()<=upper[s-1])part++;
                    cp[m*segments+s]=curve.get(part).specificHeatJPerKgK();
                    minimum[m]=Math.min(minimum[m],cp[m*segments+s]);
                }
            }
            return new Tables(upper,cp,minimum,materials.size());
        }
    }
    static final class Layout {
        final int owners,terms,contacts,tiles,baths;final Tables tables;
        final VulkanLiquidStateLayout.Column header;final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        final long endOffset;
        Layout(int owners,int terms,int contacts,Tables tables,long base,long alignment,int baths){
            if(owners<1||terms<1||contacts<0||baths<0||tables.materials()<1||base<0||alignment<8||(alignment&(alignment-1))!=0)
                throw new IllegalArgumentException("Invalid thermal conduction layout");
            this.owners=owners;this.terms=terms;this.contacts=contacts;this.tables=tables;this.baths=baths;
            // Each long row has d>128 incidents, so ceil(d/128)<=d/64; all rows have at most 2*contacts incidents.
            tiles=Math.toIntExact((contacts+31L)/32);
            long[] cursor={base};header=allocate(cursor,80,4,alignment);
            for(Field field:Field.values()){
                int n=switch(field){
                    case COUNTS->3;
                    case TERM_OFFSETS,DEGREE_OFFSETS,STARTS,TILE_OFFSETS->Math.addExact(owners,1);
                    case TERM_MATERIAL,TERM_MASS->terms;
                    case CONTACT_A,CONTACT_B,CONTACT_G,CONTACT_BATH_T,FLUX,BATH_INDEX->contacts;
                    case KNOTS->tables.knots().length;case MATERIAL_CP->tables.heatCapacity().length;
                    case MATERIAL_MIN_CP->tables.materials();
                    case INCIDENT->Math.multiplyExact(contacts,2);
                    case ENERGY_CHANGE->Math.multiplyExact(owners,2);case RESERVOIR_CHANGE->Math.multiplyExact(baths,ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
                    case MODE->2;case ACCEPTED->1;case SUMMARY->8;
                    case PARTIALS->Math.multiplyExact((owners+127)/128,4);case BATH_PARTIALS->Math.multiplyExact(Math.max(1,(contacts+127)/128),4);
                    case BATH_EDGES->baths;case DISPATCH->15;case TILE_OWNERS->tiles;case TILE_PARTIALS->2*tiles;case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(owners)/4);
                    case WORK->Math.multiplyExact(owners,5);case META->Math.multiplyExact(owners,2);
                    case PROBLEM->Math.addExact(Math.multiplyExact(owners,3),1);case CONTROL->16;
                    case PCG_PARTIALS->Math.multiplyExact((owners+255)/256,4);
                    default->owners;
                };columns.put(field,allocate(cursor,n,field.width,alignment));
            }
            endOffset=align(cursor[0],alignment);Math.toIntExact(endOffset/4);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        int word(Field field){return Math.toIntExact(column(field).offset()/4);}
        int headerWordOffset(){return Math.toIntExact(header.offset()/4);}
        long endOffset(){return endOffset;}
        ByteBuffer headerBytes(){
            var bytes=ByteBuffer.allocateDirect(320).order(ByteOrder.LITTLE_ENDIAN);
            bytes.putInt(0,owners).putInt(4,terms).putInt(8,contacts).putInt(12,tables.materials()).putInt(16,tables.segments());
            bytes.putInt(20,ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS).putInt(24,baths);
            for(Field f:Field.values())bytes.putInt(4*(8+f.ordinal()),word(f));
            bytes.putDouble(288,ThermalTransport.CONDUCTION_RESOLUTION_K).putDouble(296,TEMPERATURE_TOLERANCE);
            return bytes;
        }
        void initializeHeaderAndTables(ByteBuffer destination){
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
            for(var chunk:tableChunks())destination.duplicate().position(Math.toIntExact(chunk.offset())).put(chunk.bytes().duplicate());
        }
        List<VulkanGasThermodynamics.TableChunk> tableChunks(){
            var result=new ArrayList<VulkanGasThermodynamics.TableChunk>();
            append(result,column(Field.KNOTS),tables.knots());append(result,column(Field.MATERIAL_CP),tables.heatCapacity());
            append(result,column(Field.MATERIAL_MIN_CP),tables.minimumCapacity());return List.copyOf(result);
        }
        List<FlipKernelBindings.BufferRange> pressureRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
            return List.of(range(arena,Field.STARTS),range(arena,Field.COLUMNS),range(arena,Field.VALUES),range(arena,Field.ROWS),
                    range(arena,Field.PRESSURE),range(arena,Field.PRESSURE),range(arena,Field.WORK),range(arena,Field.META),
                    range(arena,Field.PROBLEM),range(arena,Field.CONTROL),range(arena,Field.PCG_PARTIALS),errors);
        }
        private FlipKernelBindings.BufferRange range(FlipKernelBindings.BufferRange arena,Field f){
            var c=column(f);return new FlipKernelBindings.BufferRange(arena.buffer(),arena.offset()+c.offset(),c.bindingBytes());
        }
    }
    static Layout layout(int ownerCapacity,int termCapacity,int contactCapacity,Tables tables,long scratchBase,long alignment,int bathCapacity){
        return new Layout(ownerCapacity,termCapacity,contactCapacity,tables,scratchBase,alignment,bathCapacity);
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;private FlipKernelBindings.BufferRange arena;
    VulkanThermalConduction(VulkanDeviceContext context){
        bindings=new FlipKernelBindings(context,2,shader(),32);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        this.arena=arena;bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);
    }
    /** Actual contacts and live counts are supplied by the spatial sampler; no state leaves the arena. */
    void recordPrepare(VkCommandBuffer command,Layout l,double dt){
        recordPrepare(command,l,dt,null);
    }
    void recordPrepare(VkCommandBuffer command,Layout l,double dt,VulkanStageTimings timings){
        if(!(Double.isFinite(dt)&&dt>0))throw new IllegalArgumentException("Invalid conduction duration");
        timed(timings,command,"thermal-clear",()->stage(command,l,dt,0,0,0,l.owners,false));
        timed(timings,command,"thermal-contact-count-bath-compact",()->{stage(command,l,dt,31,0,0,128,false);stage(command,l,dt,1,0,0,Math.max(1,l.contacts),true);});
        timed(timings,command,"thermal-bath-clear",()->stage(command,l,dt,30,0,0,l.baths,false));
        timed(timings,command,"thermal-adjacency",()->{
            scan.record(command,l.owners,l.word(Field.DEGREE_COUNTS),l.word(Field.DEGREE_OFFSETS),l.word(Field.SCAN));
            stage(command,l,dt,2,0,0,l.contacts,false);
        });
        timed(timings,command,"thermal-owner-tiles",()->{
            stage(command,l,dt,21,0,0,l.owners,false);
            scan.record(command,l.owners,l.word(Field.TILE_COUNTS),l.word(Field.TILE_OFFSETS),l.word(Field.SCAN));
            stage(command,l,dt,20,0,0,Math.max(1,l.tiles),false);
        });
        timed(timings,command,"thermal-owner-degrees",()->{
            stage(command,l,dt,22,0,0,l.tiles,true);
            stage(command,l,dt,3,0,0,l.owners,false);
        });
        timed(timings,command,"thermal-mode",()->{
            stage(command,l,dt,4,0,0,128,false);
        });
    }
    /** Up to the CPU model's stable explicit-substep budget, with zero-group dispatch after completion. */
    void recordExplicit(VkCommandBuffer command,Layout l,double dt){
        for(int step=0;step<ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS;step++){
            stage(command,l,dt,5,step,0,128,false);
            stage(command,l,dt,6,step,0,l.contacts,true);
            stage(command,l,dt,23,step,0,l.tiles,true);
            stage(command,l,dt,7,step,0,l.owners,true);
        }
    }
    /** Assemble the nonlinear residual and positive caloric Jacobian; read MODE before invoking PCG. */
    void recordLinearize(VkCommandBuffer command,Layout l,double dt){
        stage(command,l,dt,24,0,0,l.tiles,true);
        stage(command,l,dt,8,0,0,l.owners,false);stage(command,l,dt,9,0,0,128,false);
    }
    /** One damped Newton trial. ACCEPTED is one iff residual improves or reaches caloric resolution. */
    void recordTrial(VkCommandBuffer command,Layout l,double dt,double fraction){
        if(!(Double.isFinite(fraction)&&fraction>0&&fraction<=1))throw new IllegalArgumentException("Invalid thermal Newton fraction");
        stage(command,l,dt,10,0,fraction,l.owners,false);
        stage(command,l,dt,25,0,0,l.tiles,true);
        stage(command,l,dt,11,0,0,l.owners,false);stage(command,l,dt,12,0,0,128,false);
        stage(command,l,dt,13,0,0,l.owners,false);
    }
    /** Publishes converged implicit contact fluxes; explicit energy is already present. */
    void recordFinish(VkCommandBuffer command,Layout l,double dt){
        stage(command,l,dt,14,0,0,l.contacts,false);
        stage(command,l,dt,26,0,0,l.tiles,true);stage(command,l,dt,15,0,0,l.owners,false);
    }
    VulkanPressureKernel.PressureOperator pressureOperator(Layout l,double dt){
        return new VulkanPressureKernel.PressureOperator(){
            public int liquidCount(){return l.owners;}public int matrixFreeFromRow(){return 0;}
            public void recordProduct(VkCommandBuffer command,boolean direction){
                stage(command,l,dt,direction?17:16,0,0,l.owners,false);
                stage(command,l,dt,direction?19:18,0,0,l.tiles,true);
                stage(command,l,dt,direction?28:27,0,0,l.owners,false);
            }
        };
    }
    /** Completes a stiff conduction solve inside the caller's private candidate. Failure leaves publication to the caller. */
    int solveImplicit(VulkanRegionStorage.Candidate candidate,Layout l,VulkanPressureKernel pressure,double dt){
        return solveImplicit(candidate,l,pressure,dt,null);
    }
    int solveImplicit(VulkanRegionStorage.Candidate candidate,Layout l,VulkanPressureKernel pressure,double dt,VulkanStageTimings timings){
        var operator=this.pressureOperator(l,dt);
        for(int iteration=0;iteration<8;iteration++){
            var status=candidate.continueBatch((command,input,output,errors,staging,stack)->timed(timings,command,"thermal-linearize",()->this.recordLinearize(command,l,dt)),l.column(MODE).offset(),4);
            require(status,"linearize");if(status.feedback().getInt(0)==VulkanThermalConduction.COMPLETE)return iteration;
            status=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                timed(timings,command,"thermal-pcg-initialize",()->pressure.recordInitialize(command,l.owners,l.owners,200,null,null,operator));
                timed(timings,command,"thermal-pcg-iterations",()->pressure.recordIterations(command,l.owners,l.owners,200,null,null,8,operator));
            },l.column(CONTROL).offset()+24,8);
            int recorded=8;require(status,"PCG");
            while(status.feedback().getDouble(0)!=0){
                if(recorded>=200)throw new IllegalStateException("Conduction PCG exhausted its work budget");
                status=candidate.continueBatch((command,input,output,errors,staging,stack)->timed(timings,command,"thermal-pcg-iterations",()->pressure.recordIterations(command,l.owners,l.owners,200,null,null,8,operator)),l.column(CONTROL).offset()+24,8);
                recorded+=8;require(status,"PCG");
            }
            status=candidate.continueBatch((command,input,output,errors,staging,stack)->timed(timings,command,"thermal-pcg-finish",()->pressure.recordFinish(command,l.owners,l.owners,200,null,null,operator)),l.column(CONTROL).offset()+16,8);
            require(status,"PCG finish");
            boolean accepted=false;
            for(int backtrack=0;backtrack<8;backtrack++){
                double fraction=Math.scalb(1.0,-backtrack);
                status=candidate.continueBatch((command,input,output,errors,staging,stack)->timed(timings,command,"thermal-caloric-trial",()->this.recordTrial(command,l,dt,fraction)),l.column(ACCEPTED).offset(),4);
                require(status,"caloric trial");if(status.feedback().getInt(0)!=0){accepted=true;break;}
            }
            if(!accepted)throw new IllegalStateException("Conduction caloric Newton trial did not reduce the residual");
        }
        var status=candidate.continueBatch((command,input,output,errors,staging,stack)->timed(timings,command,"thermal-linearize",()->this.recordLinearize(command,l,dt)),l.column(MODE).offset(),4);
        require(status,"final residual");if(status.feedback().getInt(0)!=VulkanThermalConduction.COMPLETE)throw new IllegalStateException("Conduction caloric work budget exhausted");
        return 8;
    }
    private static void timed(VulkanStageTimings timings,VkCommandBuffer command,String label,Runnable commands){
        if(timings==null)commands.run();else timings.record(command,label,commands);
    }
    private static void require(VulkanRegionStorage.Candidate.BatchResult result,String stage){
        if(result.error()!=0)throw new IllegalStateException("Conduction "+stage+" error="+result.error());
    }
    private void stage(VkCommandBuffer command,Layout l,double dt,int stage,int step,double fraction,int count,boolean indirect){
        if(count==0)return;int groups=(count+127)/128;
        try(var stack=stackPush()){
            bindings.bind(command,stack);
            var push=stack.malloc(32).putInt(l.headerWordOffset()).putInt(stage).putInt(count).putInt(step).putDouble(dt).putDouble(fraction).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            if(indirect){
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+l.column(Field.DISPATCH).offset()+(stage==1?48:stage==6?0:stage==7?12:stage==23?24:36));
            }else vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static void append(List<VulkanGasThermodynamics.TableChunk> result,VulkanLiquidStateLayout.Column column,double[] values){
        for(int first=0;first<values.length;first+=8192){int count=Math.min(8192,values.length-first);
            var out=ByteBuffer.allocateDirect(8*count).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<count;i++)out.putDouble(8*i,values[first+i]);
            result.add(new VulkanGasThermodynamics.TableChunk(column.offset()+8L*first,out));}
    }
    private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static VulkanLiquidStateLayout.Column allocate(long[] cursor,int count,int width,long alignment){
        var c=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);cursor[0]=Math.addExact(c.offset(),c.bindingBytes());return c;
    }
    private static String shader(){
        try(var input=VulkanThermalConduction.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/thermal-conduction.comp")){
            if(input==null)throw new IllegalStateException("Thermal conduction shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("Thermal conduction shader unavailable",failure);}
    }
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}
