package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Read-only quantized display projection. Transfers neither exact solid inventory nor composition. */
final class VulkanGranularDisplay implements AutoCloseable {
    private enum Header {
        SIZE_X, SIZE_Y, SIZE_Z, PARTICLE_CAPACITY, SAMPLE_CAPACITY, OUTPUT,
        GEOMETRY_OFFSETS, GEOMETRY_ENTRIES, STATE_FIELDS
    }

    record Layout(VulkanGranularStateLayout state,VulkanGranularGeometry.Layout geometry,
                  VulkanLiquidStateLayout.Column header,VulkanLiquidStateLayout.Column output,int sampleCapacity,long endOffset){
        ByteBuffer headerBytes(){var b=ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(4*Header.SIZE_X.ordinal(),state.sizeX()).putInt(4*Header.SIZE_Y.ordinal(),state.sizeY()).putInt(4*Header.SIZE_Z.ordinal(),state.sizeZ()).putInt(4*Header.PARTICLE_CAPACITY.ordinal(),state.particleCapacity()).putInt(4*Header.SAMPLE_CAPACITY.ordinal(),sampleCapacity)
                    .putInt(4*Header.OUTPUT.ordinal(),word(output)).putInt(4*Header.GEOMETRY_OFFSETS.ordinal(),geometry.word(VulkanGranularGeometry.Field.OFFSETS)).putInt(4*Header.GEOMETRY_ENTRIES.ordinal(),geometry.word(VulkanGranularGeometry.Field.ENTRIES));
            for(var f:VulkanGranularStateLayout.Field.values())b.putInt(4*(Header.STATE_FIELDS.ordinal()+f.ordinal()),state.word(f));return b;}
        mmm.granular.GranularDisplaySnapshot snapshot(ByteBuffer projected,mmm.granular.GranularRegistry registry,float h){
            if(!registry.fingerprint().equals(state.definitions().registryFingerprint())||projected.remaining()!=output.bytes())
                throw new IllegalArgumentException("Granular display projection differs from its definitions or layout");
            var b=projected.slice().order(ByteOrder.LITTLE_ENDIAN);int count=b.getInt(0);if(count<0||count>sampleCapacity)throw new IllegalStateException("Granular display count exceeds its capacity");
            int cells=state.cells();byte[] fill=new byte[cells],porosity=new byte[cells];short[] material=new short[cells];
            for(int c=0;c<cells;c++){int packed=b.getInt(4+4*c);fill[c]=(byte)packed;porosity[c]=(byte)(packed>>>8);material[c]=(short)(packed>>>16);}
            var samples=new java.util.ArrayList<mmm.granular.GranularDisplaySnapshot.ParcelSample>();
            for(int i=0;i<count;i++){int off=4+4*cells+8*i,a=b.getInt(off),v=b.getInt(off+4);samples.add(new mmm.granular.GranularDisplaySnapshot.ParcelSample(a&65535,a>>>16,v&65535,(v>>>16)&255,v>>>24));}
            var palette=state.definitions().materialIds().stream().map(id->new mmm.granular.GranularDisplaySnapshot.MaterialEntry(id,registry.material(registry.materialHandle(id)).baseArgb())).toList();
            return new mmm.granular.GranularDisplaySnapshot(state.sizeX(),state.sizeY(),state.sizeZ(),h,registry.fingerprint(),palette,fill,porosity,material,samples);
        }
    }
    static Layout layout(VulkanGranularStateLayout state,VulkanGranularGeometry.Layout geometry,long base,long alignment){
        if(geometry.state()!=state||base<Math.max(state.endOffset(),geometry.endOffset()))throw new IllegalArgumentException("Granular display geometry differs");
        int samples=Math.min(state.particleCapacity(),mmm.granular.GranularLimits.MAX_DISPLAY_PARCELS_PER_REGION);
        var header=new VulkanLiquidStateLayout.Column(align(base,alignment),32,4);
        var output=new VulkanLiquidStateLayout.Column(align(header.offset()+header.bytes(),alignment),Math.addExact(1+state.cells(),2*samples),4);
        return new Layout(state,geometry,header,output,samples,align(output.offset()+output.bytes(),alignment));
    }
    private final VulkanDeviceContext context;
    private final FlipGpuResources resources;
    private final FlipKernelBindings bindings;
    private GpuStepScheduler scheduler;
    private GpuBuffer output;
    private long capacity;
    VulkanGranularDisplay(VulkanDeviceContext context){this.context=context;resources=new FlipGpuResources(context);
        try{bindings=new FlipKernelBindings(context,2,shader(),24);}catch(RuntimeException|Error failure){resources.close();throw failure;}}
    synchronized mmm.granular.GranularDisplaySnapshot capture(VulkanLiquidRegion.Capture capture,mmm.granular.GranularRegistry registry,double h){
        var inputs=capture.readoutInputs();if(inputs.granular()==null)return null;
        var state=inputs.granular();var geometry=inputs.granularGeometry();
        int samples=Math.min(state.particleCapacity(),mmm.granular.GranularLimits.MAX_DISPLAY_PARCELS_PER_REGION);
        var header=new VulkanLiquidStateLayout.Column(0,32,4);var result=new VulkanLiquidStateLayout.Column(128,1+state.cells()+2*samples,4);
        var l=new Layout(state,geometry,header,result,samples,result.offset()+result.bytes());
        if(l.endOffset()>capacity){
            if(scheduler!=null)scheduler.close();if(output!=null)resources.destroy(output);
            output=resources.addDeviceStorage(l.endOffset());scheduler=new GpuStepScheduler(context,resources,l.output().bytes());capacity=l.endOffset();
        }
        bindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(capture.buffer()),new FlipKernelBindings.BufferRange(output)));
        try(var stack=stackPush()){
            var submission=scheduler.acquire();var command=scheduler.begin(submission,"granular-display");
            vkCmdUpdateBuffer(command,output.handle(),0,l.headerBytes());
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
            record(command,l,h);
            VulkanRegionStorage.copy(command,output,l.output().offset(),submission.stagingBuffer(),0,l.output().bytes(),stack);
            scheduler.submitAndWait(stack,submission,"granular-display");
            return l.snapshot(submission.stagingBuffer().bytes().duplicate().limit(Math.toIntExact(l.output().bytes())),registry,(float)h);
        }
    }
    /** Reads the pinned committed generation and writes only the separate projection buffer. */
    private void record(VkCommandBuffer command,Layout l,double h){try(var stack=stackPush()){
        bindings.bind(command,stack);var push=stack.malloc(24).putInt(word(l.header())).putInt(l.state().cells()).putInt(l.sampleCapacity()).putInt(0).putDouble(h).flip();
        vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);int groups=(Math.max(l.state().cells(),l.sampleCapacity())+127)/128;
        vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_TRANSFER_READ_BIT,stack);
    }}
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    private static String shader(){try(var in=VulkanGranularDisplay.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/granular-display.comp")){
        if(in==null)throw new IllegalStateException("Granular display shader missing");return new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", VulkanShaderLayout.fields("H_", Header.values()) + VulkanShaderLayout.fields("S_", VulkanGranularStateLayout.Field.values()));
    }catch(IOException e){throw new IllegalStateException("Granular display shader unavailable",e);}}
    @Override public synchronized void close(){try{bindings.close();}finally{try{if(scheduler!=null)scheduler.close();}finally{resources.close();}}}
}
