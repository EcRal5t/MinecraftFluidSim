package mmm.gpuprobe;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Owns bulk host transfer memory and all synchronous GPU readback operations. */
final class GpuReadback implements AutoCloseable {
	private final VulkanDeviceContext context;
	private final FlipGpuResources resources;
	private final GpuStepScheduler scheduler;
	private final VulkanFlipPipeline.MemoryMode memoryMode;
	private GpuBuffer transferBuffer;

	GpuReadback(VulkanDeviceContext context, FlipGpuResources resources, GpuStepScheduler scheduler,
	            VulkanFlipPipeline.MemoryMode memoryMode) {
		this.context = context;
		this.resources = resources;
		this.scheduler = scheduler;
		this.memoryMode = memoryMode;
		if (memoryMode == VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING) {
			transferBuffer = resources.createHostTransferBuffer(resources.maximumBufferBytes());
		}
	}

	GpuBuffer transferBuffer() {
		if (transferBuffer == null) throw new IllegalStateException("Host transfer buffer is unavailable");
		return transferBuffer;
	}

	ByteBuffer readableBuffer(int index) { return readableBuffer(index, resources.buffer(index).size()); }

	ByteBuffer readableBuffer(int index, long byteCount) {
		scheduler.awaitSubmittedWork();
		GpuBuffer source = resources.buffer(index);
		if (byteCount < 0L || byteCount > source.size()) {
			throw new IllegalArgumentException("Readback byte range exceeds source buffer");
		}
		if (memoryMode == VulkanFlipPipeline.MemoryMode.HOST_VISIBLE) return source.bytes(byteCount);
		return copyDeviceBufferToHost(source, byteCount);
	}

	ByteBuffer copiedBuffer(int index) { return copiedBuffer(index, resources.buffer(index).size()); }

	ByteBuffer copiedBuffer(int index, long byteCount) {
		GpuBuffer source = resources.buffer(index);
		if (byteCount < 0L || byteCount > source.size()) {
			throw new IllegalArgumentException("Copy byte range exceeds source buffer");
		}
		ByteBuffer readable = readableBuffer(index, byteCount).duplicate().order(ByteOrder.nativeOrder());
		readable.position(0).limit(Math.toIntExact(byteCount));
		ByteBuffer copy = ByteBuffer.allocate(Math.toIntExact(byteCount)).order(ByteOrder.nativeOrder());
		copy.put(readable).flip();
		return copy;
	}

	Measurements measure(int snapshotIndex) {
		long fullStarted = System.nanoTime();
		long fullChecksum = 0L;
		long fullBytes = 0L;
		for (int i = 0; i < resources.size(); i++) {
			GpuBuffer current = resources.buffer(i);
			fullChecksum ^= Long.rotateLeft(checksum(readableBuffer(i), current.size()), i * 7);
			fullBytes += current.size();
		}
		long fullNanos = System.nanoTime() - fullStarted;
		long compactStarted = System.nanoTime();
		long compactChecksum = checksum(readableBuffer(snapshotIndex), resources.buffer(snapshotIndex).size());
		long compactNanos = System.nanoTime() - compactStarted;
		return new Measurements(fullBytes, resources.buffer(snapshotIndex).size(), fullNanos, compactNanos,
				fullChecksum, compactChecksum);
	}

	static void recordCopy(VkCommandBuffer commandBuffer, MemoryStack stack, GpuBuffer source,
	                       GpuBuffer destination, long bytes) {
		VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
		region.get(0).srcOffset(0).dstOffset(0).size(bytes);
		vkCmdCopyBuffer(commandBuffer, source.handle(), destination.handle(), region);
	}

	private ByteBuffer copyDeviceBufferToHost(GpuBuffer source, long byteCount) {
		GpuStepScheduler.SubmissionSlot slot = scheduler.acquire();
		GpuBuffer transfer = transferBuffer();
		if (byteCount > source.size() || byteCount > transfer.size()) {
			throw new IllegalArgumentException("Bulk transfer staging buffer too small");
		}
		try (MemoryStack stack = stackPush()) {
			VkCommandBuffer commandBuffer = scheduler.begin(slot, "readback");
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
			barrier.get(0).sType$Default()
					.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
					.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
			vkCmdPipelineBarrier(commandBuffer,
					VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
					VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
			recordCopy(commandBuffer, stack, source, transfer, byteCount);
			barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
			vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
					0, barrier, null, null);
			scheduler.submitAndWait(stack, slot, "readback");
		}
		return transfer.bytes(byteCount);
	}

	private static long checksum(ByteBuffer bytes, long byteCount) {
		long checksum = 0xcbf29ce484222325L;
		for (int i = 0; i < byteCount; i += 4) {
			checksum = (checksum ^ Integer.toUnsignedLong(bytes.getInt(i))) * 0x100000001b3L;
		}
		return checksum;
	}

	@Override
	public void close() {
		if (transferBuffer != null) {
			resources.destroy(transferBuffer);
			transferBuffer = null;
		}
	}

	record Measurements(long fullBytes, long compactBytes, long fullNanos, long compactNanos,
	                    long fullChecksum, long compactChecksum) { }
}
