package mmm.gpuprobe;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Resident exact integer transport. CPU builds adjacency; all quantity arithmetic runs on Vulkan. */
public final class VulkanInventoryTransport implements AutoCloseable {
    public record Edge(int source, int destination, long amount) { }
    public record Result(boolean committed, int error, long[] inventory) { }
    public record Receipt(boolean committed, int error, long generation) { }
    public record Checkpoint(long generation, long[] inventory) {
        public Checkpoint {
            if(generation<0) throw new IllegalArgumentException("Negative checkpoint generation");
            inventory=inventory.clone();
        }
        @Override public long[] inventory() { return inventory.clone(); }
    }
    public record Metrics(long submissions, long dispatches, long uploadBytes, long readbackBytes, long gpuNanos) { }
    private final int rows, maximumEdges;
    private VulkanDeviceContext context;
    private FlipGpuResources resources;
    private FlipKernelBindings bindings;
    private GpuStepScheduler scheduler;
    private int committed;
    private boolean ready;
    private long generation;
    private long submissions, dispatches, uploadBytes, readbackBytes, gpuNanos;

    public VulkanInventoryTransport(int device, int rows, int maximumEdges) {
        if(rows<1 || maximumEdges<0 || maximumEdges>Integer.MAX_VALUE/2) throw new IllegalArgumentException("Invalid inventory capacity");
        this.rows=rows; this.maximumEdges=maximumEdges;
        try {
            context=new VulkanDeviceContext(device,true);
            resources=new FlipGpuResources(context);
            resources.addDeviceStorage(8L*rows); resources.addDeviceStorage(8L*rows);
            resources.addDeviceStorage(Math.max(16L,16L*maximumEdges));
            resources.addDeviceStorage(4L*(rows+1L));
            resources.addDeviceStorage(Math.max(4L,8L*maximumEdges));
            resources.addDeviceStorage(4);
            bindings=new FlipKernelBindings(context,resources,shader(),16);
            long staging=Math.max(8L*rows+4,16L*maximumEdges+4L*(rows+1L)+8L*maximumEdges);
            scheduler=new GpuStepScheduler(context,resources,staging);
        } catch(RuntimeException | LinkageError failure) {
            try { close(); } catch(RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }
    public String deviceName() { return context.deviceName(); }
    public Metrics metrics() { return new Metrics(submissions,dispatches,uploadBytes,readbackBytes,gpuNanos); }

    /** Replaces the resident inventory with a fresh generation zero. */
    public synchronized void initialize(long[] inventory) { initialize(inventory,0); }

    public synchronized void restore(Checkpoint checkpoint) { initialize(checkpoint.inventory(),checkpoint.generation()); }
    public synchronized Checkpoint checkpoint() { return new Checkpoint(generation(),snapshot()); }
    public synchronized long generation() { requireReady(); return generation; }

    private void initialize(long[] inventory,long initialGeneration) {
        requireOpen();
        if(inventory.length!=rows) throw new IllegalArgumentException("Inventory row count differs");
        for(long value:inventory) if(value<0) throw new IllegalArgumentException("Negative initial inventory");
        try(MemoryStack stack=stackPush()) {
            var slot=scheduler.acquire(); var cmd=scheduler.begin(slot,"inventory-upload");
            ByteBuffer bytes=slot.stagingBuffer().bytes();
            for(int i=0;i<rows;i++) bytes.putLong(i*8,inventory[i]);
            copy(cmd,slot.stagingBuffer(),0,resources.buffer(1-committed),0,8L*rows,stack);
            barrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
            scheduler.submitAndWait(stack,slot,"inventory-upload"); submissions++; uploadBytes+=8L*rows;
            committed=1-committed; ready=true; generation=initialGeneration;
        }
    }

    public synchronized Result transport(Edge[] edges) {
        Receipt receipt=transportResident(edges);
        return new Result(receipt.committed(),receipt.error(),snapshot());
    }

    /** Ordinary advancement reads only the four-byte GPU error word; quantities remain resident. */
    public synchronized Receipt transportResident(Edge[] edges) {
        requireReady();
        if(edges.length>maximumEdges) throw new IllegalArgumentException("Transport edge capacity exceeded");
        long nextGeneration=Math.addExact(generation,1);
        int[] offsets=new int[rows+1];
        for(Edge e:edges) {
            if(e.source>=0 && e.source<rows) offsets[e.source+1]++;
            if(e.destination>=0 && e.destination<rows) offsets[e.destination+1]++;
        }
        for(int i=1;i<=rows;i++) offsets[i]+=offsets[i-1];
        int[] adjacency=new int[offsets[rows]], cursor=offsets.clone();
        for(int i=0;i<edges.length;i++) {
            Edge e=edges[i];
            if(e.source>=0 && e.source<rows) adjacency[cursor[e.source]++]=2*i;
            if(e.destination>=0 && e.destination<rows) adjacency[cursor[e.destination]++]=2*i+1;
        }
        try(MemoryStack stack=stackPush()) {
            var slot=scheduler.acquire(); var cmd=scheduler.begin(slot,"inventory-transport");
            ByteBuffer bytes=slot.stagingBuffer().bytes();
            long offsetBytes=4L*(rows+1L), edgeBytes=16L*edges.length, adjacencyBytes=4L*adjacency.length;
            for(int i=0;i<edges.length;i++) {
                Edge e=edges[i]; bytes.putInt(i*16,e.source).putInt(i*16+4,e.destination).putLong(i*16+8,e.amount);
            }
            for(int i=0;i<offsets.length;i++) bytes.putInt(Math.toIntExact(edgeBytes+4L*i),offsets[i]);
            for(int i=0;i<adjacency.length;i++) bytes.putInt(Math.toIntExact(edgeBytes+offsetBytes+4L*i),adjacency[i]);
            if(edgeBytes>0) copy(cmd,slot.stagingBuffer(),0,resources.buffer(2),0,edgeBytes,stack);
            copy(cmd,slot.stagingBuffer(),edgeBytes,resources.buffer(3),0,offsetBytes,stack);
            if(adjacencyBytes>0) copy(cmd,slot.stagingBuffer(),edgeBytes+offsetBytes,resources.buffer(4),0,adjacencyBytes,stack);
            vkCmdFillBuffer(cmd,resources.buffer(5).handle(),0,4,0);
            barrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
            if(context.hasTimestampQueries()) {
                vkCmdResetQueryPool(cmd,slot.timestampQueryPool(),0,2);
                vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,slot.timestampQueryPool(),0);
            }
            bindings.bind(cmd,stack);
            ByteBuffer push=stack.malloc(16);
            for(int stage=0;stage<2;stage++) {
                push.clear().putInt(rows).putInt(edges.length).putInt(committed).putInt(stage).flip();
                vkCmdPushConstants(cmd,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
                vkCmdDispatch(cmd,Math.max(1,((stage==0?edges.length:rows)+255)/256),1,1);
                barrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
            }
            if(context.hasTimestampQueries()) vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,slot.timestampQueryPool(),1);
            barrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT,stack);
            copy(cmd,resources.buffer(5),0,slot.stagingBuffer(),0,4,stack);
            barrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_HOST_READ_BIT,stack);
            scheduler.submitAndWait(stack,slot,"inventory-transport");
            submissions++; dispatches+=2; uploadBytes+=edgeBytes+offsetBytes+adjacencyBytes; readbackBytes+=4;
            if(context.hasTimestampQueries()) gpuNanos+=scheduler.readTimestampNanos(slot);
            int error=bytes.getInt(0);
            if(error!=0) return new Receipt(false,error,generation);
            committed=1-committed; generation=nextGeneration;
            return new Receipt(true,0,generation);
        }
    }

    public synchronized long[] snapshot() {
        requireReady();
        try(MemoryStack stack=stackPush()) {
            var slot=scheduler.acquire(); var cmd=scheduler.begin(slot,"inventory-snapshot");
            barrier(cmd,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT,stack);
            copy(cmd,resources.buffer(committed),0,slot.stagingBuffer(),0,8L*rows,stack);
            barrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_HOST_READ_BIT,stack);
            scheduler.submitAndWait(stack,slot,"inventory-snapshot"); submissions++; readbackBytes+=8L*rows;
            long[] result=new long[rows]; for(int i=0;i<rows;i++) result[i]=slot.stagingBuffer().bytes().getLong(i*8);
            return result;
        }
    }
    private void requireOpen() { if(context==null) throw new IllegalStateException("Inventory compute is closed"); }
    private void requireReady() { requireOpen(); if(!ready) throw new IllegalStateException("Inventory not initialized"); }
    private static void copy(VkCommandBuffer cmd,GpuBuffer from,long sourceOffset,GpuBuffer to,long targetOffset,long size,MemoryStack stack) {
        var region=VkBufferCopy.calloc(1,stack); region.get(0).srcOffset(sourceOffset).dstOffset(targetOffset).size(size);
        vkCmdCopyBuffer(cmd,from.handle(),to.handle(),region);
    }
    private static void barrier(VkCommandBuffer cmd,int sourceStage,int sourceAccess,int targetStage,int targetAccess,MemoryStack stack) {
        var barrier=VkMemoryBarrier.calloc(1,stack); barrier.get(0).sType$Default().srcAccessMask(sourceAccess).dstAccessMask(targetAccess);
        vkCmdPipelineBarrier(cmd,sourceStage,targetStage,0,barrier,null,null);
    }
    private static String shader() {
        try(var in=VulkanInventoryTransport.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/inventory.comp")) {
            if(in==null) throw new IllegalStateException("Inventory shader missing");
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException failure) { throw new IllegalStateException("Inventory shader unavailable",failure); }
    }
    @Override public synchronized void close() {
        var oldScheduler=scheduler; var oldBindings=bindings; var oldResources=resources; var oldContext=context;
        scheduler=null; bindings=null; resources=null; context=null; ready=false;
        try {
            if(oldScheduler!=null) oldScheduler.close();
        } finally {
            try { if(oldBindings!=null) oldBindings.close(); }
            finally { try { if(oldResources!=null) oldResources.close(); }
                finally { if(oldContext!=null) oldContext.close(); } }
        }
    }
}
