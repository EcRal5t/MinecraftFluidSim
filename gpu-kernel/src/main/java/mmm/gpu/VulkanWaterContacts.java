package mmm.gpu;

import mmm.fluid.FluidDomainContracts;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Read-only physical water contact rectangles; only contacting stock keys/area/volume return. */
final class VulkanWaterContacts implements AutoCloseable {
    private enum Header {
        SIZE_X(0), SIZE_Y(1), SIZE_Z(2), WATER_MATERIAL(3),
        FACE_COUNT(4), CELL_COUNT(5), CELL_OFFSETS(6), MATERIALS(7),
        SUPPORT_MASKS(8), VOLUMES(9), MASS_UNITS(10), FIXED_MASKS(11),
        PLANES(12), GEOMETRY_CELLS(13), FACES(14), CELLS(15),
        RANGES(16), RECORDS(17), COUNT_OUTPUT(18), CELL_SIZE(32),
        CELL_VOLUME(34);
        final int word; Header(int word) { this.word = word; }
    }
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        return result.toString();
    }

    private final VulkanDeviceContext context;private final FlipGpuResources resources;private final FlipKernelBindings bindings;
    private GpuStepScheduler scheduler;private GpuBuffer output;private long capacity;
    VulkanWaterContacts(VulkanDeviceContext context){this.context=context;resources=new FlipGpuResources(context);
        try(var in=getClass().getResourceAsStream("/assets/mmm_gpu_probe/shaders/water-contacts.comp")){
            if(in==null)throw new IllegalStateException("Water contact shader missing");bindings=new FlipKernelBindings(context,2,new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout()),16);
        }catch(java.io.IOException|RuntimeException|Error failure){resources.close();throw new IllegalStateException(failure);}}
    synchronized List<mmm.fluid.FluidDomainContracts.WaterContact> capture(VulkanLiquidRegion.Capture capture,VulkanFluidStateCodec codec,List<mmm.fluid.FluidDomainContracts.WaterContactFace> requested){
        if(requested==null)throw new IllegalArgumentException("Water contact faces are missing");var faces=List.copyOf(new java.util.LinkedHashSet<>(requested));
        var in=capture.readoutInputs();var l=in.liquid();int water=-1;for(int m=0;m<codec.fluids().size();m++)if(codec.fluids().material(m).id().equals("mmm:water"))water=m;
        if(water<0||faces.isEmpty())return List.of();double size=codec.template().config().cellSize();int[] dims={l.sizeX(),l.sizeY(),l.sizeZ()};var cells=new java.util.TreeSet<Integer>();
        for(var face:faces){int axis=face.axis(),a=(axis+1)%3,b=(axis+2)%3;double c=face.coordinateMetres()/size;int normal=face.side()>0?(int)Math.floor(c):(int)Math.ceil(c)-1;if(normal<0||normal>=dims[axis])continue;
            int firstA=Math.max(0,(int)Math.floor(face.minimumAMetres()/size)),lastA=Math.min(dims[a]-1,(int)Math.ceil(face.maximumAMetres()/size)-1);
            int firstB=Math.max(0,(int)Math.floor(face.minimumBMetres()/size)),lastB=Math.min(dims[b]-1,(int)Math.ceil(face.maximumBMetres()/size)-1);
            for(int x=firstA;x<=lastA;x++)for(int y=firstB;y<=lastB;y++){int[] q=new int[3];q[axis]=normal;q[a]=x;q[b]=y;int cell=l.grid().cell(q[0],q[1],q[2]);if(cell>=0)cells.add(cell);}}
        if(cells.isEmpty())return List.of();int records=Math.multiplyExact(255,cells.size());long facesAt=256,cellsAt=facesAt+48L*faces.size(),rangesAt=(cellsAt+4L*cells.size()+7)&-8L,countAt=rangesAt+16L*cells.size(),recordsAt=countAt+8,bytes=recordsAt+32L*records;
        if(bytes>capacity){if(scheduler!=null)scheduler.close();if(output!=null)resources.destroy(output);output=resources.addDeviceStorage(bytes);scheduler=new GpuStepScheduler(context,resources,Math.max(4096,32L*records));capacity=bytes;}
        var header=ByteBuffer.allocateDirect(Math.toIntExact(cellsAt+4L*cells.size())).order(ByteOrder.LITTLE_ENDIAN);int[] h=new int[32];h[Header.SIZE_X.word]=dims[0];h[Header.SIZE_Y.word]=dims[1];h[Header.SIZE_Z.word]=dims[2];h[Header.WATER_MATERIAL.word]=water;h[Header.FACE_COUNT.word]=faces.size();h[Header.CELL_COUNT.word]=cells.size();
        h[Header.CELL_OFFSETS.word]=word(l.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS));h[Header.MATERIALS.word]=word(l.column(VulkanLiquidStateLayout.Field.MATERIALS));h[Header.SUPPORT_MASKS.word]=word(l.column(VulkanLiquidStateLayout.Field.SUPPORT_MASKS));h[Header.VOLUMES.word]=word(l.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES));h[Header.MASS_UNITS.word]=word(l.column(VulkanLiquidStateLayout.Field.MASS_UNITS));
        h[Header.FIXED_MASKS.word]=word(in.geometry().get(VulkanLiquidRegion.GeometryField.FIXED_MASKS));h[Header.PLANES.word]=word(in.geometry().get(VulkanLiquidRegion.GeometryField.PLANES));h[Header.GEOMETRY_CELLS.word]=word(in.geometry().get(VulkanLiquidRegion.GeometryField.CELLS));
        h[Header.FACES.word]=Math.toIntExact(facesAt/4);h[Header.CELLS.word]=Math.toIntExact(cellsAt/4);h[Header.RANGES.word]=Math.toIntExact(rangesAt/4);h[Header.RECORDS.word]=Math.toIntExact(recordsAt/4);h[Header.COUNT_OUTPUT.word]=Math.toIntExact(countAt/4);
        for(int i=0;i<h.length;i++)header.putInt(4*i,h[i]);header.putDouble(4*Header.CELL_SIZE.word,size).putDouble(4*Header.CELL_VOLUME.word,size*size*size);
        header.position(Math.toIntExact(facesAt));for(var face:faces)header.putInt(face.axis()).putInt(face.side()).putDouble(face.coordinateMetres()/size).putDouble(face.minimumAMetres()/size).putDouble(face.maximumAMetres()/size).putDouble(face.minimumBMetres()/size).putDouble(face.maximumBMetres()/size);
        for(int cell:cells)header.putInt(cell);header.flip();bindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(capture.buffer()),new FlipKernelBindings.BufferRange(output)));
        try(var stack=stackPush()){
            var submission=scheduler.acquire();var command=scheduler.begin(submission,"water-contacts");
            int uploadBytes=header.remaining();for(int offset=0;offset<uploadBytes;offset+=65536){int chunk=Math.min(65536,uploadBytes-offset);vkCmdUpdateBuffer(command,output.handle(),offset,header.slice(offset,chunk));}
            vkCmdFillBuffer(command,output.handle(),countAt,8,0);VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
            for(int stage=0;stage<2;stage++){int count=stage==0?cells.size():records;bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(stage,count,0,0));int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,stage==0?VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT:VK_PIPELINE_STAGE_TRANSFER_BIT,stage==0?VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT:VK_ACCESS_TRANSFER_READ_BIT,stack);}
            VulkanRegionStorage.copy(command,output,countAt,submission.stagingBuffer(),0,8,stack);scheduler.submitAndWait(stack,submission,"water-contacts");var first=submission.stagingBuffer().bytes().order(ByteOrder.LITTLE_ENDIAN);int actual=first.getInt(0);if(first.getInt(4)!=0||actual<0||actual>records)throw new IllegalStateException("Water contact projection exceeds its selected stock capacity");if(actual==0)return List.of();
            submission=scheduler.acquire();command=scheduler.begin(submission,"water-contact-results");VulkanRegionStorage.copy(command,output,recordsAt,submission.stagingBuffer(),0,32L*actual,stack);scheduler.submitAndWait(stack,submission,"water-contact-results");
            var b=submission.stagingBuffer().bytes().order(ByteOrder.LITTLE_ENDIAN);var result=new java.util.ArrayList<mmm.fluid.FluidDomainContracts.WaterContact>();for(int row=0;row<actual;row++){int off=32*row;result.add(new mmm.fluid.FluidDomainContracts.WaterContact(b.getInt(off),b.getInt(off+4),b.getInt(off+8),b.getDouble(off+16),b.getDouble(off+24)));}
            result.sort(java.util.Comparator.comparingInt(mmm.fluid.FluidDomainContracts.WaterContact::cell).thenComparingInt(mmm.fluid.FluidDomainContracts.WaterContact::materialHandle).thenComparingInt(mmm.fluid.FluidDomainContracts.WaterContact::supportMask));return List.copyOf(result);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    @Override public synchronized void close(){try{bindings.close();}finally{try{if(scheduler!=null)scheduler.close();}finally{resources.close();}}}
}
