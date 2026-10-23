package mmm.gpuprobe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.nio.charset.StandardCharsets;
import static mmm.gpuprobe.VulkanRegionStorage.copy;
import static mmm.gpuprobe.VulkanRegionStorage.barrier;
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
    private VulkanRegionStorage storage;
    private long dispatches;

    public VulkanInventoryTransport(int device, int rows, int maximumEdges) {
        if(rows<1 || maximumEdges<0 || maximumEdges>Integer.MAX_VALUE/2) throw new IllegalArgumentException("Invalid inventory capacity");
        this.rows=rows; this.maximumEdges=maximumEdges;
        try {
            context=new VulkanDeviceContext(device,true);
            resources=new FlipGpuResources(context);
            resources.addDeviceStorage(Math.max(16L,16L*maximumEdges));
            resources.addDeviceStorage(4L*(rows+1L));
            resources.addDeviceStorage(Math.max(4L,8L*maximumEdges));
            bindings=new FlipKernelBindings(context,6,shader(),16);
            long staging=16L*maximumEdges+4L*(rows+1L)+8L*maximumEdges;
            storage=new VulkanRegionStorage(context,8L*rows,Math.toIntExact(staging));
        } catch(RuntimeException | LinkageError failure) {
            try { close(); } catch(RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }
    public String deviceName() { return context.deviceName(); }
    public synchronized Metrics metrics() {
        requireOpen(); var metrics=storage.metrics();
        return new Metrics(metrics.submissions(),dispatches,metrics.uploadBytes(),metrics.readbackBytes(),metrics.gpuNanos());
    }

    /** Replaces the resident inventory with a fresh generation zero. */
    public synchronized void initialize(long[] inventory) { initialize(inventory,0); }

    public synchronized void restore(Checkpoint checkpoint) { initialize(checkpoint.inventory(),checkpoint.generation()); }
    public synchronized Checkpoint checkpoint() { return new Checkpoint(generation(),snapshot()); }
    public synchronized long generation() {
        requireOpen(); try(var capture=storage.capture()) { return capture.generation(); }
    }

    private void initialize(long[] inventory,long initialGeneration) {
        requireOpen();
        if(inventory.length!=rows) throw new IllegalArgumentException("Inventory row count differs");
        for(long value:inventory) if(value<0) throw new IllegalArgumentException("Negative initial inventory");
        ByteBuffer bytes=ByteBuffer.allocate(Math.multiplyExact(rows,8)).order(ByteOrder.nativeOrder());
        for(long value:inventory) bytes.putLong(value);
        storage.initialize(bytes.flip(),initialGeneration);
    }

    public synchronized Result transport(Edge[] edges) {
        Receipt receipt=transportResident(edges);
        return new Result(receipt.committed(),receipt.error(),snapshot());
    }

    /** Ordinary advancement reads only the four-byte GPU error word; quantities remain resident. */
    public synchronized Receipt transportResident(Edge[] edges) {
        requireOpen();
        if(edges.length>maximumEdges) throw new IllegalArgumentException("Transport edge capacity exceeded");
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
        try(var capture=storage.capture(); var candidate=storage.prepare(capture)) {
            long offsetBytes=4L*(rows+1L), edgeBytes=16L*edges.length, adjacencyBytes=4L*adjacency.length;
            int error=candidate.execute(edgeBytes+offsetBytes+adjacencyBytes,(cmd,source,target,errors,staging,stack)-> {
                bindings.rebind(List.of(source,target,resources.buffer(0),resources.buffer(1),resources.buffer(2),errors));
                ByteBuffer bytes=staging.bytes();
                for(int i=0;i<edges.length;i++) {
                    Edge e=edges[i]; bytes.putInt(i*16,e.source).putInt(i*16+4,e.destination).putLong(i*16+8,e.amount);
                }
                for(int i=0;i<offsets.length;i++) bytes.putInt(Math.toIntExact(edgeBytes+4L*i),offsets[i]);
                for(int i=0;i<adjacency.length;i++) bytes.putInt(Math.toIntExact(edgeBytes+offsetBytes+4L*i),adjacency[i]);
                if(edgeBytes>0) copy(cmd,staging,0,resources.buffer(0),0,edgeBytes,stack);
                copy(cmd,staging,edgeBytes,resources.buffer(1),0,offsetBytes,stack);
                if(adjacencyBytes>0) copy(cmd,staging,edgeBytes+offsetBytes,resources.buffer(2),0,adjacencyBytes,stack);
                barrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                bindings.bind(cmd,stack);
                ByteBuffer push=stack.malloc(16);
                for(int stage=0;stage<2;stage++) {
                    push.clear().putInt(rows).putInt(edges.length).putInt(0).putInt(stage).flip();
                    vkCmdPushConstants(cmd,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
                    vkCmdDispatch(cmd,Math.max(1,((stage==0?edges.length:rows)+255)/256),1,1);
                    barrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                }
            });
            dispatches+=2;
            if(error!=0) return new Receipt(false,error,capture.generation());
            return new Receipt(true,0,candidate.commit());
        }
    }

    public synchronized long[] snapshot() {
        requireOpen();
        try(var capture=storage.capture()) {
            ByteBuffer bytes=ByteBuffer.allocate(Math.multiplyExact(rows,8)).order(ByteOrder.nativeOrder());
            storage.read(capture,0,bytes);
            long[] result=new long[rows];
            bytes.flip().asLongBuffer().get(result);
            return result;
        }
    }
    private void requireOpen() { if(context==null) throw new IllegalStateException("Inventory compute is closed"); }

    private static String shader() {
        try(var in=VulkanInventoryTransport.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/inventory.comp")) {
            if(in==null) throw new IllegalStateException("Inventory shader missing");
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException failure) { throw new IllegalStateException("Inventory shader unavailable",failure); }
    }
    @Override public synchronized void close() {
        var oldStorage=storage; var oldBindings=bindings; var oldResources=resources; var oldContext=context;
        storage=null; bindings=null; resources=null; context=null;
        try {
            if(oldStorage!=null) oldStorage.close();
        } finally {
            try { if(oldBindings!=null) oldBindings.close(); }
            finally { try { if(oldResources!=null) oldResources.close(); }
                finally { if(oldContext!=null) oldContext.close(); } }
        }
    }
}
