package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static mmm.gpuprobe.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Actual inventory owners, spatial thermal samples, and finite-volume conduction contacts. */
final class VulkanThermalContacts implements AutoCloseable {
    record Inputs(VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout liquidThermal,
                  VulkanGasStateLayout gas,VulkanGasThermodynamics.Layout gasThermal,VulkanGasGeometry.Layout gasGeometry,
                  VulkanGranularStateLayout granular,VulkanLiquidThermodynamics.Layout granularThermal,
                  VulkanCoherentStateLayout coherent,VulkanLiquidThermodynamics.Layout coherentThermal,
                  VulkanThermalDeviceLayout device,VulkanLiquidThermodynamics.Layout deviceThermal,
                  int fixedMasksWord,int bathTemperaturesWord,int openFaceFractionsWord,double cellSize){
        Inputs(VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout liquidThermal,VulkanGasStateLayout gas,
               VulkanGasThermodynamics.Layout gasThermal,VulkanGasGeometry.Layout gasGeometry,VulkanGranularStateLayout granular,
               VulkanLiquidThermodynamics.Layout granularThermal,VulkanCoherentStateLayout coherent,VulkanLiquidThermodynamics.Layout coherentThermal,
               int fixedMasksWord,int bathTemperaturesWord,int openFaceFractionsWord,double cellSize){
            this(liquid,liquidThermal,gas,gasThermal,gasGeometry,granular,granularThermal,coherent,coherentThermal,null,null,fixedMasksWord,bathTemperaturesWord,openFaceFractionsWord,cellSize);
        }
        Inputs(VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout liquidThermal,VulkanGasStateLayout gas,
               VulkanGasThermodynamics.Layout gasThermal,VulkanGasGeometry.Layout gasGeometry,VulkanGranularStateLayout granular,
               VulkanLiquidThermodynamics.Layout granularThermal,int fixedMasksWord,int bathTemperaturesWord,int openFaceFractionsWord,double cellSize){
            this(liquid,liquidThermal,gas,gasThermal,gasGeometry,granular,granularThermal,null,null,null,null,fixedMasksWord,bathTemperaturesWord,openFaceFractionsWord,cellSize);
        }
        Inputs{if(liquid==null||liquidThermal==null||fixedMasksWord<0||!(Double.isFinite(cellSize)&&cellSize>0)
                ||(gas==null)!=(gasThermal==null)||(gas==null)!=(gasGeometry==null)||(granular==null)!=(granularThermal==null)||(coherent==null)!=(coherentThermal==null)||(device==null)!=(deviceThermal==null))
            throw new IllegalArgumentException("Invalid thermal inventory inputs");}
        int owners(){return Math.addExact((coherent==null?0:coherent.groupCapacity())+(device==null?0:device.deviceCapacity()),Math.addExact(liquid.stockCapacity(),Math.addExact(gas==null?0:gas.nodeCapacity(),granular==null?0:Math.addExact(granular.particleCapacity(),granular.depositCapacity()))));}
        int samples(){return Math.addExact(coherent==null?0:coherent.groupCapacity(),Math.addExact(liquid.stockCapacity(),Math.addExact(gas==null?0:liquid.cellCount(),granular==null?0:Math.addExact(8*granular.particleCapacity(),granular.depositCapacity()))));}
        int terms(){return Math.addExact((coherent==null?0:coherent.groupCapacity())+(device==null?0:device.deviceCapacity()),Math.addExact(liquid.stockCapacity(),Math.addExact(gas==null?0:gas.componentCapacity(),granular==null?0:Math.addExact(granular.particleCapacity(),granular.depositCapacity()))));}
        long endOffset(){return Math.max(device==null?0:Math.max(device.endOffset(),deviceThermal.endOffset()),Math.max(coherent==null?0:Math.max(coherent.endOffset(),coherentThermal.endOffset()),Math.max(liquidThermal.endOffset(),Math.max(gasThermal==null?0:Math.max(gasThermal.endOffset(),gasGeometry.endOffset()),granularThermal==null?0:Math.max(granular.endOffset(),granularThermal.endOffset())))));}
    }
    /** Indices refer to the conduction solver's common material table; conductivity remains SI W/(m K). */
    record Materials(int[] liquid,int[] gas,int[] granular,int[] coherent,int[] device,double[] conductivity){
        Materials(int[] liquid,int[] gas,int[] granular,double[] conductivity){this(liquid,gas,granular,new int[0],new int[0],conductivity);}
        Materials(int[] liquid,int[] gas,int[] granular,int[] coherent,double[] conductivity){this(liquid,gas,granular,coherent,new int[0],conductivity);}
        Materials{liquid=liquid.clone();gas=gas.clone();granular=granular.clone();coherent=coherent.clone();device=device.clone();conductivity=conductivity.clone();
            for(double k:conductivity)if(!(Double.isFinite(k)&&k>=0))throw new IllegalArgumentException("Invalid thermal conductivity");
            for(int[] map:new int[][]{liquid,gas,granular,coherent,device})for(int i:map)if(i<0||i>=conductivity.length)throw new IllegalArgumentException("Thermal material mapping out of range");}
    }
    enum Field{OWNER_FLAGS(4),OWNER_PREFIX(4),OWNER_KIND(4),OWNER_INDEX(4),OWNER_K(8),TERM_COUNTS(4),
        SAMPLE_FLAGS(4),SAMPLE_PREFIX(4),SAMPLE_OWNER(4),SAMPLE_CELL(4),SAMPLE_MASK(4),SAMPLE_FRACTION(8),
        CELL_COUNTS(4),CELL_OFFSETS(4),CELL_CURSORS(4),CELL_SAMPLES(4),COVERAGE(8),CONTACT_COUNTS(4),CONTACT_OFFSETS(4),
        LIQUID_MATERIAL(4),GAS_MATERIAL(4),GRANULAR_MATERIAL(4),CONDUCTIVITY(8),REQUIREMENTS(4),SCAN(4),COHERENT_MATERIAL(4),DEVICE_MATERIAL(4),PORT_COUNTS(4),PORT_OFFSETS(4),PORT_CURSORS(4),PORT_ROWS(4),MF_DATA(8),MF_POW(4),BATH_COUNTS(4),BATH_OFFSETS(4),BATH_G(8),BATH_T(8),MF_DISPATCH(4);
        final int width;Field(int width){this.width=width;}}
    static final class Layout{
        final Inputs input;final Materials materials;final VulkanThermalConduction.Layout conduction;
        final int owners,samples,cells;final VulkanLiquidStateLayout.Column header;final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);final long endOffset;
        Layout(Inputs input,Materials materials,VulkanThermalConduction.Layout conduction,long base,long a){
            this.input=input;this.materials=materials;this.conduction=conduction;owners=input.owners();samples=input.samples();cells=input.liquid().cellCount();
            if(base<Math.max(input.endOffset(),conduction.endOffset())||a<8||(a&(a-1))!=0||materials.conductivity().length!=conduction.tables.materials())throw new IllegalArgumentException("Invalid thermal contacts layout");
            long[] cursor={base};header=allocate(cursor,160,4,a);
            for(Field f:Field.values()){
                int n=switch(f){case MF_DISPATCH->3;case MF_DATA->Math.multiplyExact(4,samples+(input.device()==null?0:input.device().portCapacity()));
                    case MF_POW,BATH_COUNTS->samples;case BATH_OFFSETS->samples+1;case BATH_G,BATH_T->conduction.baths;
                    case OWNER_FLAGS->owners;case OWNER_PREFIX->owners+1;
                    case OWNER_KIND,OWNER_INDEX,OWNER_K,TERM_COUNTS->conduction.owners;
                    case SAMPLE_FLAGS,SAMPLE_OWNER,SAMPLE_CELL,SAMPLE_MASK,SAMPLE_FRACTION,CELL_SAMPLES,CONTACT_COUNTS->samples;
                    case SAMPLE_PREFIX,CONTACT_OFFSETS->samples+1;case PORT_COUNTS,PORT_CURSORS,CELL_COUNTS,CELL_CURSORS->cells;case PORT_OFFSETS->cells+1;case PORT_ROWS->input.device()==null?0:input.device().portCapacity();case CELL_OFFSETS->cells+1;case COVERAGE->Math.addExact(8*cells,input.device()==null?0:24*input.device().portCapacity());
                    case LIQUID_MATERIAL->materials.liquid().length;case GAS_MATERIAL->materials.gas().length;case GRANULAR_MATERIAL->materials.granular().length;case COHERENT_MATERIAL->materials.coherent().length;case DEVICE_MATERIAL->materials.device().length;case CONDUCTIVITY->materials.conductivity().length;
                    case REQUIREMENTS->7;case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(samples,Math.max(owners,Math.max(cells,conduction.owners))))/4);};
                columns.put(f,allocate(cursor,n,f.width,a));
            }endOffset=align(cursor[0],a);Math.toIntExact(endOffset/4);
        }
        VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}int word(Field f){return Math.toIntExact(column(f).offset()/4);}
        int headerWord(){return Math.toIntExact(header.offset()/4);}long endOffset(){return endOffset;}
        ByteBuffer headerBytes(){
            int[] d=new int[160];var l=input.liquid();var g=input.gas();var s=input.granular();
            d[0]=l.stockCapacity();d[1]=g==null?0:g.nodeCapacity();d[2]=s==null?0:s.particleCapacity();d[3]=s==null?0:s.depositCapacity();
            d[4]=cells;d[5]=l.sizeX();d[6]=l.sizeY();d[7]=l.sizeZ();d[8]=samples;d[9]=owners;d[11]=input.fixedMasksWord();d[12]=input.bathTemperaturesWord();d[13]=input.openFaceFractionsWord();
            d[14]=lw(l,VulkanLiquidStateLayout.Field.COUNTS);d[15]=g==null?-1:gw(g,VulkanGasStateLayout.Field.COUNTS);d[16]=s==null?-1:s.word(VulkanGranularStateLayout.Field.COUNTS);
            d[17]=lw(l,VulkanLiquidStateLayout.Field.CELL_OFFSETS);d[18]=lw(l,VulkanLiquidStateLayout.Field.MATERIALS);d[19]=lw(l,VulkanLiquidStateLayout.Field.SUPPORT_MASKS);
            d[20]=lw(l,VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES);d[21]=lw(l,VulkanLiquidStateLayout.Field.MASS_UNITS);d[22]=lw(l,VulkanLiquidStateLayout.Field.ENERGY_BITS);d[23]=VulkanThermalContacts.columnWord(input.liquidThermal().temperatures());
            if(g!=null){d[24]=gw(g,VulkanGasStateLayout.Field.ROW_COMPONENT_OFFSETS);d[25]=gw(g,VulkanGasStateLayout.Field.COMPONENT_HANDLES);d[26]=gw(g,VulkanGasStateLayout.Field.STANDARD_VOLUME_NANOS);d[27]=gw(g,VulkanGasStateLayout.Field.MASS_UNITS);d[28]=gw(g,VulkanGasStateLayout.Field.INTERNAL_ENERGY);d[29]=VulkanThermalContacts.columnWord(input.gasThermal().states());d[30]=VulkanThermalContacts.columnWord(input.gasGeometry().cellNodes());d[31]=VulkanThermalContacts.columnWord(input.gasGeometry().dryVolumes());d[32]=VulkanThermalContacts.columnWord(input.gasGeometry().dryMasks());}
            if(s!=null){d[33]=s.word(VulkanGranularStateLayout.Field.MATERIAL);d[34]=s.word(VulkanGranularStateLayout.Field.SOLID_VOLUME_SVQ);d[35]=s.word(VulkanGranularStateLayout.Field.MASS_UNITS);d[36]=s.word(VulkanGranularStateLayout.Field.INTERNAL_ENERGY);d[37]=VulkanThermalContacts.columnWord(input.granularThermal().temperatures());d[38]=s.word(VulkanGranularStateLayout.Field.SUPPORT_CELLS);d[39]=s.word(VulkanGranularStateLayout.Field.SUPPORT_QUOTAS);d[40]=s.word(VulkanGranularStateLayout.Field.DEPOSIT_CELL);}
            d[41]=materials.liquid().length;d[42]=materials.gas().length;d[43]=materials.granular().length;
            d[44]=materials.coherent().length;var c=input.coherent();d[45]=c==null?0:c.groupCapacity();d[46]=c==null?-1:c.word(VulkanCoherentStateLayout.Field.COUNTS);
            if(c!=null){d[109]=c.word(VulkanCoherentStateLayout.Field.CELL);d[110]=c.word(VulkanCoherentStateLayout.Field.MATERIAL);d[111]=c.word(VulkanCoherentStateLayout.Field.OCCUPIED_VOLUME_SVQ);d[112]=c.word(VulkanCoherentStateLayout.Field.MASS_UNITS);d[113]=c.word(VulkanCoherentStateLayout.Field.INTERNAL_ENERGY);d[114]=VulkanThermalContacts.columnWord(input.coherentThermal().temperatures());}
            var v=input.device();d[47]=materials.device().length;d[132]=v==null?0:v.deviceCapacity();d[133]=v==null?-1:v.word(VulkanThermalDeviceLayout.Field.COUNTS);
            if(v!=null){var fields=new VulkanThermalDeviceLayout.Field[]{VulkanThermalDeviceLayout.Field.INTERNAL_ENERGY,VulkanThermalDeviceLayout.Field.MASS_UNITS,VulkanThermalDeviceLayout.Field.MATERIAL};
                for(int i=0;i<fields.length;i++)d[134+i]=v.word(fields[i]);d[137]=columnWord(input.deviceThermal().temperatures());
                var more=new VulkanThermalDeviceLayout.Field[]{VulkanThermalDeviceLayout.Field.GENERATION,VulkanThermalDeviceLayout.Field.REQUEST_GENERATION,VulkanThermalDeviceLayout.Field.DEVICE_ENERGY_CHANGE,VulkanThermalDeviceLayout.Field.PORT_DEVICE,VulkanThermalDeviceLayout.Field.PORT_CELL,VulkanThermalDeviceLayout.Field.PORT_DIRECTION,VulkanThermalDeviceLayout.Field.PORT_WALL_THICKNESS,VulkanThermalDeviceLayout.Field.PORT_WALL_K,VulkanThermalDeviceLayout.Field.PORT_FILM_THICKNESS};
                for(int i=0;i<more.length;i++)d[138+i]=v.word(more[i]);d[147]=v.portCapacity();}
            if(g!=null){d[148]=input.gasGeometry().planesWordOffset();d[149]=input.gasGeometry().cellsWordOffset();d[150]=input.gasGeometry().microWordOffset();}else{d[148]=-1;d[149]=-1;d[150]=-1;}
            d[151]=conduction.headerWordOffset();
            for(Field f:Field.values())d[48+f.ordinal()]=word(f);
            var fields=new VulkanThermalConduction.Field[]{VulkanThermalConduction.Field.COUNTS,VulkanThermalConduction.Field.OWNER_ENERGY,VulkanThermalConduction.Field.OWNER_INITIAL_T,VulkanThermalConduction.Field.TERM_OFFSETS,VulkanThermalConduction.Field.TERM_MATERIAL,VulkanThermalConduction.Field.TERM_MASS,VulkanThermalConduction.Field.CONTACT_A,VulkanThermalConduction.Field.CONTACT_B,VulkanThermalConduction.Field.CONTACT_G,VulkanThermalConduction.Field.CONTACT_BATH_T};
            for(int i=0;i<fields.length;i++)d[96+i]=conduction.word(fields[i]);d[106]=conduction.owners;d[107]=conduction.terms;d[108]=conduction.contacts;
            var out=ByteBuffer.allocateDirect(640).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<d.length;i++)out.putInt(4*i,d[i]);out.putDouble(512,input.cellSize());return out;
        }
        void initializeHeaderAndTables(ByteBuffer out){out.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());for(var chunk:tableChunks())out.duplicate().position(Math.toIntExact(chunk.offset())).put(chunk.bytes().duplicate());}
        List<VulkanGasThermodynamics.TableChunk> tableChunks(){
            var out=new ArrayList<VulkanGasThermodynamics.TableChunk>();ints(out,Field.LIQUID_MATERIAL,materials.liquid());ints(out,Field.GAS_MATERIAL,materials.gas());ints(out,Field.GRANULAR_MATERIAL,materials.granular());ints(out,Field.COHERENT_MATERIAL,materials.coherent());ints(out,Field.DEVICE_MATERIAL,materials.device());
            var b=ByteBuffer.allocateDirect(8*materials.conductivity().length).order(ByteOrder.LITTLE_ENDIAN);for(double x:materials.conductivity())b.putDouble(x);b.flip();if(b.hasRemaining())out.add(new VulkanGasThermodynamics.TableChunk(column(Field.CONDUCTIVITY).offset(),b));return List.copyOf(out);
        }
        private void ints(List<VulkanGasThermodynamics.TableChunk> out,Field f,int[] values){if(values.length==0)return;var b=ByteBuffer.allocateDirect(4*values.length).order(ByteOrder.LITTLE_ENDIAN);for(int x:values)b.putInt(x);b.flip();out.add(new VulkanGasThermodynamics.TableChunk(column(f).offset(),b));}
    }
    static Layout layout(Inputs input,Materials materials,VulkanThermalConduction.Layout conduction,long base,long alignment){return new Layout(input,materials,conduction,base,alignment);}
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;private FlipKernelBindings.BufferRange arena;
    VulkanThermalContacts(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),32);try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){this.arena=arena;bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);}
    void recordPrepare(VkCommandBuffer command,Layout l){recordSource(command,l,false);recordGraphEdges(command,l);stage(command,l,13,1);}
    private void recordSource(VkCommandBuffer command,Layout l,boolean matrixFree){
        stage(command,l,0,Math.max(l.owners,Math.max(l.samples,Math.max(l.cells,l.conduction.owners))),matrixFree?2:1,0);
        stage(command,l,1,l.owners);prefix(command,l,l.owners,Field.OWNER_FLAGS,Field.OWNER_PREFIX);
        stage(command,l,2,l.owners);scan.record(command,l.conduction.owners,l.word(Field.TERM_COUNTS),l.conduction.word(VulkanThermalConduction.Field.TERM_OFFSETS),l.word(Field.SCAN));
        stage(command,l,3,l.owners);stage(command,l,4,l.samples);prefix(command,l,l.samples,Field.SAMPLE_FLAGS,Field.SAMPLE_PREFIX);
        stage(command,l,5,l.samples);prefix(command,l,l.cells,Field.CELL_COUNTS,Field.CELL_OFFSETS);stage(command,l,6,l.samples);
        if(l.input.device()!=null){stage(command,l,11,l.input.device().portCapacity());prefix(command,l,l.cells,Field.PORT_COUNTS,Field.PORT_OFFSETS);stage(command,l,12,l.input.device().portCapacity());}
        stage(command,l,7,l.cells);
    }
    void recordSpatial(VkCommandBuffer command,Layout l){
        recordSource(command,l,true);
        scan.record(command,l.conduction.owners,l.conduction.word(VulkanThermalConduction.Field.DEGREE_COUNTS),l.conduction.word(VulkanThermalConduction.Field.DEGREE_OFFSETS),l.word(Field.SCAN));
        stage(command,l,15,spatialCapacity(l));stage(command,l,16,l.samples);
        scan.record(command,l.samples,l.word(Field.BATH_COUNTS),l.word(Field.BATH_OFFSETS),l.word(Field.SCAN),l.word(Field.SAMPLE_PREFIX)+l.samples);
        stage(command,l,17,Math.max(1,l.samples));
    }
    void recordFullTrial(VkCommandBuffer command,Layout l,double duration){
        stage(command,l,24,1,-1,duration);stage(command,l,18,spatialCapacity(l),0,duration);stage(command,l,19,Math.max(1,l.conduction.baths),0,duration);
    }
    void recordSpatialFlux(VkCommandBuffer command,Layout l,double duration,int step){stage(command,l,24,1,step,duration);stage(command,l,20,spatialCapacity(l),step,duration);}
    void recordGraphEdges(VkCommandBuffer command,Layout l){
        stage(command,l,21,l.conduction.owners);stage(command,l,8,l.samples);
        prefix(command,l,l.samples,Field.CONTACT_COUNTS,Field.CONTACT_OFFSETS);stage(command,l,9,Math.max(1,l.samples));stage(command,l,22,1);
    }
    private static int spatialCapacity(Layout l){return Math.addExact(l.samples,l.input.device()==null?0:l.input.device().portCapacity());}
    void recordConductionState(VkCommandBuffer command,Layout l){stage(command,l,14,1);}
    /** The solver has already combined every spatial sample at its unique owner. */
    void recordPublish(VkCommandBuffer command,Layout l){stage(command,l,10,l.conduction.owners);}
    private void prefix(VkCommandBuffer command,Layout l,int n,Field input,Field output){scan.record(command,n,l.word(input),l.word(output),l.word(Field.SCAN));}
    private void stage(VkCommandBuffer command,Layout l,int stage,int count){stage(command,l,stage,count,0,0);}
    private void stage(VkCommandBuffer command,Layout l,int stage,int count,int step,double dt){if(count==0)return;try(var stack=stackPush()){
        bindings.bind(command,stack);var push=stack.malloc(32).putInt(l.headerWord()).putInt(stage).putInt(count).putInt(step).putDouble(dt).putDouble(0).flip();
        vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
        int groups=(count+127)/128;
        if(stage==18||stage==20){
            barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
            vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+l.column(Field.MF_DISPATCH).offset());
        }else vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
        barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }}
    private static int lw(VulkanLiquidStateLayout l,VulkanLiquidStateLayout.Field f){return columnWord(l.column(f));}
    private static int gw(VulkanGasStateLayout l,VulkanGasStateLayout.Field f){return Math.toIntExact(l.column(f).offset()/4);}
    private static int columnWord(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static VulkanLiquidStateLayout.Column allocate(long[] cursor,int n,int width,long a){var c=new VulkanLiquidStateLayout.Column(align(cursor[0],a),n,width);cursor[0]=Math.addExact(c.offset(),c.bindingBytes());return c;}
    private static String shader(){try(var in=VulkanThermalContacts.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/thermal-contacts.comp")){if(in==null)throw new IllegalStateException("Thermal contact shader missing");return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(IOException e){throw new IllegalStateException("Thermal contact shader unavailable",e);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}
