package cc.ecisr.mmm.gpuprobe;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/** Owns the FLIP storage buffers and centralizes Vulkan memory allocation. */
final class FlipGpuResources implements AutoCloseable {
	static final int BUFFER_COUNT = 27;

	private final VulkanDeviceContext context;
	private final List<GpuBuffer> buffers = new ArrayList<>(BUFFER_COUNT);

	FlipGpuResources(VulkanDeviceContext context, VulkanFlipPipeline.Config config,
	                 VulkanFlipPipeline.MemoryMode memoryMode, int multigridCellCount) {
		this.context = context;
		try {
			long particleBytes = Math.multiplyExact((long) config.maxParticles(), 4L * Float.BYTES);
			addStorageBuffer(particleBytes, memoryMode); addStorageBuffer(particleBytes, memoryMode);
			addStorageBuffer((long) config.cellCount() * Float.BYTES, memoryMode);
			addStorageBuffer((long) config.cellCount() * Integer.BYTES, memoryMode);
			addStorageBuffer((long) config.uCount() * Float.BYTES, memoryMode);
			addStorageBuffer((long) config.uCount() * Float.BYTES, memoryMode);
			addStorageBuffer((long) config.uCount() * Float.BYTES, memoryMode);
			addStorageBuffer((long) config.vCount() * Float.BYTES, memoryMode);
			addStorageBuffer((long) config.vCount() * Float.BYTES, memoryMode);
			addStorageBuffer((long) config.vCount() * Float.BYTES, memoryMode);
			addStorageBuffer((long) config.wCount() * Float.BYTES, memoryMode);
			addStorageBuffer((long) config.wCount() * Float.BYTES, memoryMode);
			addStorageBuffer((long) config.wCount() * Float.BYTES, memoryMode);
			for (int i = 0; i < 3; i++) addStorageBuffer((long) config.cellCount() * Float.BYTES, memoryMode);
			addStorageBuffer((long) config.cellCount() * Integer.BYTES, memoryMode);
			addStorageBuffer((long) config.cellCount() * Integer.BYTES, memoryMode);
			addStorageBuffer(16L * Integer.BYTES, memoryMode);
			for (int i = 0; i < 8; i++) {
				long bytes = (long) multigridCellCount * (i == 1 ? Integer.BYTES : Float.BYTES);
				addStorageBuffer(bytes, memoryMode);
			}
			if (buffers.size() != BUFFER_COUNT) throw new IllegalStateException("Wrong GPU buffer count");
		} catch (RuntimeException | LinkageError exception) {
			try { close(); }
			catch (RuntimeException | LinkageError closeFailure) { exception.addSuppressed(closeFailure); }
			throw exception;
		}
	}

	int size() { return buffers.size(); }
	GpuBuffer buffer(int index) { return buffers.get(index); }
	long maximumBufferBytes() {
		long maximum = 0L;
		for (GpuBuffer buffer : buffers) maximum = Math.max(maximum, buffer.size());
		return maximum;
	}

	GpuBuffer createHostTransferBuffer(long bytes) {
		return createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
				VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
	}

	void destroy(GpuBuffer buffer) {
		if (buffer == null) return;
		if (buffer.mappedAddress() != NULL) vkUnmapMemory(context.device(), buffer.memory());
		vkDestroyBuffer(context.device(), buffer.handle(), null);
		vkFreeMemory(context.device(), buffer.memory(), null);
	}

	private void addStorageBuffer(long bytes, VulkanFlipPipeline.MemoryMode memoryMode) {
		int memoryProperties = memoryMode == VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING
				? VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
				: VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
		buffers.add(createBuffer(bytes,
				VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
				memoryProperties, memoryMode == VulkanFlipPipeline.MemoryMode.HOST_VISIBLE));
	}

	private GpuBuffer createBuffer(long bytes, int usage, int memoryProperties, boolean map) {
		long handle = VK_NULL_HANDLE;
		long memory = VK_NULL_HANDLE;
		long mappedAddress = NULL;
		try (MemoryStack stack = stackPush()) {
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
					.usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer bufferPointer = stack.mallocLong(1);
			VulkanDeviceContext.check(vkCreateBuffer(context.device(), info, null, bufferPointer), "vkCreateBuffer");
			handle = bufferPointer.get(0);
			VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
			vkGetBufferMemoryRequirements(context.device(), handle, requirements);
			int type = context.findMemoryType(requirements.memoryTypeBits(), memoryProperties, stack);
			VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default()
					.allocationSize(requirements.size()).memoryTypeIndex(type);
			LongBuffer memoryPointer = stack.mallocLong(1);
			VulkanDeviceContext.check(vkAllocateMemory(context.device(), allocation, null, memoryPointer), "vkAllocateMemory");
			memory = memoryPointer.get(0);
			VulkanDeviceContext.check(vkBindBufferMemory(context.device(), handle, memory, 0), "vkBindBufferMemory");
			if (map) {
				PointerBuffer mapped = stack.mallocPointer(1);
				VulkanDeviceContext.check(vkMapMemory(context.device(), memory, 0, bytes, 0, mapped), "vkMapMemory");
				mappedAddress = mapped.get(0);
				MemoryUtil.memSet(mappedAddress, 0, bytes);
			}
			return new GpuBuffer(handle, memory, mappedAddress, bytes);
		} catch (RuntimeException | LinkageError exception) {
			if (mappedAddress != NULL) vkUnmapMemory(context.device(), memory);
			if (handle != VK_NULL_HANDLE) vkDestroyBuffer(context.device(), handle, null);
			if (memory != VK_NULL_HANDLE) vkFreeMemory(context.device(), memory, null);
			throw exception;
		}
	}

	@Override
	public void close() {
		for (int i = buffers.size() - 1; i >= 0; i--) destroy(buffers.get(i));
		buffers.clear();
	}
}

record GpuBuffer(long handle, long memory, long mappedAddress, long size) {
	FloatBuffer floats(long byteCount) {
		return MemoryUtil.memFloatBuffer(requireMapped(), Math.toIntExact(byteCount / Float.BYTES));
	}

	ByteBuffer bytes() { return bytes(size); }
	ByteBuffer bytes(long byteCount) {
		return MemoryUtil.memByteBuffer(requireMapped(), Math.toIntExact(byteCount)).order(ByteOrder.nativeOrder());
	}

	private long requireMapped() {
		if (mappedAddress == NULL) throw new IllegalStateException("GPU buffer is not host mapped");
		return mappedAddress;
	}
}
