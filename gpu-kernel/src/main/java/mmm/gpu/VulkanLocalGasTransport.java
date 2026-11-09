package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Conservative local gas routes over the registered inventory columns. */
final class VulkanLocalGasTransport implements AutoCloseable {
    enum Field { HEADER(4), NANOS(8), MASS(8), RESIDUAL(8), SPECIES(8), ENERGY(8), VOLUME(8), PRIMAL_MASS(8), WORK(8), EXTERNAL_PRESSURE_WORK(8), OLD_VOLUME(8), OLD_MASS(8), NEW_MASS(8), INFLOW_NANOS(8), EXTERNAL_SOURCE_ENERGY(8), EXTERNAL_SINK_ENERGY(8), AMBIENT_DATA(8), ENERGY_ROUNDOFF(8), GATHER_ROUNDOFF(8), SPECIES_MASS(8); final int width; Field(int width){this.width=width;} }
    /** Production storage uses the resident cell inventory, mixture, and shared MAC fields. */
    static final class ProductionLayout {
        final VulkanGasStateLayout source; final VulkanGasThermodynamics.Layout eos;
        final VulkanMixtureState.Layout mixture; final int cells,faces,faceFlux,projectedVelocity,velocityStride,pressureWord,activityCells,activityFaces;
        final boolean sealedExterior; final VulkanGasAmbient.AmbientTables ambient; final long[] speciesMasses;
        int targetMasks=-1,targetSolid=-1;
        final EnumMap<Field,VulkanLiquidStateLayout.Column> fields=new EnumMap<>(Field.class); final long endOffset;
        ProductionLayout(VulkanGasStateLayout source,VulkanGasThermodynamics.Layout eos,VulkanMixtureState.Layout mixture,
                         int faceFlux,int projectedVelocity,int velocityStride,int pressureWord,int activityCells,int activityFaces,
                         boolean sealedExterior,VulkanGasAmbient.AmbientTables ambient,long[] speciesMasses,long base,long alignment) {
            this.source=source;this.eos=eos;this.mixture=mixture;this.faceFlux=faceFlux;this.projectedVelocity=projectedVelocity;this.velocityStride=velocityStride;
            this.pressureWord=pressureWord;this.activityCells=activityCells;this.activityFaces=activityFaces;
            this.sealedExterior=sealedExterior;this.ambient=java.util.Objects.requireNonNull(ambient);this.speciesMasses=speciesMasses.clone();
            if(speciesMasses.length!=source.definitions().speciesIds().size())throw new IllegalArgumentException("Gas species mass table differs");
            cells=source.grid().cellCount();
            faces=source.grid().faceCount();
            if(source.nodeCapacity()<cells||eos.gas()!=source||mixture.inputs().cells()!=cells||faceFlux<0||projectedVelocity<0||velocityStride<1||pressureWord<0||base<mixture.endOffset())throw new IllegalArgumentException("Invalid production gas transport layout");
            long cursor=base;int components=source.definitions().componentIds().size(),species=source.definitions().speciesStride();
            for(Field f:Field.values()) {
                int n=switch(f){case HEADER->96;case NANOS,MASS,RESIDUAL->6*cells*components;case SPECIES->6*cells*species;case ENERGY,VOLUME,INFLOW_NANOS,ENERGY_ROUNDOFF->6*cells;case GATHER_ROUNDOFF->21*cells;case WORK,EXTERNAL_PRESSURE_WORK,OLD_VOLUME,OLD_MASS,NEW_MASS,EXTERNAL_SOURCE_ENERGY,EXTERNAL_SINK_ENERGY->cells;case AMBIENT_DATA->5*ambient.handles().length+4*ambient.speciesHandles().length+1;case SPECIES_MASS->speciesMasses.length;default->faces;};
                long offset=(cursor+alignment-1)&-alignment;var c=new VulkanLiquidStateLayout.Column(offset,n,f.width);fields.put(f,c);cursor=offset+c.bindingBytes();
            }
            endOffset=(cursor+alignment-1)&-alignment;
        }
        VulkanLiquidStateLayout.Column column(Field f){return fields.get(f);}
        void bindTargetOccupation(int masks,int solid){targetMasks=masks;targetSolid=solid;}
        int word(Field f){return Math.toIntExact(column(f).offset()/4);}
        ByteBuffer headerBytes(){
            int[] h=new int[96];h[0]=cells;h[1]=source.definitions().componentIds().size();h[2]=source.definitions().speciesStride();h[3]=eos.headerWordOffset();
            for(Field f:Field.values())h[64+f.ordinal()]=word(f);
            for(var f:VulkanGasStateLayout.Field.values())h[16+f.ordinal()]=Math.toIntExact(source.column(f).offset()/4);
            h[48]=mixture.statesWord();h[51]=projectedVelocity;h[52]=projectedVelocity-2;h[53]=pressureWord;h[54]=activityCells;h[55]=activityFaces;h[56]=faceFlux;h[59]=velocityStride;
            h[57]=targetMasks;h[58]=targetSolid;
            h[60]=source.sizeX();h[61]=source.sizeY();h[62]=source.sizeZ();h[63]=1;
            h[5]=sealedExterior?1:0;h[6]=ambient.handles().length;h[7]=ambient.speciesHandles().length;
            var target=ByteBuffer.allocateDirect(384).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<h.length;i++)target.putInt(4*i,h[i]);
            target.putDouble(4*12,mixture.inputs().cellSize());target.putLong(4*8,ambient.totalWeight());target.putDouble(4*10,ambient.temperature());return target;
        }
        ByteBuffer ambientBytes(){
            var b=ByteBuffer.allocateDirect(Math.toIntExact(column(Field.AMBIENT_DATA).bytes())).order(ByteOrder.LITTLE_ENDIAN);
            for(int v:ambient.handles())b.putLong(v);for(long v:ambient.weights())b.putLong(v);
            for(long v:ambient.massNumerators())b.putLong(v);for(long v:ambient.massDenominators())b.putLong(v);
            for(int v:ambient.speciesOffsets())b.putLong(v);for(int v:ambient.speciesHandles())b.putLong(v);
            for(long v:ambient.speciesNumerators())b.putLong(v);for(long v:ambient.speciesDenominators())b.putLong(v);
            for(long v:ambient.speciesMass())b.putLong(v);return b.flip();
        }
        ByteBuffer speciesMassBytes(){var b=ByteBuffer.allocateDirect(Math.toIntExact(column(Field.SPECIES_MASS).bindingBytes())).order(ByteOrder.LITTLE_ENDIAN);for(long mass:speciesMasses)b.putLong(mass);b.position(0);return b;}
        void initialize(ByteBuffer target){target.duplicate().position(Math.toIntExact(column(Field.HEADER).offset())).put(headerBytes());}
    }
    private final FlipKernelBindings bindings;
    VulkanLocalGasTransport(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),20);}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors));}
    void record(VkCommandBuffer command,ProductionLayout l,org.lwjgl.system.MemoryStack stack,double duration,VulkanStageTimings timings){
        if(!(Double.isFinite(duration)&&duration>0))throw new IllegalArgumentException("Invalid gas transport duration");
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        timed(command,timings,"local-gas-split",()->productionStage(command,l,0,l.cells,duration));
        timed(command,timings,"local-gas-gather",()->productionStage(command,l,1,l.cells,duration));
        timed(command,timings,"local-gas-face-mass",()->productionStage(command,l,2,l.faces,duration));
    }
    private static void timed(VkCommandBuffer command,VulkanStageTimings timings,String label,Runnable action){
        if(timings==null)action.run();else timings.record(command,label,action);
    }
    /** Apply -p dV using actual routed volumes once the future mixture volume is known. */
    void recordPressureWork(VkCommandBuffer command,ProductionLayout l,int futureMixtureWord){
        productionStage(command,l,6,l.cells,Double.longBitsToDouble(Integer.toUnsignedLong(futureMixtureWord)));
    }
    private void productionStage(VkCommandBuffer command,ProductionLayout l,int stage,int count,double value){try(var stack=stackPush()){
        long bits=Double.doubleToRawLongBits(value);bindings.bind(command,stack);
        vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(l.word(Field.HEADER),stage,count,(int)bits,(int)(bits>>>32)));
        int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }}
    private static String shader(){
        String source=VulkanLiquidPhaseRows.resource("local-gas-transport.comp");
        return source.replace("// HOST_LAYOUT",VulkanShaderLayout.fields("F_",Field.values())+VulkanShaderLayout.fields("G_",VulkanGasStateLayout.Field.values())+VulkanGasThermodynamics.shaderLayout()+"\n#define MIXTURE_BULK "+mmm.thermal.MixtureVolumeModel.LIQUID_BULK_PA+"LF\n#define MIXTURE_PRESSURE_LIMIT "+mmm.thermal.MixtureVolumeModel.PRESSURE_LIMIT_PA+"LF\n")
                .replace("// LOCAL_GAS_GRID",VulkanLiquidPhaseRows.resource("local-gas-grid.glsl"))
                .replace("// GAS_INTEGER_QUANTITIES",VulkanLiquidPhaseRows.resource("gas-integer-quantities.glsl"))
                .replace("// GAS_CALORIC_MATERIAL",VulkanLiquidPhaseRows.resource("gas-caloric-material.glsl"));
    }
    @Override public void close(){bindings.close();}
}
