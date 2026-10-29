package mmm.gpu;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Exact signed reduction of binary64 energy terms into a compact device dyadic ledger entry. */
final class VulkanEnergyLedger implements AutoCloseable {
    // All finite binary64 values, with room for the sum of up to Integer.MAX_VALUE terms.
    private static final int WORDS=68;
    record Stream(int bank,int wordOffset,int count,int strideWords,int coefficient,int liveCountWord) {
        Stream(int bank,int wordOffset,int count,int strideWords,int coefficient){this(bank,wordOffset,count,strideWords,coefficient,-1);}
        Stream {
            if((bank!=0&&bank!=1)||wordOffset<0||count<0||strideWords<2||Math.abs(coefficient)!=1||liveCountWord< -1)
                throw new IllegalArgumentException("Invalid device energy stream");
        }
    }
    record Layout(List<Stream> streams,int terms,VulkanLiquidStateLayout.Column header,
                  VulkanLiquidStateLayout.Column scratchA,VulkanLiquidStateLayout.Column scratchB,
                  VulkanLiquidStateLayout.Column result,long endOffset) {
        int headerWordOffset(){return word(header);}
        /** Result: sign, exponent, limb count, reserved, then canonical uint32 limbs. */
        void initializeHeader(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
        }
        ByteBuffer headerBytes() {
            var target=ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            int at=Math.toIntExact(0);target.putInt(at,streams.size());int first=0;
            for(int i=0;i<streams.size();i++) {
                var stream=streams.get(i);int base=at+4*(1+7*i);
                target.putInt(base,first).putInt(base+4,stream.count()).putInt(base+8,stream.bank())
                        .putInt(base+12,stream.wordOffset()).putInt(base+16,stream.strideWords()).putInt(base+20,stream.coefficient()).putInt(base+24,stream.liveCountWord());
                first=Math.addExact(first,stream.count());
            }

            return target;
        }
    }
    static Layout layout(List<Stream> streams,long scratchBase,long alignment) {
        if(scratchBase<0||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Invalid energy workspace");
        var inputs=List.copyOf(streams);int terms=0;
        for(var stream:inputs)terms=Math.addExact(terms,stream.count());
        int blocks=Math.max(1,(int)(((long)terms+1023)/1024));long[] cursor={scratchBase};
        var header=column(cursor,Math.addExact(1,Math.multiplyExact(inputs.size(),7)),alignment);
        var a=column(cursor,Math.multiplyExact(blocks,WORDS),alignment);
        var b=column(cursor,Math.multiplyExact(Math.max(1,(blocks+63)/64),WORDS),alignment);
        var result=column(cursor,WORDS+4,alignment);
        return new Layout(inputs,terms,header,a,b,result,align(cursor[0],alignment));
    }
    private final FlipKernelBindings bindings;
    VulkanEnergyLedger(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,3,shader(),24);}
    void rebindRanges(FlipKernelBindings.BufferRange source,FlipKernelBindings.BufferRange candidate,FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(source,candidate,errors));
    }
    void record(VkCommandBuffer command,Layout layout) {
        int count=Math.max(1,(int)(((long)layout.terms()+1023)/1024));
        int from=word(layout.scratchA()),to=word(layout.scratchB());
        stage(command,layout,0,layout.terms(),0,from,count);
        while(count>1){int groups=(count+63)/64;stage(command,layout,1,count,from,to,groups);count=groups;int swap=from;from=to;to=swap;}
        stage(command,layout,2,1,from,word(layout.result()),1);
    }
    /** Adds the preceding successful private substep's exact result to this substep's result. */
    void recordAccumulate(VkCommandBuffer command,Layout layout,int sourceCanonicalWord){
        if(sourceCanonicalWord<0)throw new IllegalArgumentException("Invalid source energy ledger offset");
        stage(command,layout,3,word(layout.result()),sourceCanonicalWord,word(layout.scratchA()),1);
        stage(command,layout,2,1,word(layout.scratchA()),word(layout.result()),1);
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,int from,int to,int groups) {
        try(var stack=stackPush()) {
            bindings.bind(command,stack);
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,
                    stack.ints(layout.headerWordOffset(),stage,count,from,to,groups));
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,long alignment){
        var column=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,4);cursor[0]=column.offset()+column.bindingBytes();return column;
    }
    private static String shader(){
        try(var input=VulkanEnergyLedger.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/energy-ledger.comp")) {
            if(input==null)throw new IllegalStateException("Energy ledger shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("Energy ledger shader unavailable",failure);}
    }
    @Override public void close(){bindings.close();}
}
