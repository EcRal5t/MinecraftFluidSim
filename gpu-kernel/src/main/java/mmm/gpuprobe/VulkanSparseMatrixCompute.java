package mmm.gpuprobe;

import mmm.fluid.SparseMatrixCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkMemoryBarrier;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Double-precision resident active-set Jacobi-PCG on Vulkan with reference matrix products. */
public final class VulkanSparseMatrixCompute implements SparseMatrixCompute, AutoCloseable {
    private static final String SHADER = """
        #version 450
        #extension GL_ARB_gpu_shader_fp64 : require
        layout(local_size_x=128) in;
        layout(binding=0,std430) readonly buffer Starts { int starts[]; };
        layout(binding=1,std430) readonly buffer Columns { int columns[]; };
        layout(binding=2,std430) readonly buffer Values { double values[]; };
        layout(binding=3,std430) readonly buffer Rows { int rows[]; };
        layout(binding=4,std430) readonly buffer Input { double inputVector[]; };
        layout(binding=5,std430) writeonly buffer Output { double outputVector[]; };
        layout(push_constant) uniform Params { int count; } params;
        void main() {
            uint ordinal=gl_GlobalInvocationID.x;
            if(ordinal>=params.count) return;
            int row=rows[ordinal];
            precise double sum=0.0;
            for(int edge=starts[row];edge<starts[row+1];edge++)
                sum += values[edge]*inputVector[columns[edge]];
            outputVector[row]=sum;
        }
        """;
    private final VulkanDeviceContext context;
    private final int singleWorkgroupRows;
    private final Thread owner=Thread.currentThread();
    private FlipGpuResources resources;
    private FlipKernelBindings bindings;
    private VulkanPressureKernel pressureBindings;
    private GpuStepScheduler scheduler;
    private VulkanLiquidQuantityCompute liquidQuantities;
    private int rowCapacity, edgeCapacity;
    private boolean leased, closed;
    private long products, solves, uploadedBytes, downloadedBytes, gpuNanos;

    public VulkanSparseMatrixCompute(int deviceIndex) { this(deviceIndex,4_096); }
    VulkanSparseMatrixCompute(int deviceIndex,int singleWorkgroupRows) {
        this.singleWorkgroupRows=singleWorkgroupRows;
        context=new VulkanDeviceContext(deviceIndex,true,true);
        try {
            ensureCapacity(1,1);
            liquidQuantities=new VulkanLiquidQuantityCompute(context);
        } catch(RuntimeException | LinkageError failure) {
            try {close();}catch(RuntimeException | LinkageError closing){failure.addSuppressed(closing);}
            throw failure;
        }
    }
    @Override public mmm.inventory.LiquidQuantityCompute liquidQuantityCompute() {
        checkOwner();
        if(liquidQuantities==null)liquidQuantities=new VulkanLiquidQuantityCompute(context);
        return liquidQuantities;
    }
    public VulkanLiquidQuantityCompute.Measurements quantityMeasurements() {
        return liquidQuantities==null?new VulkanLiquidQuantityCompute.Measurements(0,0,0,0,0):liquidQuantities.measurements();
    }
    public String deviceName() { return context.deviceName(); }
    public record Measurements(long products,long solves,long uploadedBytes,long downloadedBytes,long gpuNanos) { }
    public Measurements measurements() { return new Measurements(products,solves,uploadedBytes,downloadedBytes,gpuNanos); }
    public record WorkspaceMemory(long deviceStorageBytes,long hostStagingBytes) { }
    /** Logical buffer sizes; driver alignment, pipeline objects and allocator overhead are additional. */
    public WorkspaceMemory workspaceMemory() {
        checkOwner();long storage=0;
        if(resources!=null)for(int i=0;i<resources.size();i++)storage+=resources.buffer(i).size();
        return new WorkspaceMemory(storage,scheduler==null?0:scheduler.regularStagingBytes());
    }

    @Override public Matrix prepare(int size,int[] starts,int[] columns,double[] values,int[] rows,int count) {
        checkOwner();
        if(leased) throw new IllegalStateException("A pressure matrix is already leased");
        if(size<=0 || starts.length<=size || count<0 || count>rows.length) throw new IllegalArgumentException("Invalid CSR dimensions");
        int edges=starts[size];
        if(edges<0 || edges>columns.length || edges>values.length) throw new IllegalArgumentException("Invalid CSR entries");
        ensureCapacity(size,Math.max(1,edges));
        int[] active=Arrays.copyOf(rows,count);
        try(MemoryStack stack=stackPush()) {
            var slot=scheduler.acquire(); var command=scheduler.begin(slot,"pressure-upload");
            ByteBuffer staging=slot.stagingBuffer().bytes(); int offset=0;
            for(int i=0;i<=size;i++)staging.putInt(offset+4*i,starts[i]);
            copy(command,stack,slot.stagingBuffer(),resources.buffer(0),offset,0,4L*(size+1)); offset+=4*(size+1);
            for(int i=0;i<edges;i++)staging.putInt(offset+4*i,columns[i]);
            copy(command,stack,slot.stagingBuffer(),resources.buffer(1),offset,0,4L*edges);offset+=4*edges;
            for(int i=0;i<edges;i++)staging.putDouble(offset+8*i,values[i]);
            copy(command,stack,slot.stagingBuffer(),resources.buffer(2),offset,0,8L*edges);offset+=8*edges;
            for(int i=0;i<count;i++)staging.putInt(offset+4*i,active[i]);
            copy(command,stack,slot.stagingBuffer(),resources.buffer(3),offset,0,4L*count);offset+=4*count;
            barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT);
            scheduler.submitAndWait(stack,slot,"pressure-upload"); uploadedBytes+=offset;
        }
        leased=true;
        return new Matrix() {
            private boolean released;
            @Override public PressureSolution solve(PressureProblem problem) {
                checkOwner();
                if(released)throw new IllegalStateException("Pressure matrix lease is closed");
                if(problem.size()!=size || problem.unknownCount()!=count)throw new IllegalArgumentException("Pressure problem differs from prepared matrix");
                for(int i=0;i<count;i++)if(problem.unknowns()[i]!=active[i])throw new IllegalArgumentException("Pressure unknown order differs");
                return solveResident(problem);
            }
            @Override public void multiply(double[] source,double[] destination) {
                checkOwner();
                if(released)throw new IllegalStateException("Pressure matrix lease is closed");
                if(source.length<size || destination.length<size)throw new IllegalArgumentException("Pressure vector is too short");
                if(count==0)return;
                try(MemoryStack stack=stackPush()) {
                    var slot=scheduler.acquire();var command=scheduler.begin(slot,"pressure-product");
                    var staging=slot.stagingBuffer().bytes();
                    for(int i=0;i<size;i++)staging.putDouble(8*i,source[i]);
                    copy(command,stack,slot.stagingBuffer(),resources.buffer(4),0,0,8L*size);
                    barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT);
                    if(context.hasTimestampQueries()) {
                        vkCmdResetQueryPool(command,slot.timestampQueryPool(),0,2);
                        vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,slot.timestampQueryPool(),0);
                    }
                    bindings.bind(command,stack);
                    vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(count));
                    vkCmdDispatch(command,(count+127)/128,1,1);
                    if(context.hasTimestampQueries())vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,slot.timestampQueryPool(),1);
                    barrier(command,stack,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT);
                    copy(command,stack,resources.buffer(5),slot.stagingBuffer(),0,8L*size,8L*size);
                    barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,
                            VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_HOST_READ_BIT);
                    scheduler.submitAndWait(stack,slot,"pressure-product");
                    for(int row:active)destination[row]=staging.getDouble(8*size+8*row);
                    products++;uploadedBytes+=8L*size;downloadedBytes+=8L*size;
                    if(context.hasTimestampQueries())gpuNanos+=scheduler.readTimestampNanos(slot);
                }
            }
            @Override public void close() { checkOwner(); if(!released){released=true;leased=false;} }
        };
    }
    private PressureSolution solveResident(PressureProblem problem) {
        int size=problem.size();
        try(MemoryStack stack=stackPush()) {
            var slot=scheduler.acquire();var command=scheduler.begin(slot,"pressure-resident-solve");
            var staging=slot.stagingBuffer().bytes();
            for(int row=0;row<size;row++) {
                staging.putDouble(8*row,problem.rhs()[row]);
                staging.putDouble(8*(size+row),problem.lowerBounds()[row]);
                staging.putDouble(8*(2*size+row),problem.initialPressure()[row]);
            }
            staging.putDouble(24*size,problem.tolerance());
            long problemBytes=24L*size+8;
            for(int row=0;row<size;row++)staging.putInt(Math.toIntExact(problemBytes+4L*row),problem.bounded()[row]?1:0);
            copy(command,stack,slot.stagingBuffer(),resources.buffer(8),0,0,problemBytes);
            copy(command,stack,slot.stagingBuffer(),resources.buffer(7),problemBytes,0,4L*size);
            vkCmdFillBuffer(command,resources.buffer(11).handle(),0,4,0);
            barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
            if(context.hasTimestampQueries()) {
                vkCmdResetQueryPool(command,slot.timestampQueryPool(),0,2);
                vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,slot.timestampQueryPool(),0);
            }
            pressureBindings.record(command, problem.size(), problem.unknownCount(), problem.maxIterations());
            if(context.hasTimestampQueries())vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,slot.timestampQueryPool(),1);
            barrier(command,stack,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT);
            copy(command,stack,resources.buffer(5),slot.stagingBuffer(),0,0,8L*size);
            copy(command,stack,resources.buffer(9),slot.stagingBuffer(),0,8L*size,24);
            barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_HOST_READ_BIT);
            scheduler.submitAndWait(stack,slot,"pressure-resident-solve");
            double[] pressure=new double[size];for(int row=0;row<size;row++)pressure[row]=staging.getDouble(8*row);
            int iterations=(int)staging.getDouble(8*size);double rms=staging.getDouble(8*size+8);
            boolean converged=staging.getDouble(8*size+16)!=0;
            solves++;uploadedBytes+=problemBytes+4L*size;downloadedBytes+=8L*size+24;
            if(context.hasTimestampQueries())gpuNanos+=scheduler.readTimestampNanos(slot);
            return new PressureSolution(pressure,iterations,rms,converged);
        }
    }
    private void ensureCapacity(int rows,int edges) {
        if(resources!=null && rows<=rowCapacity && edges<=edgeCapacity)return;
        int grownRows=growCapacity(rowCapacity,rows),grownEdges=growCapacity(edgeCapacity,edges);
        releaseBuffers();rowCapacity=grownRows;edgeCapacity=grownEdges;
        rows=grownRows;edges=grownEdges;
        try {
            resources=new FlipGpuResources(context);
            resources.addDeviceStorage(4L*(rows+1));resources.addDeviceStorage(4L*edges);
            resources.addDeviceStorage(8L*edges);resources.addDeviceStorage(4L*rows);
            resources.addDeviceStorage(8L*rows);resources.addDeviceStorage(8L*rows);
            resources.addDeviceStorage(40L*rows);
            resources.addDeviceStorage(8L*rows);
            resources.addDeviceStorage(8L*(3L*rows+1));resources.addDeviceStorage(128);
            resources.addDeviceStorage(32L*((rows+255)/256));
            resources.addDeviceStorage(4);
            if(bindings==null)bindings=new FlipKernelBindings(context,resources,SHADER,4);
            else bindings.rebind(resources);
            if(pressureBindings==null)pressureBindings=new VulkanPressureKernel(context,singleWorkgroupRows);
            pressureBindings.rebind(resources);
            scheduler=new GpuStepScheduler(context,resources,Math.max(28L*rows+24,8L*rows+4+12L*edges));
        } catch(RuntimeException | LinkageError failure) {
            try {releaseWorkspace();}catch(RuntimeException | LinkageError closing){failure.addSuppressed(closing);}
            throw failure;
        }
    }
    private static int growCapacity(int current,int required) {
        long capacity=Math.max(64,current);
        while(capacity<required)capacity=Math.min(Integer.MAX_VALUE,capacity+Math.max(1,capacity/2));
        return (int)capacity;
    }
    private static void copy(VkCommandBuffer command,MemoryStack stack,GpuBuffer from,GpuBuffer to,long source,long target,long bytes) {
        if(bytes==0)return;
        var range=VkBufferCopy.calloc(1,stack);range.get(0).srcOffset(source).dstOffset(target).size(bytes);
        vkCmdCopyBuffer(command,from.handle(),to.handle(),range);
    }
    private static void barrier(VkCommandBuffer command,MemoryStack stack,int from,int to,int source,int target) {
        var memory=VkMemoryBarrier.calloc(1,stack);memory.get(0).sType$Default().srcAccessMask(source).dstAccessMask(target);
        vkCmdPipelineBarrier(command,from,to,0,memory,null,null);
    }
    private void checkOwner() {
        if(closed)throw new IllegalStateException("Vulkan pressure compute is closed");
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Vulkan pressure compute belongs to another thread");
    }
    private void releaseWorkspace() {
        var oldScheduler=scheduler;var oldBindings=bindings;var oldPressureBindings=pressureBindings;var oldResources=resources;
        scheduler=null;bindings=null;pressureBindings=null;resources=null;
        Throwable failure=closeFailure(null,oldScheduler);
        failure=closeFailure(failure,oldBindings);failure=closeFailure(failure,oldPressureBindings);
        failure=closeFailure(failure,oldResources);rethrow(failure);
    }
    private void releaseBuffers() {
        var oldScheduler=scheduler;var oldResources=resources;
        scheduler=null;resources=null;
        Throwable failure=closeFailure(null,oldScheduler);
        failure=closeFailure(failure,oldResources);rethrow(failure);
    }
    private static Throwable closeFailure(Throwable failure,AutoCloseable resource) {
        if(resource==null)return failure;
        try {resource.close();}
        catch(Exception | LinkageError closing) {if(failure==null)failure=closing;else failure.addSuppressed(closing);}
        return failure;
    }
    private static void rethrow(Throwable failure) {
        if(failure instanceof RuntimeException runtime)throw runtime;
        if(failure instanceof LinkageError linkage)throw linkage;
        if(failure!=null)throw new IllegalStateException("Vulkan resource release failed",failure);
    }
    @Override public void close() {
        if(closed)return;checkOwner();
        if(leased)throw new IllegalStateException("Close the pressure matrix lease before its device");
        closed=true;Throwable failure=null;
        try {releaseWorkspace();}catch(RuntimeException | LinkageError closing){failure=closing;}
        failure=closeFailure(failure,liquidQuantities);liquidQuantities=null;
        failure=closeFailure(failure,context);rethrow(failure);
    }
}
