package mmm.gpuprobe;

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
                   long gpuNanos, int allocatedGenerations, long deviceBytes, long stagingBytes) { }

    @FunctionalInterface
    interface ComputeCommands {
        void record(VkCommandBuffer command, GpuBuffer source, GpuBuffer candidate,
                    GpuBuffer errors, GpuBuffer staging, MemoryStack stack);
    }

    private final VulkanDeviceContext context;
    private final long bytes;
    private final int stagingBytes;
    private final FlipGpuResources resources;
    private final List<Slot> slots = new ArrayList<>();
    private GpuStepScheduler scheduler;
    private GpuBuffer errors;
    private Version committed;
    private boolean closed;
    private long submissions, uploadBytes, readbackBytes, copiedBytes, gpuNanos;

    VulkanRegionStorage(VulkanDeviceContext context, long bytes, int stagingBytes) {
        if (bytes < 4 || (bytes & 3) != 0 || stagingBytes < 4 || (stagingBytes & 3) != 0)
            throw new IllegalArgumentException("Device storage and transfer sizes must be positive words");
        this.context = context;
        this.bytes = bytes;
        this.stagingBytes = stagingBytes;
        resources = new FlipGpuResources(context);
        try {
            slots.add(new Slot(resources.addDeviceStorage(bytes)));
            slots.add(new Slot(resources.addDeviceStorage(bytes)));
            errors = resources.addDeviceStorage(4);
            scheduler = new GpuStepScheduler(context, resources, stagingBytes);
        } catch (RuntimeException | LinkageError failure) {
            try { close(); } catch (RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    synchronized Metrics metrics() {
        return new Metrics(submissions, uploadBytes, readbackBytes, copiedBytes, gpuNanos,
                slots.size(), bytes * slots.size() + 4, (long) stagingBytes * GpuStepScheduler.RING_SIZE);
    }

    /** Imports once at initialization or explicit restore; pinned old generations remain valid. */
    synchronized void initialize(ByteBuffer input, long generation) {
        requireOpen();
        if (input.remaining() != bytes || generation < 0)
            throw new IllegalArgumentException("Region import layout or generation differs");
        Slot target = reserve();
        try {
            for (long offset = 0; offset < bytes;) {
                int length = (int) Math.min(stagingBytes, bytes - offset);
                try (MemoryStack stack = stackPush()) {
                    var submission = scheduler.acquire();
                    submission.stagingBuffer().bytes().put(0, input, input.position() + (int) offset, length);
                    var command = scheduler.begin(submission, "region-import");
                    barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, stack);
                    copy(command, submission.stagingBuffer(), 0, target.buffer, offset, length, stack);
                    scheduler.submitAndWait(stack, submission, "region-import");
                    submissions++;
                    uploadBytes += length;
                }
                offset += length;
            }
            if (committed != null) release(committed.slot);
            committed = new Version(target, generation);
        } catch (RuntimeException | LinkageError failure) {
            release(target);
            throw failure;
        }
    }

    synchronized Capture capture() {
        requireOpen();
        if (committed == null) throw new IllegalStateException("Region storage is not initialized");
        return new Capture(committed);
    }

    /** Reserves a private slot. No host copy or device submission occurs here. */
    synchronized Candidate prepare(Capture source) {
        requireCapture(source);
        long next = Math.addExact(source.version.generation, 1);
        Slot target = reserve();
        source.version.slot.references++;
        return new Candidate(source.version, target, next);
    }

    /** Explicit range read for queries/checkpoints; normal candidate submission reads one status word. */
    synchronized void read(Capture capture, long offset, ByteBuffer output) {
        requireCapture(capture);
        int length = output.remaining();
        if (offset < 0 || offset > bytes - length || ((offset | length) & 3) != 0)
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

    private Slot reserve() {
        for (Slot slot : slots) if (slot.references == 0) { slot.references = 1; return slot; }
        Slot slot = new Slot(resources.addDeviceStorage(bytes));
        slot.references = 1;
        slots.add(slot);
        return slot;
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
        private boolean released;
        private Capture(Version version) { this.version = version; version.slot.references++; }
        private VulkanRegionStorage owner() { return VulkanRegionStorage.this; }
        long generation() { synchronized (VulkanRegionStorage.this) { requireCapture(this); return version.generation; } }
        @Override public void close() {
            synchronized (VulkanRegionStorage.this) {
                if (!released) { release(version.slot); released = true; }
            }
        }
    }

    final class Candidate implements AutoCloseable {
        private final Version base;
        private final Slot slot;
        private final long generation;
        private boolean attempted, submitted, released;
        private int error;
        private Candidate(Version base, Slot slot, long generation) {
            this.base = base; this.slot = slot; this.generation = generation;
        }

        /** Records device copy, all compute stages and status download in one submission. */
        int execute(long uploadedBytes, ComputeCommands commands) {
            synchronized (VulkanRegionStorage.this) {
                requireOpen();
                if (released || attempted) throw new IllegalStateException("Candidate has already been used");
                attempted = true;
                try (MemoryStack stack = stackPush()) {
                    var submission = scheduler.acquire();
                    var command = scheduler.begin(submission, "region-step");
                    if (context.hasTimestampQueries()) {
                        vkCmdResetQueryPool(command, submission.timestampQueryPool(), 0, 2);
                        vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, submission.timestampQueryPool(), 0);
                    }
                    barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, stack);
                    copy(command, base.slot.buffer, 0, slot.buffer, 0, bytes, stack);
                    vkCmdFillBuffer(command, errors.handle(), 0, 4, 0);
                    barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
                    commands.record(command, base.slot.buffer, slot.buffer, errors, submission.stagingBuffer(), stack);
                    barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, stack);
                    copy(command, errors, 0, submission.stagingBuffer(), 0, 4, stack);
                    barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT, stack);
                    if (context.hasTimestampQueries())
                        vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, submission.timestampQueryPool(), 1);
                    scheduler.submitAndWait(stack, submission, "region-step");
                    error = submission.stagingBuffer().bytes().getInt(0);
                    submissions++;
                    uploadBytes += uploadedBytes;
                    readbackBytes += 4;
                    copiedBytes += bytes;
                    if (context.hasTimestampQueries()) gpuNanos += scheduler.readTimestampNanos(submission);
                    submitted = true;
                    return error;
                }
            }
        }

        /** The repository chooses when to adopt; a stale or failed result cannot replace current state. */
        long commit() {
            synchronized (VulkanRegionStorage.this) {
                requireOpen();
                if (released || !submitted || error != 0 || committed != base)
                    throw new IllegalStateException("Candidate cannot replace this committed region generation");
                release(committed.slot);
                committed = new Version(slot, generation);
                release(base.slot);
                released = true;
                return generation;
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
        finally { resources.close(); }
    }

    private static final class Slot {
        final GpuBuffer buffer;
        int references;
        Slot(GpuBuffer buffer) { this.buffer = buffer; }
    }
    private record Version(Slot slot, long generation) { }
}
