package mmm.gpuprobe;

import mmm.fluid.FluidMaterialRegistry;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Height-function and fixed-wall curvature, with balanced liquid/gas and immiscible-pair loads. */
final class VulkanSurfaceTension implements AutoCloseable {
    record Tables(int[] materialPhases,double[] tension,double[] contactCosine,int[] pairPhases,double[] pairTension) { }
    static Tables tables(FluidMaterialRegistry registry){
        int count=registry.size();int[] phases=new int[count];double[] tension=new double[count],cosine=new double[count];
        for(int i=0;i<count;i++){
            phases[i]=registry.phaseHandle(i);tension[i]=registry.material(i).surfaceTension();
            double angle=registry.material(i).contactAngleRadians();cosine[i]=angle==Math.PI/2?0:Math.cos(angle);
        }
        var pairs=registry.liquidPairTensions().entrySet().stream().filter(e->e.getValue()>0).sorted(java.util.Map.Entry.comparingByKey()).toList();
        int[] labels=new int[2*pairs.size()];double[] values=new double[pairs.size()];
        for(int i=0;i<pairs.size();i++){long key=pairs.get(i).getKey();labels[2*i]=(int)(key>>>32);labels[2*i+1]=(int)key;values[i]=pairs.get(i).getValue();}
        return new Tables(phases,tension,cosine,labels,values);
    }
    record Layout(VulkanLiquidStateLayout liquid,Tables tables,int cellsWordOffset,int facesWordOffset,int planesWordOffset,
                  int masksWordOffset,int flagsWordOffset,VulkanLiquidStateLayout.Column header,
                  VulkanLiquidStateLayout.Column phases,VulkanLiquidStateLayout.Column tensions,VulkanLiquidStateLayout.Column cosines,
                  VulkanLiquidStateLayout.Column pairPhases,VulkanLiquidStateLayout.Column pairTensions,
                  VulkanLiquidStateLayout.Column colours,VulkanLiquidStateLayout.Column smoothed,VulkanLiquidStateLayout.Column normals,
                  VulkanLiquidStateLayout.Column curvature,VulkanLiquidStateLayout.Column capillaryPressure,
                  VulkanLiquidStateLayout.Column cellTension,VulkanLiquidStateLayout.Column cellCosine,long endOffset) {
        ByteBuffer headerBytes(){
            var out=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);int[] data=new int[64];
            data[0]=liquid.sizeX();data[1]=liquid.sizeY();data[2]=liquid.sizeZ();data[3]=liquid.cellCount();
            data[4]=tables.materialPhases().length;data[5]=cellsWordOffset;data[6]=facesWordOffset;data[7]=planesWordOffset;
            data[8]=masksWordOffset;data[9]=flagsWordOffset;
            var columns=List.of(phases,tensions,cosines,pairPhases,pairTensions,colours,smoothed,normals,curvature,capillaryPressure,cellTension,cellCosine);
            for(int i=0;i<columns.size();i++)data[10+i]=word(columns.get(i));
            for(var field:VulkanLiquidStateLayout.Field.values())data[32+field.ordinal()]=word(liquid.column(field));
            for(int i=0;i<data.length;i++)out.putInt(4*i,data[i]);return out;
        }
        void initializeHeaderAndTables(ByteBuffer destination){
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());var out=destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<tables.materialPhases().length;i++)out.putInt(Math.toIntExact(phases.offset()+4L*i),tables.materialPhases()[i]);
            for(int i=0;i<tables.pairPhases().length;i++)out.putInt(Math.toIntExact(pairPhases.offset()+4L*i),tables.pairPhases()[i]);
            write(out,tensions,tables.tension());write(out,cosines,tables.contactCosine());write(out,pairTensions,tables.pairTension());
        }
        int faces(){return liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_X).count()+liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count()+liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z).count();}
    }
    static Layout layout(VulkanLiquidStateLayout liquid,Tables tables,int cellsWordOffset,int facesWordOffset,int planesWordOffset,
                         int masksWordOffset,int flagsWordOffset,long scratchBase,long alignment){
        if(scratchBase<liquid.bytes()||alignment<8||(alignment&(alignment-1))!=0||cellsWordOffset<0||facesWordOffset<0||planesWordOffset<0||masksWordOffset<0)
            throw new IllegalArgumentException("Invalid surface force layout");
        long[] cursor={scratchBase};int cells=liquid.cellCount();var header=column(cursor,64,4,alignment);
        var phases=column(cursor,tables.materialPhases().length,4,alignment);var tensions=column(cursor,tables.tension().length,8,alignment);
        var cosines=column(cursor,tables.contactCosine().length,8,alignment);var pairs=column(cursor,tables.pairPhases().length,4,alignment);
        var pairTension=column(cursor,tables.pairTension().length,8,alignment);var colours=column(cursor,2*cells,8,alignment);
        var smoothed=column(cursor,2*cells,8,alignment);var normals=column(cursor,3*cells,8,alignment);var curvature=column(cursor,cells,8,alignment);
        var pressure=column(cursor,cells,8,alignment);var cellTension=column(cursor,cells,8,alignment);var cellCosine=column(cursor,cells,8,alignment);
        return new Layout(liquid,tables,cellsWordOffset,facesWordOffset,planesWordOffset,masksWordOffset,flagsWordOffset,header,
                phases,tensions,cosines,pairs,pairTension,colours,smoothed,normals,curvature,pressure,cellTension,cellCosine,align(cursor[0],alignment));
    }
    private final FlipKernelBindings bindings;
    VulkanSurfaceTension(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),24);}
    void rebindRanges(FlipKernelBindings.BufferRange candidate,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(candidate,errors));}
    /** Apply before pressure assembly. CapillaryPressure remains available for physical pressure observations. */
    void record(VkCommandBuffer command,Layout layout,double cellSize,double duration){
        boolean surface=false;for(double sigma:layout.tables().tension())surface|=sigma>0;
        stage(command,layout,0,-1,cellSize,duration);
        if(surface)recordInterface(command,layout,-1,cellSize,duration);
        for(int pair=0;pair<layout.tables().pairTension().length;pair++){
            stage(command,layout,5,pair,cellSize,duration);recordInterface(command,layout,pair,cellSize,duration);
        }
    }
    private void recordInterface(VkCommandBuffer command,Layout layout,int pair,double cellSize,double duration){
        for(int stage=1;stage<=4;stage++)stage(command,layout,stage,pair,cellSize,duration);
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int pair,double cellSize,double duration){
        int count=stage==4?layout.faces():layout.liquid().cellCount(),groups=(count+127)/128;
        try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(24).putInt(word(layout.header())).putInt(stage).putInt(pair).putInt(0).putFloat((float)cellSize).putFloat((float)duration).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long alignment){var c=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);cursor[0]=c.offset()+c.bindingBytes();return c;}
    private static void write(ByteBuffer out,VulkanLiquidStateLayout.Column column,double[] values){for(int i=0;i<values.length;i++)out.putDouble(Math.toIntExact(column.offset()+8L*i),values[i]);}
    private static String shader(){
        try(var input=VulkanSurfaceTension.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/surface-tension.comp")){
            if(input==null)throw new IllegalStateException("Surface tension shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("Surface tension shader unavailable",failure);}
    }
    @Override public void close(){bindings.close();}
}
