package mmm.gpuprobe;

import mmm.fluid.air.GasRegistry;
import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ExactRational;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static mmm.gpuprobe.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Conservative gas split, source-volume work handoff, and future topology gather. */
final class VulkanGasRemap implements AutoCloseable {
    record AmbientTables(int[] handles,long[] weights,long totalWeight,long[] massNumerators,long[] massDenominators,
                         int[] speciesOffsets,int[] speciesHandles,long[] speciesNumerators,long[] speciesDenominators,
                         long[] speciesMass,double temperature) { }
    /** Registry ratios are uploaded exactly as positive signed-64-bit coefficients. */
    static AmbientTables ambientTables(GasRegistry gas,VulkanGasStateLayout.Definitions definitions,double temperature) {
        if(!(Double.isFinite(temperature)&&temperature>0))throw new IllegalArgumentException("Invalid ambient temperature");
        int count=gas.ambient().size();int[] handles=new int[count],offsets=new int[count+1];
        long[] weights=new long[count],mn=new long[count],md=new long[count];BigInteger denominator=BigInteger.ONE;
        for(var entry:gas.ambient())denominator=denominator.divide(denominator.gcd(entry.fraction().denominator())).multiply(entry.fraction().denominator());
        var sh=new ArrayList<Integer>();var sn=new ArrayList<Long>();var sd=new ArrayList<Long>();var sm=new ArrayList<Long>();
        for(int i=0;i<count;i++) {
            var entry=gas.ambient().get(i);var component=gas.component(entry.componentId());
            handles[i]=definitions.componentIds().indexOf(entry.componentId());if(handles[i]<0)throw new IllegalArgumentException("Ambient component absent from device definitions");
            weights[i]=entry.fraction().numerator().multiply(denominator.divide(entry.fraction().denominator())).longValueExact();
            var density=ExactRational.parse(Double.toString(component.standardDensityKgPerCubicMeter()));
            var massRatio=new ExactRational(density.numerator().multiply(BigInteger.TEN),density.denominator());
            mn[i]=massRatio.numerator().longValueExact();md[i]=massRatio.denominator().longValueExact();
            for(var species:component.defaultSpeciesConcentrations()) {
                int handle=definitions.speciesIds().indexOf(species.speciesId());if(handle<0)throw new IllegalArgumentException("Ambient species absent from device definitions");
                var c=species.concentrationMolPerStandardCubicMeter();
                var ratio=new ExactRational(c.numerator().multiply(BigInteger.valueOf(ChemicalLimits.QUANTA_PER_MOL)),c.denominator().multiply(BigInteger.valueOf(1_000_000_000L)));
                sh.add(handle);sn.add(ratio.numerator().longValueExact());sd.add(ratio.denominator().longValueExact());sm.add(species.massUnitsPerQuantum());
            }
            offsets[i+1]=sh.size();
        }
        return new AmbientTables(handles,weights,denominator.longValueExact(),mn,md,offsets,sh.stream().mapToInt(Integer::intValue).toArray(),
                sn.stream().mapToLong(Long::longValue).toArray(),sd.stream().mapToLong(Long::longValue).toArray(),sm.stream().mapToLong(Long::longValue).toArray(),temperature);
    }
    enum Field {
        PART_OFFSETS(4),PART_HANDLES(4),PART_NANOS(8),PART_MASS(8),PART_RESIDUAL(8),
        SPECIES_OFFSETS(4),SPECIES_HANDLES(4),SPECIES_AMOUNTS(8),GROUP_ENERGY(8),GROUP_CAPACITY(8),
        WEIGHTS(8),SOURCE_PIVOT(4),ESCAPE_GROUP(4),
        ENERGY_ROUNDOFF(8),SOURCE_ROUNDOFF(8),RETAINED_ROUNDOFF(8),MERGE_ROUNDOFF(8),AUDIT_ENERGY(8),
        AMBIENT_HANDLES(4),AMBIENT_WEIGHTS(8),AMBIENT_MASS_NUM(8),AMBIENT_MASS_DEN(8),AMBIENT_SPECIES_OFFSETS(4),
        AMBIENT_SPECIES_HANDLES(4),AMBIENT_SPECIES_NUM(8),AMBIENT_SPECIES_DEN(8),AMBIENT_SPECIES_MASS(8),AMBIENT_BEFORE(8),AMBIENT_AFTER(8),DISPATCH(4),REQUIREMENTS(4),LONG_ROWS(4),TRANSPORT_PREFIX(8),TRANSPORT_RETAINED_ENERGY(8),DECLARED_SPECIES_OFFSETS(4),DECLARED_SPECIES_HANDLES(4);
        final int width;Field(int width){this.width=width;}
    }
    static final class Layout {
        final VulkanGasRoutes routes;final VulkanGasThermodynamics.Layout sourceEOS;
        final VulkanGasStateLayout retained,future;final AmbientTables ambient;final int partCapacity,speciesCapacity;final boolean transport;final int phaseWordOffset;
        final VulkanLiquidStateLayout.Column header;final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);final long endOffset;
        Layout(VulkanGasRoutes routes,VulkanGasThermodynamics.Layout sourceEOS,VulkanGasStateLayout retained,
               VulkanGasStateLayout future,AmbientTables ambient,int partCapacity,long scratchBase,long alignment,boolean transport,int phaseWordOffset) {
            this.routes=routes;this.sourceEOS=sourceEOS;this.retained=retained;this.future=future;this.ambient=ambient;
            this.partCapacity=partCapacity;this.speciesCapacity=Math.multiplyExact((partCapacity+Math.max(1,routes.gas().definitions().componentIds().size())-1)/Math.max(1,routes.gas().definitions().componentIds().size()),routes.gas().definitions().speciesStride());this.transport=transport;this.phaseWordOffset=phaseWordOffset;
            if(retained.nodeCapacity()!=routes.gas().nodeCapacity()||!retained.definitions().equals(routes.gas().definitions())
                    ||!future.definitions().equals(routes.gas().definitions())||partCapacity<0
                    ||scratchBase<Math.max(Math.max(routes.endOffset(),sourceEOS.endOffset()),Math.max(retained.endOffset(),future.endOffset()))
                    ||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Invalid gas remap layout");
            long[] cursor={scratchBase};header=allocate(cursor,160,4,alignment);
            for(Field field:Field.values()) {
                int n=switch(field) {
                    case PART_OFFSETS->routes.capacity()+1;
                    case PART_HANDLES,PART_NANOS,PART_MASS,PART_RESIDUAL->partCapacity;
                    case SPECIES_OFFSETS->partCapacity+1;
                    case SPECIES_HANDLES,SPECIES_AMOUNTS->speciesCapacity;
                    case TRANSPORT_PREFIX->transport?partCapacity:0;
                    case GROUP_ENERGY,GROUP_CAPACITY,WEIGHTS->routes.capacity();
                    case SOURCE_PIVOT,ESCAPE_GROUP->retained.nodeCapacity();
                    case TRANSPORT_RETAINED_ENERGY->transport?retained.nodeCapacity():0;
                    case ENERGY_ROUNDOFF,MERGE_ROUNDOFF->Math.multiplyExact(routes.capacity(),2);
                    case SOURCE_ROUNDOFF,RETAINED_ROUNDOFF->Math.multiplyExact(retained.nodeCapacity(),2);
                    case AUDIT_ENERGY->2;
                    case AMBIENT_HANDLES,AMBIENT_WEIGHTS,AMBIENT_MASS_NUM,AMBIENT_MASS_DEN,AMBIENT_BEFORE,AMBIENT_AFTER->ambient.handles().length;
                    case AMBIENT_SPECIES_OFFSETS->ambient.speciesOffsets().length;
                    case AMBIENT_SPECIES_HANDLES,AMBIENT_SPECIES_NUM,AMBIENT_SPECIES_DEN,AMBIENT_SPECIES_MASS->ambient.speciesHandles().length;
                    case DISPATCH->27;
                    case LONG_ROWS->Math.addExact(2*Math.addExact(retained.nodeCapacity(),4),Math.addExact(routes.futureGeometry().cells(),4));
                    case REQUIREMENTS->9;
                    case DECLARED_SPECIES_OFFSETS->routes.gas().definitions().componentIds().size()+1;
                    case DECLARED_SPECIES_HANDLES->routes.gas().definitions().speciesStride();
                };
                columns.put(field,allocate(cursor,n,field.width,alignment));
            }
            endOffset=align(cursor[0],alignment);Math.toIntExact(endOffset/4);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}int word(Field field){return Math.toIntExact(column(field).offset()/4);}
        /** Eight uint requirements: parts, raw species, retained components/species, future components/species, future nodes, groups.
         * Component and species storage follow immutable declared slots, including zero quantities. */
        VulkanLiquidStateLayout.Column requirements(){return column(Field.REQUIREMENTS);}
        int headerWordOffset(){return Math.toIntExact(header.offset()/4);}long endOffset(){return endOffset;}
        long requiredPartsOffset(){return column(Field.REQUIREMENTS).offset();}
        long requiredSpeciesOffset(){return column(Field.REQUIREMENTS).offset()+4;}
        ByteBuffer headerBytes(){
            var b=ByteBuffer.allocateDirect(640).order(ByteOrder.LITTLE_ENDIAN);int[] data=new int[160];
            data[0]=routes.gas().definitions().componentIds().size();data[1]=sourceEOS.headerWordOffset();data[2]=routes.capacity();data[3]=partCapacity;data[4]=speciesCapacity;
            data[5]=retained.nodeCapacity();data[6]=future.nodeCapacity();data[7]=retained.componentCapacity();data[8]=retained.speciesCapacity();
            data[9]=future.componentCapacity();data[10]=future.speciesCapacity();data[11]=ambient.handles().length;data[12]=transport?1:0;data[13]=phaseWordOffset;data[14]=retained.nodeCapacity()+4;data[15]=routes.futureGeometry().cells()+4;
            for(var field:VulkanGasStateLayout.Field.values()){
                data[16+field.ordinal()]=Math.toIntExact(routes.gas().column(field).offset()/4);
                data[32+field.ordinal()]=Math.toIntExact(retained.column(field).offset()/4);
                data[48+field.ordinal()]=Math.toIntExact(future.column(field).offset()/4);
            }
            for(Field field:Field.values())data[64+field.ordinal()]=word(field);
            if(transport)data[32+VulkanGasStateLayout.Field.INTERNAL_ENERGY.ordinal()]=word(Field.TRANSPORT_RETAINED_ENERGY);
            data[116]=routes.gas().definitions().speciesStride();
            data[136]=Math.toIntExact(routes.futureGeometry().parents().offset()/4);data[137]=Math.toIntExact(routes.futureGeometry().dryMasks().offset()/4);
            data[138]=Math.toIntExact(routes.futureGeometry().cellNodes().offset()/4);data[139]=routes.futureGeometry().cells();
            int[] graph=routes.words();System.arraycopy(graph,0,data,120,graph.length);
            for(int i=0;i<data.length;i++)b.putInt(4*i,data[i]);b.putLong(4*112,ambient.totalWeight());b.putDouble(4*114,ambient.temperature());return b;
        }
        /** Signed terms whose exact sum is Ufuture + escapedDelta - Uold - capturedDelta - gasWork.
         * For transport layouts, gasWork is absent: donor cooling and carried enthalpy settle internally.
         * Pre-work partition diagnostics occupy the even ENERGY_ROUNDOFF/SOURCE_ROUNDOFF lanes;
         * the endpoint ledger uses only the post-work odd lanes plus retained and gather corrections.
         */
        List<VulkanEnergyLedger.Stream> roundoffStreams(){
            return List.of(new VulkanEnergyLedger.Stream(1,word(Field.RETAINED_ROUNDOFF),retained.nodeCapacity(),4,1),
                    new VulkanEnergyLedger.Stream(1,word(Field.ENERGY_ROUNDOFF)+2,routes.capacity(),4,1),
                    new VulkanEnergyLedger.Stream(1,word(Field.SOURCE_ROUNDOFF)+2,retained.nodeCapacity(),4,1),
                    new VulkanEnergyLedger.Stream(1,word(Field.MERGE_ROUNDOFF),2*routes.capacity(),2,1));
        }
        /** AUDIT_ENERGY stores this step's captured and escaped energy, in that order. */
        void initializeHeaderAndTables(ByteBuffer destination){
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
            for(var chunk:tableChunks())destination.duplicate().position(Math.toIntExact(chunk.offset())).put(chunk.bytes().duplicate());
        }
        List<VulkanGasThermodynamics.TableChunk> tableChunks(){
            var result=new ArrayList<VulkanGasThermodynamics.TableChunk>();
            ints(result,Field.DECLARED_SPECIES_OFFSETS,routes.gas().definitions().speciesOffsets());ints(result,Field.DECLARED_SPECIES_HANDLES,routes.gas().definitions().speciesHandles());
            ints(result,Field.AMBIENT_HANDLES,ambient.handles());longs(result,Field.AMBIENT_WEIGHTS,ambient.weights());
            longs(result,Field.AMBIENT_MASS_NUM,ambient.massNumerators());longs(result,Field.AMBIENT_MASS_DEN,ambient.massDenominators());
            ints(result,Field.AMBIENT_SPECIES_OFFSETS,ambient.speciesOffsets());ints(result,Field.AMBIENT_SPECIES_HANDLES,ambient.speciesHandles());
            longs(result,Field.AMBIENT_SPECIES_NUM,ambient.speciesNumerators());longs(result,Field.AMBIENT_SPECIES_DEN,ambient.speciesDenominators());
            longs(result,Field.AMBIENT_SPECIES_MASS,ambient.speciesMass());return List.copyOf(result);
        }
        private void ints(List<VulkanGasThermodynamics.TableChunk> out,Field field,int[] values){
            for(int first=0;first<values.length;first+=16384){int n=Math.min(16384,values.length-first);var b=ByteBuffer.allocateDirect(4*n).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<n;i++)b.putInt(4*i,values[first+i]);out.add(new VulkanGasThermodynamics.TableChunk(column(field).offset()+4L*first,b));}
        }
        private void longs(List<VulkanGasThermodynamics.TableChunk> out,Field field,long[] values){
            for(int first=0;first<values.length;first+=8192){int n=Math.min(8192,values.length-first);var b=ByteBuffer.allocateDirect(8*n).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<n;i++)b.putLong(8*i,values[first+i]);out.add(new VulkanGasThermodynamics.TableChunk(column(field).offset()+8L*first,b));}
        }

    }
    static Layout layout(VulkanGasLineage.Layout lineage,VulkanGasThermodynamics.Layout sourceEOS,VulkanGasStateLayout retained,
                         VulkanGasStateLayout future,AmbientTables ambient,int partCapacity,long scratchBase,long alignment){
        return layout(VulkanGasRoutes.from(lineage),sourceEOS,retained,future,ambient,partCapacity,scratchBase,alignment);
    }
    static Layout layout(VulkanGasRoutes routes,VulkanGasThermodynamics.Layout sourceEOS,VulkanGasStateLayout retained,
                         VulkanGasStateLayout future,AmbientTables ambient,int partCapacity,long scratchBase,long alignment){
        return new Layout(routes,sourceEOS,retained,future,ambient,partCapacity,scratchBase,alignment,false,0);
    }
    /** Transport retains each source's self route, then expands that actual retained inventory
     * from V * retainedN / sourceN back into the unchanged chamber volume. */
    static Layout transportLayout(VulkanGasRoutes routes,VulkanGasThermodynamics.Layout sourceEOS,VulkanGasStateLayout retained,
                                  VulkanGasStateLayout future,AmbientTables ambient,int phaseWordOffset,int partCapacity,long scratchBase,long alignment){
        if(phaseWordOffset<0)throw new IllegalArgumentException("Negative transport phase offset");
        return new Layout(routes,sourceEOS,retained,future,ambient,partCapacity,scratchBase,alignment,true,phaseWordOffset);
    }
    /** After prepare, before retained EOS/work. Work's future volumes are sourceEOS.nodeVolumes. */
    void recordTransportVolumes(VkCommandBuffer command,Layout l,VulkanGasFlow.Layout flow){
        if(!l.transport)throw new IllegalArgumentException("Transport layout required");
        stage(command,l,23,l.retained.nodeCapacity(),null,flow.word(VulkanGasFlow.Field.RETAINED_VOLUME),flow.word(VulkanGasFlow.Field.MODES));
    }
    private final FlipKernelBindings bindings;private FlipKernelBindings.BufferRange arena;
    VulkanGasRemap(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),20);}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){this.arena=arena;bindings.rebindRanges(List.of(arena,errors));}
    /** Measures all nested capacities without writing any part/species/output pool. */
    void recordMeasure(VkCommandBuffer command,Layout l){
        stage(command,l,18,1,null);
        stage(command,l,25,Math.max(l.retained.nodeCapacity()+4,l.routes.futureGeometry().cells()+4),null);
        stage(command,l,26,1,null);
        stage(command,l,22,128,null);
    }
    void recordPrepare(VkCommandBuffer command,Layout l){
        recordPrepare(command,l,null);
    }
    void recordPrepare(VkCommandBuffer command,Layout l,VulkanStageTimings timings){
        timed(timings,command,"remap-measure",()->{
            recordMeasure(command,l);
            stage(command,l,0,Math.max(l.routes.capacity(),Math.max(l.retained.nodeCapacity(),l.future.nodeCapacity())),null);
            stage(command,l,1,l.routes.capacity(),null);
        });
        timed(timings,command,"remap-split",()->{stage(command,l,2,l.retained.nodeCapacity(),null);stage(command,l,3,1,null);});
        timed(timings,command,"remap-partition-energy",()->{stage(command,l,4,l.routes.capacity(),null);stage(command,l,5,l.retained.nodeCapacity(),null);});
        if(!l.transport){gather(command,l,false,null,timings);stage(command,l,16,l.routes.futureGeometry().cells(),null);}
    }
    void recordFinish(VkCommandBuffer command,Layout l,VulkanGasAdiabaticWork.Layout work){
        recordFinish(command,l,work,null);
    }
    void recordFinish(VkCommandBuffer command,Layout l,VulkanGasAdiabaticWork.Layout work,VulkanStageTimings timings){
        if(l.transport)timed(timings,command,"gas-flow-energy",()->stage(command,l,24,l.retained.nodeCapacity(),work));
        else{timed(timings,command,"remap-group-energy",()->stage(command,l,9,l.routes.capacity(),work));
            timed(timings,command,"remap-source-energy",()->stage(command,l,10,l.retained.nodeCapacity(),work));}
        gather(command,l,true,work,timings);
        timed(timings,command,"remap-topology",()->stage(command,l,15,l.routes.futureGeometry().cells(),work));
    }
    /** Preserve the already partitioned source U while rebuilding only inventory topology. */
    void recordFinishFixedEnergy(VkCommandBuffer command,Layout l){
        if(l.transport)throw new IllegalArgumentException("Geometry remap required for fixed energy");
        stage(command,l,27,Math.max(l.routes.capacity(),l.retained.nodeCapacity()),null);
        gather(command,l,true,null,null);stage(command,l,15,l.routes.futureGeometry().cells(),null);
    }
    private void gather(VkCommandBuffer command,Layout l,boolean future,VulkanGasAdiabaticWork.Layout work){
        gather(command,l,future,work,null);
    }
    private void gather(VkCommandBuffer command,Layout l,boolean future,VulkanGasAdiabaticWork.Layout work,VulkanStageTimings timings){
        var output=future?l.future:l.retained;
        timed(timings,command,"remap-gather-inventory-energy",()->stage(command,l,future?12:7,output.nodeCapacity()+4,work));
    }
    private static void timed(VulkanStageTimings timings,VkCommandBuffer command,String label,Runnable action){
        if(timings==null)action.run();else timings.record(command,label,action);
    }
    private void stage(VkCommandBuffer cmd,Layout l,int stage,int count,VulkanGasAdiabaticWork.Layout work){
        stage(cmd,l,stage,count,work,work==null?0:Math.toIntExact(work.candidateEnergy().offset()/4),work==null?0:Math.toIntExact(work.candidateTemperature().offset()/4));
    }
    private void stage(VkCommandBuffer cmd,Layout l,int stage,int count,VulkanGasAdiabaticWork.Layout work,int first,int second){
        if(count==0)return;
        if(stage==7||stage==12||stage==23||stage==24||stage==2&&l.transport)
            stage(cmd,l,stage+100,count,work,first,second);
        int groups=(int)(((long)count+127)/128);try(var stack=stackPush()){
            bindings.bind(cmd,stack);vkCmdPushConstants(cmd,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(l.headerWordOffset(),stage,count,
                    first,second));
            int indirect=switch(stage){case 1,4,9->0;case 2,5,10->1;case 7->2;case 12->3;case 23,24->4;case 107->5;case 112->6;case 102,123,124->7;default->-1;};
            if(stage==2&&l.transport)indirect=4;
            if(indirect>=0){barrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(cmd,arena.buffer().handle(),arena.offset()+l.column(Field.DISPATCH).offset()+12L*indirect);
            }else vkCmdDispatch(cmd,Math.min(groups,65535),(groups+65534)/65535,1);barrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static VulkanLiquidStateLayout.Column allocate(long[] cursor,int count,int width,long alignment){var c=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);cursor[0]=Math.addExact(c.offset(),c.bindingBytes());return c;}
    private static String shader(){try(var input=VulkanGasRemap.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/gas-remap.comp")){if(input==null)throw new IllegalStateException("Gas remap shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);}catch(IOException failure){throw new IllegalStateException("Gas remap shader unavailable",failure);}}
    @Override public void close(){bindings.close();}
}
