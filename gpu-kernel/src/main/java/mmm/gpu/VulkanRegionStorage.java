package mmm.gpu;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Device-local generations shared by captures and private compute candidates. */
final class VulkanRegionStorage implements AutoCloseable {
    record Metrics(long submissions, long uploadBytes, long readbackBytes, long copiedBytes,
                   long gpuNanos, long recordingNanos, long waitingNanos, int allocatedGenerations, long deviceBytes, long stagingBytes) { }

    @FunctionalInterface
    interface ComputeCommands {
        void record(VkCommandBuffer command, GpuBuffer source, GpuBuffer candidate,
                    GpuBuffer errors, GpuBuffer staging, MemoryStack stack);
    }

    private final VulkanDeviceContext context;
    private final int stagingBytes;
    private final FlipGpuResources resources;
    private final List<Slot> slots = new ArrayList<>();
    private GpuStepScheduler scheduler;
    private GpuBuffer errors;
    private Version committed;
    private boolean closed;
    private long submissions, uploadBytes, readbackBytes, copiedBytes, gpuNanos, recordingNanos, waitingNanos;

    VulkanRegionStorage(VulkanDeviceContext context, long bytes, int stagingBytes) {
        if (bytes < 4 || (bytes & 3) != 0 || stagingBytes < 4 || (stagingBytes & 3) != 0)
            throw new IllegalArgumentException("Device storage and transfer sizes must be positive words");
        this.context = context;
        this.stagingBytes = stagingBytes;
        resources = new FlipGpuResources(context);
        try {
            slots.add(new Slot(context, bytes));
            slots.add(new Slot(context, bytes));
            errors = resources.addDeviceStorage(4);
            scheduler = new GpuStepScheduler(context, resources, stagingBytes);
        } catch (RuntimeException | LinkageError failure) {
            try { close(); } catch (RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    synchronized Metrics metrics() {
        long deviceBytes = 4;
        for (Slot slot : slots) deviceBytes = Math.addExact(deviceBytes, slot.buffer.size());
        return new Metrics(submissions, uploadBytes, readbackBytes, copiedBytes, gpuNanos, recordingNanos, waitingNanos,
                slots.size(), deviceBytes, (long) stagingBytes * GpuStepScheduler.RING_SIZE);
    }

    /** Imports a complete contiguous checkpoint through the same sparse cold-import path. */
    synchronized void initialize(ByteBuffer input, long generation) {
        initialize(input.remaining(),generation,target->target.put(0,input));
    }

    /** Zeroes device storage, uploads authoritative ranges, then publishes the complete generation. */
    synchronized void initialize(long bytes,long generation,java.util.function.Consumer<Import> initialize) {
        requireOpen();requireStorageSize(bytes);
        if(generation<0)throw new IllegalArgumentException("Region generation must be nonnegative");
        Slot target=reserve(bytes);
        try {
            try(var stack=stackPush()) {
                var submission=scheduler.acquire();var command=scheduler.begin(submission,"region-import-clear");
                barrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                vkCmdFillBuffer(command,target.buffer.handle(),0,bytes,0);
                scheduler.submitAndWait(stack,submission,"region-import-clear");submissions++;
            }
            var writer=new Import(target,bytes);
            try{initialize.accept(writer);}finally{writer.active=false;}
            if(committed!=null)release(committed.slot);
            committed=new Version(target,generation,bytes);
        } catch(RuntimeException|Error failure){release(target);throw failure;}
    }

    /** Bounded staging uploads retain long device offsets; no host arena mirror is allocated. */
    final class Import {
        private final Slot target;private final long bytes;private boolean active=true;
        private Import(Slot target,long bytes){this.target=target;this.bytes=bytes;}
        void put(long offset,ByteBuffer input) {
            if(!active)throw new IllegalStateException("Region import has completed");
            int length=input.remaining();
            if(offset<0||offset>bytes-length||((offset|length)&3)!=0)
                throw new IllegalArgumentException("Region import range differs from storage");
            for(int position=0;position<length;) {
                int count=Math.min(stagingBytes,length-position);
                try(var stack=stackPush()) {
                    var submission=scheduler.acquire();
                    submission.stagingBuffer().bytes().put(0,input,input.position()+position,count);
                    var command=scheduler.begin(submission,"region-import");
                    barrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                    copy(command,submission.stagingBuffer(),0,target.buffer,offset+position,count,stack);
                    scheduler.submitAndWait(stack,submission,"region-import");submissions++;uploadBytes+=count;
                }
                position+=count;
            }
        }
        void put(VulkanLiquidStateLayout.Column column,int[] values){write(column.offset(),values.length,4,(b,i)->b.putInt(values[i]));}
        void put(VulkanLiquidStateLayout.Column column,long[] values){write(column.offset(),values.length,8,(b,i)->b.putLong(values[i]));}
        void put(VulkanLiquidStateLayout.Column column,double[] values){write(column.offset(),values.length,8,(b,i)->b.putDouble(values[i]));}
        private void write(long offset,int count,int width,java.util.function.ObjIntConsumer<ByteBuffer> value) {
            var buffer=ByteBuffer.allocate(Math.min(stagingBytes,Math.max(width,Math.min(count,stagingBytes/width)*width))).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for(int begin=0;begin<count;) {
                int end=begin+Math.min(buffer.capacity()/width,count-begin);buffer.clear();
                for(int i=begin;i<end;i++)value.accept(buffer,i);
                buffer.flip();put(offset+(long)begin*width,buffer);begin=end;
            }
        }
    }

    synchronized Capture capture() {
        requireOpen();
        if (committed == null) throw new IllegalStateException("Region storage is not initialized");
        return new Capture(committed);
    }

    /** Reserves a same-layout candidate; its first submission copies the complete source generation. */
    synchronized Candidate prepare(Capture source) {
        requireCapture(source);
        return prepareCandidate(source, source.version.logicalBytes, true);
    }

    /** Reserves a new layout without copying source bytes. The first callback must initialize every
     * authoritative candidate column, recording any device migration and its required barriers.
     */
    synchronized Candidate prepareReallocated(Capture source, long candidateBytes) {
        return prepareCandidate(source, candidateBytes, false);
    }

    /** Prepare derived data for an initial import without changing its generation. */
    synchronized Candidate prepareInitial(Capture source, long candidateBytes, boolean copySource) {
        requireCapture(source);
        if (source.version != committed || source.origin != committed || committed.generation != 0)
            throw new IllegalStateException("Initial geometry requires the initial committed generation");
        requireStorageSize(candidateBytes);
        Slot target = reserve(candidateBytes);
        source.version.slot.references++;
        return new Candidate(source.version, source.origin, target, 0, candidateBytes, copySource);
    }

    private Candidate prepareCandidate(Capture source, long candidateBytes, boolean copySource) {
        requireCapture(source);
        requireStorageSize(candidateBytes);
        long next = Math.addExact(source.origin.generation, 1);
        Slot target = reserve(candidateBytes);
        source.version.slot.references++;
        return new Candidate(source.version, source.origin, target, next, candidateBytes, copySource);
    }

    /** Explicit range read for queries/checkpoints; normal candidate submission reads one status word. */
    synchronized void read(Capture capture, long offset, ByteBuffer output) {
        requireCapture(capture);
        int length = output.remaining();
        if (offset < 0 || offset > capture.version.logicalBytes - length || ((offset | length) & 3) != 0)
            throw new IllegalArgumentException("Region read range differs from its storage");
        while (output.hasRemaining()) {
            int chunk = Math.min(stagingBytes, output.remaining());
            try (MemoryStack stack = stackPush()) {
                var submission = scheduler.acquire();
                var command = scheduler.begin(submission, "region-export");
                barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT, stack);
                copy(command, capture.version.slot.buffer, offset, submission.stagingBuffer(), 0, chunk, stack);
                barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT, stack);
                scheduler.submitAndWait(stack, submission, "region-export");
                output.put(submission.stagingBuffer().bytes().slice(0, chunk));
                submissions++;
                readbackBytes += chunk;
            }
            offset += chunk;
        }
    }

    private Slot reserve(long bytes) {
        Slot best = null;
        int expandable = -1;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.references != 0) continue;
            if (slot.buffer.size() >= bytes) {
                if (best == null || slot.buffer.size() < best.buffer.size()) best = slot;
            } else if (expandable < 0 || slot.buffer.size() > slots.get(expandable).buffer.size()) {
                expandable = i;
            }
        }
        if (best != null) { best.references = 1; return best; }
        Slot target = new Slot(context, bytes);
        target.references = 1;
        if (expandable < 0) slots.add(target);
        else slots.set(expandable, target).resources.close();
        return target;
    }

    private static void requireStorageSize(long bytes) {
        if (bytes < 4 || (bytes & 3) != 0)
            throw new IllegalArgumentException("Device storage size must be positive words");
    }

    private static void release(Slot slot) { slot.references--; }
    private void requireOpen() { if (closed) throw new IllegalStateException("Region storage is closed"); }
    private void requireCapture(Capture capture) {
        requireOpen();
        if (capture.owner() != this || capture.released)
            throw new IllegalStateException("Region capture is closed or belongs to another storage");
    }

    final class Capture implements AutoCloseable {
        private final Version version;
        private final Version origin;
        private boolean released;
        private Capture(Version version) { this(version,version); }
        private Capture(Version version,Version origin) { this.version = version;this.origin=origin;version.slot.references++; }
        private VulkanRegionStorage owner() { return VulkanRegionStorage.this; }
        long generation() { synchronized (VulkanRegionStorage.this) { requireCapture(this); return version.generation; } }
        long logicalBytes() { synchronized (VulkanRegionStorage.this) { requireCapture(this); return version.logicalBytes; } }
        Capture retain(){synchronized(VulkanRegionStorage.this){requireCapture(this);return sameCommitted()?new Capture(committed):new Capture(version,origin);}}
        GpuBuffer buffer(){synchronized(VulkanRegionStorage.this){requireCapture(this);return version.slot.buffer;}}
        boolean isCurrent(){synchronized(VulkanRegionStorage.this){requireCapture(this);return sameCommitted();}}
        private boolean sameCommitted(){return committed!=null&&committed.slot==version.slot&&committed.generation==version.generation&&committed.logicalBytes==version.logicalBytes;}
        @Override public void close() {
            synchronized (VulkanRegionStorage.this) {
                if (!released) { release(version.slot); released = true; }
            }
        }
    }

    final class Candidate implements AutoCloseable {
        private final Version base;
        private final Version origin;
        private final Slot slot;
        private final long generation, logicalBytes;
        private final boolean copySource;
        private boolean attempted, submitted, finished, released;
        private int error;
        private Candidate(Version base,Version origin, Slot slot, long generation, long logicalBytes, boolean copySource) {
            this.base = base;this.origin=origin;this.slot = slot; this.generation = generation;
            this.logicalBytes = logicalBytes; this.copySource = copySource;
        }

        long logicalBytes() {
            synchronized (VulkanRegionStorage.this) {
                requireOpen();
                if (released) throw new IllegalStateException("Candidate is closed or committed");
                return logicalBytes;
            }
        }

        record BatchResult(int error, ByteBuffer feedback) { }

        /** Writes a complete replacement into this private slot without publishing it. */
        void importState(java.util.function.Consumer<Import> initialize) {
            synchronized (VulkanRegionStorage.this) {
                requireOpen();
                if (released || attempted) throw new IllegalStateException("Candidate cannot import state");
                attempted = true;
                try (var stack = stackPush()) {
                    var submission = scheduler.acquire();
                    var command = scheduler.begin(submission, "region-candidate-import-clear");
                    barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, stack);
                    vkCmdFillBuffer(command, slot.buffer.handle(), 0, logicalBytes, 0);
                    scheduler.submitAndWait(stack, submission, "region-candidate-import-clear");
                    submissions++;
                }
                var writer = new Import(slot, logicalBytes);
                try { initialize.accept(writer); } finally { writer.active = false; }
                submitted = true;
                error = 0;
            }
        }

        /** Records initialization, all compute stages and status download in one submission. */
        int execute(long uploadedBytes, ComputeCommands commands) {
            return submit(uploadedBytes, commands, true, true, 0, 0).error();
        }

        BatchResult beginBatch(ComputeCommands commands, long feedbackOffset, int feedbackBytes) {
            return beginBatch(0,commands,feedbackOffset,feedbackBytes);
        }
        BatchResult beginBatch(long uploadedBytes,ComputeCommands commands,long feedbackOffset,int feedbackBytes) {
            return submit(uploadedBytes,commands,true,false,feedbackOffset,feedbackBytes);
        }

        BatchResult continueBatch(ComputeCommands commands, long feedbackOffset, int feedbackBytes) {
            return submit(0, commands, false, false, feedbackOffset, feedbackBytes);
        }

        int finishBatch(ComputeCommands commands) { return finishBatch(0,commands); }
        BatchResult finishBatch(ComputeCommands commands,long feedbackOffset,int feedbackBytes) {
            return submit(0,commands,false,true,feedbackOffset,feedbackBytes);
        }
        int finishBatch(long uploadedBytes,ComputeCommands commands) {
            return submit(uploadedBytes,commands,false,true,0,0).error();
        }

        /** Batches retain the same private generation; only a completed final batch can be adopted. */
        private BatchResult submit(long uploadedBytes, ComputeCommands commands, boolean first, boolean last,
                                   long feedbackOffset, int feedbackBytes) {
            synchronized (VulkanRegionStorage.this) {
                requireOpen();
                if (released || finished || (first ? attempted : !submitted || error != 0))
                    throw new IllegalStateException("Candidate cannot execute this batch");
                if (feedbackBytes < 0 || feedbackBytes > stagingBytes - 4 || feedbackOffset < 0
                        || feedbackOffset > logicalBytes - feedbackBytes || ((feedbackOffset | feedbackBytes) & 3) != 0)
                    throw new IllegalArgumentException("Candidate feedback range differs from storage");
                attempted = true; submitted = false;
                try (MemoryStack stack = stackPush()) {
                    var submission = scheduler.acquire();
                    long recordingStart=System.nanoTime();
                    var command = scheduler.begin(submission, "region-step");
                    if (context.hasTimestampQueries()) {
                        vkCmdResetQueryPool(command, submission.timestampQueryPool(), 0, 2);
                        vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, submission.timestampQueryPool(), 0);
                    }
                    barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, stack);
                    if (first && copySource)
                        copy(command, base.slot.buffer, 0, slot.buffer, 0, logicalBytes, stack);
                    // Another candidate may use the shared status word between this candidate's batches.
                    vkCmdFillBuffer(command, errors.handle(), 0, 4, 0);
                    barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
                    commands.record(command, base.slot.buffer, slot.buffer, errors, submission.stagingBuffer(), stack);
                    barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, stack);
                    copy(command, errors, 0, submission.stagingBuffer(), 0, 4, stack);
                    if (feedbackBytes != 0)
                        copy(command, slot.buffer, feedbackOffset, submission.stagingBuffer(), 4, feedbackBytes, stack);
                    barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT, stack);
                    if (context.hasTimestampQueries())
                        vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, submission.timestampQueryPool(), 1);
                    long waitingStart=System.nanoTime();recordingNanos+=waitingStart-recordingStart;
                    scheduler.submitAndWait(stack, submission, "region-step");
                    waitingNanos+=System.nanoTime()-waitingStart;
                    error = submission.stagingBuffer().bytes().getInt(0);
                    var feedback = ByteBuffer.allocate(feedbackBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    feedback.put(submission.stagingBuffer().bytes().slice(4, feedbackBytes)).flip();
                    submissions++;
                    uploadBytes += uploadedBytes;
                    readbackBytes += 4L + feedbackBytes;
                    if (first && copySource) copiedBytes += logicalBytes;
                    if (context.hasTimestampQueries()) gpuNanos += scheduler.readTimestampNanos(submission);
                    submitted = true; finished = last;
                    return new BatchResult(error, feedback);
                }
            }
        }

        /** The repository chooses when to adopt; a stale or failed result cannot replace current state. */
        long commit() {
            synchronized (VulkanRegionStorage.this) {
                requireOpen();
                if (released || !submitted || !finished || error != 0 || committed != base)
                    throw new IllegalStateException("Candidate cannot replace this committed region generation");
                return adopt();
            }
        }

        /** Pins a successful private substep as input to the next substep without publishing it. */
        Capture captureCompleted(){
            synchronized(VulkanRegionStorage.this){
                requireOpen();if(released||!submitted||!finished||error!=0)throw new IllegalStateException("Candidate substep is incomplete");
                return new Capture(new Version(slot,generation,logicalBytes),origin);
            }
        }

        /** Publishes an entire private substep chain once, against the original request capture. */
        long commit(Capture original){
            synchronized(VulkanRegionStorage.this){
                requireCapture(original);
                if(released||!submitted||!finished||error!=0||original.version!=origin||original.origin!=origin||committed!=origin)
                    throw new IllegalStateException("Private substeps cannot replace this original region generation");
                return adopt();
            }
        }
        private long adopt(){
            release(committed.slot);committed=new Version(slot,generation,logicalBytes);release(base.slot);released=true;return generation;
        }

        /** Small explicit diagnostics may inspect a completed, uncommitted candidate. */
        void read(long offset, ByteBuffer output) {
            synchronized (VulkanRegionStorage.this) {
                requireOpen();
                if (released || !submitted) throw new IllegalStateException("Candidate is not available for inspection");
                try (var capture = new Capture(new Version(slot, generation, logicalBytes))) {
                    VulkanRegionStorage.this.read(capture, offset, output);
                }
            }
        }

        /** Packs sparse uint32 capacity requirements into one small diagnostic readback. */
        void readWords(long[] offsets,ByteBuffer output) {
            synchronized(VulkanRegionStorage.this) {
                requireOpen();int bytes=Math.multiplyExact(offsets.length,4);
                if(released||!submitted||output.remaining()!=bytes||bytes>stagingBytes)
                    throw new IllegalArgumentException("Candidate word read differs from its storage");
                for(long offset:offsets)if(offset<0||offset>logicalBytes-4||(offset&3)!=0)
                    throw new IllegalArgumentException("Candidate word offset exceeds its storage");
                try(var stack=stackPush()) {
                    var submission=scheduler.acquire();var command=scheduler.begin(submission,"region-capacity-requirements");
                    barrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT,stack);
                    for(int word=0;word<offsets.length;word++)copy(command,slot.buffer,offsets[word],submission.stagingBuffer(),4L*word,4,stack);
                    barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_HOST_READ_BIT,stack);
                    scheduler.submitAndWait(stack,submission,"region-capacity-requirements");
                    output.put(submission.stagingBuffer().bytes().slice(0,bytes));submissions++;readbackBytes+=bytes;
                }
            }
        }

        @Override public void close() {
            synchronized (VulkanRegionStorage.this) {
                if (!released) { release(slot); release(base.slot); released = true; }
            }
        }
    }

    static void copy(VkCommandBuffer command, GpuBuffer source, long sourceOffset,
                     GpuBuffer target, long targetOffset, long bytes, MemoryStack stack) {
        var copy = VkBufferCopy.calloc(1, stack);
        copy.get(0).srcOffset(sourceOffset).dstOffset(targetOffset).size(bytes);
        vkCmdCopyBuffer(command, source.handle(), target.handle(), copy);
    }

    static void barrier(VkCommandBuffer command, int sourceStage, int sourceAccess,
                        int targetStage, int targetAccess, MemoryStack stack) {
        var barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().srcAccessMask(sourceAccess).dstAccessMask(targetAccess);
        vkCmdPipelineBarrier(command, sourceStage, targetStage, 0, barrier, null, null);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { if (scheduler != null) scheduler.close(); }
        finally {
            try { resources.close(); }
            finally { for (Slot slot : slots) slot.resources.close(); }
        }
    }

    private static final class Slot {
        final FlipGpuResources resources;
        final GpuBuffer buffer;
        int references;
        Slot(VulkanDeviceContext context, long bytes) {
            resources = new FlipGpuResources(context);
            try { buffer = resources.addDeviceStorage(bytes); }
            catch (RuntimeException | LinkageError failure) {
                try { resources.close(); } catch (RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
                throw failure;
            }
        }
    }
    private record Version(Slot slot, long generation, long logicalBytes) { }
}
