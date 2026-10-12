package mmm.gpuprobe;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Owns the bounded command/fence/query ring and asynchronous completion ordering. */
final class GpuStepScheduler implements AutoCloseable {
	static final int RING_SIZE = 3;

	private final VulkanDeviceContext context;
	private final FlipGpuResources resources;
	private final List<SubmissionSlot> ring = new ArrayList<>(RING_SIZE);
	private ExecutorService completionExecutor;
	private int nextSlot;
	private int maximumOutstandingSubmissions;

	GpuStepScheduler(VulkanDeviceContext context, FlipGpuResources resources, long stagingBytes) {
		this.context = context;
		this.resources = resources;
		try {
			createRing(stagingBytes);
			completionExecutor = Executors.newSingleThreadExecutor(task -> {
				Thread thread = new Thread(task, "mmm-gpu-fence-completion");
				thread.setDaemon(true);
				return thread;
			});
		} catch (RuntimeException | LinkageError exception) {
			try { close(); }
			catch (RuntimeException | LinkageError closeFailure) { exception.addSuppressed(closeFailure); }
			throw exception;
		}
	}

	int ringSize() { return RING_SIZE; }
	int maximumOutstandingSubmissions() { return maximumOutstandingSubmissions; }
	long regularStagingBytes() {
		long bytes = 0L;
		for (SubmissionSlot slot : ring) bytes += slot.stagingBuffer().size();
		return bytes;
	}

	SubmissionSlot acquire() {
		if (ring.size() != RING_SIZE) throw new IllegalStateException("Vulkan submission ring is unavailable");
		SubmissionSlot slot = ring.get(nextSlot);
		nextSlot = (nextSlot + 1) % RING_SIZE;
		awaitSlotCompletion(slot);
		return slot;
	}

	VkCommandBuffer begin(SubmissionSlot slot, String label) {
		try (MemoryStack stack = stackPush()) {
			VulkanDeviceContext.check(vkResetCommandPool(context.device(), slot.commandPool(), 0),
					"vkResetCommandPool(" + label + ")");
			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
			VulkanDeviceContext.check(vkBeginCommandBuffer(slot.commandBuffer(), begin),
					"vkBeginCommandBuffer(" + label + ")");
			return slot.commandBuffer();
		}
	}

	void submitAndWait(MemoryStack stack, SubmissionSlot slot, String label) {
		submitRecorded(stack, slot, label);
		waitForFence(stack, slot, label);
	}

	void submitAsync(MemoryStack stack, SubmissionSlot slot, String label, Consumer<SubmissionSlot> completion) {
		submitRecorded(stack, slot, label);
		slot.completion = completionExecutor.submit(() -> {
			try (MemoryStack completionStack = stackPush()) {
				waitForFence(completionStack, slot, label);
				completion.accept(slot);
			}
		});
		maximumOutstandingSubmissions = Math.max(maximumOutstandingSubmissions, outstandingSubmissionCount());
	}

	void awaitSubmittedWork() {
		Throwable failure = null;
		for (SubmissionSlot slot : ring) {
			try {
				awaitSlotCompletion(slot);
			} catch (RuntimeException | LinkageError exception) {
				if (failure == null) failure = exception;
				else failure.addSuppressed(exception);
			}
		}
		rethrow(failure);
	}

	long readTimestampNanos(SubmissionSlot slot) {
		if (slot.timestampQueryPool() == VK_NULL_HANDLE) throw new IllegalStateException("Timestamp queries unavailable");
		try (MemoryStack stack = stackPush()) {
			LongBuffer timestamps = stack.mallocLong(2);
			VulkanDeviceContext.check(vkGetQueryPoolResults(context.device(), slot.timestampQueryPool(), 0, 2,
					timestamps, Long.BYTES, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT),
					"vkGetQueryPoolResults(timestamp)");
			long ticks;
			if (context.timestampValidBits() == Long.SIZE) {
				ticks = timestamps.get(1) - timestamps.get(0);
			} else {
				long mask = (1L << context.timestampValidBits()) - 1L;
				ticks = (timestamps.get(1) - timestamps.get(0)) & mask;
			}
			return Math.max(1L, Math.round(ticks * (double) context.timestampPeriodNanos()));
		}
	}

	private void submitRecorded(MemoryStack stack, SubmissionSlot slot, String label) {
		VkCommandBuffer commandBuffer = slot.commandBuffer();
		VulkanDeviceContext.check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer(" + label + ")");
		VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
		submit.get(0).sType$Default().pCommandBuffers(stack.pointers(commandBuffer));
		VulkanDeviceContext.check(vkResetFences(context.device(), stack.longs(slot.fence())),
				"vkResetFences(" + label + ")");
		VulkanDeviceContext.check(vkQueueSubmit(context.queue(), submit, slot.fence()),
				"vkQueueSubmit(" + label + ")");
	}

	private void createRing(long stagingBytes) {
		for (int index = 0; index < RING_SIZE; index++) {
			GpuBuffer staging = resources.createHostTransferBuffer(stagingBytes);
			long commandPool = VK_NULL_HANDLE;
			long fence = VK_NULL_HANDLE;
			long queryPool = VK_NULL_HANDLE;
			try (MemoryStack stack = stackPush()) {
				VkCommandPoolCreateInfo commandPoolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
						.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(context.queueFamily());
				LongBuffer commandPoolPointer = stack.mallocLong(1);
				VulkanDeviceContext.check(vkCreateCommandPool(context.device(), commandPoolInfo, null, commandPoolPointer),
						"vkCreateCommandPool(ring)");
				commandPool = commandPoolPointer.get(0);
				VkCommandBufferAllocateInfo commandInfo = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
						.commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
				PointerBuffer commandPointer = stack.mallocPointer(1);
				VulkanDeviceContext.check(vkAllocateCommandBuffers(context.device(), commandInfo, commandPointer),
						"vkAllocateCommandBuffers(ring)");
				VkCommandBuffer commandBuffer = new VkCommandBuffer(commandPointer.get(0), context.device());
				VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
				LongBuffer fencePointer = stack.mallocLong(1);
				VulkanDeviceContext.check(vkCreateFence(context.device(), fenceInfo, null, fencePointer),
						"vkCreateFence(ring)");
				fence = fencePointer.get(0);
				if (context.hasTimestampQueries()) {
					VkQueryPoolCreateInfo queryInfo = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
							.queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2);
					LongBuffer queryPointer = stack.mallocLong(1);
					VulkanDeviceContext.check(vkCreateQueryPool(context.device(), queryInfo, null, queryPointer),
							"vkCreateQueryPool(ring)");
					queryPool = queryPointer.get(0);
				}
				ring.add(new SubmissionSlot(index, staging, commandPool, commandBuffer, fence, queryPool));
			} catch (RuntimeException | LinkageError exception) {
				if (queryPool != VK_NULL_HANDLE) vkDestroyQueryPool(context.device(), queryPool, null);
				if (fence != VK_NULL_HANDLE) vkDestroyFence(context.device(), fence, null);
				if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(context.device(), commandPool, null);
				resources.destroy(staging);
				throw exception;
			}
		}
	}

	private void awaitSlotCompletion(SubmissionSlot slot) {
		Future<?> completion = slot.completion;
		if (completion == null) return;
		try {
			completion.get();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for Vulkan submission ring", exception);
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			if (cause instanceof Error error) throw error;
			throw new IllegalStateException("Vulkan submission completion failed", cause);
		} finally {
			slot.completion = null;
		}
	}

	private int outstandingSubmissionCount() {
		int count = 0;
		for (SubmissionSlot slot : ring) if (slot.completion != null) count++;
		return count;
	}

	private void waitForFence(MemoryStack stack, SubmissionSlot slot, String label) {
		VulkanDeviceContext.check(vkWaitForFences(context.device(), stack.longs(slot.fence()), true, -1L),
				"vkWaitForFences(" + label + ")");
	}

	@Override
	public void close() {
		Throwable completionFailure = null;
		try {
			awaitSubmittedWork();
		} catch (RuntimeException | LinkageError exception) {
			completionFailure = exception;
		}
		if (completionExecutor != null) {
			completionExecutor.shutdown();
			completionExecutor = null;
		}
		if (context.device() != null) {
			int idleResult = vkDeviceWaitIdle(context.device());
			if (idleResult != VK_SUCCESS) {
				IllegalStateException idleFailure = new IllegalStateException(
						"vkDeviceWaitIdle(close-scheduler) failed with VkResult " + idleResult);
				if (completionFailure == null) completionFailure = idleFailure;
				else completionFailure.addSuppressed(idleFailure);
			}
			for (int i = ring.size() - 1; i >= 0; i--) {
				SubmissionSlot slot = ring.get(i);
				if (slot.timestampQueryPool() != VK_NULL_HANDLE) {
					vkDestroyQueryPool(context.device(), slot.timestampQueryPool(), null);
				}
				if (slot.fence() != VK_NULL_HANDLE) vkDestroyFence(context.device(), slot.fence(), null);
				if (slot.commandPool() != VK_NULL_HANDLE) vkDestroyCommandPool(context.device(), slot.commandPool(), null);
				resources.destroy(slot.stagingBuffer());
			}
		}
		ring.clear();
		rethrow(completionFailure);
	}

	private static void rethrow(Throwable failure) {
		if (failure instanceof RuntimeException runtime) throw runtime;
		if (failure instanceof LinkageError linkage) throw linkage;
	}

	static final class SubmissionSlot {
		private final int index;
		private final GpuBuffer stagingBuffer;
		private final long commandPool;
		private final VkCommandBuffer commandBuffer;
		private final long fence;
		private final long timestampQueryPool;
		private volatile Future<?> completion;

		private SubmissionSlot(int index, GpuBuffer stagingBuffer, long commandPool, VkCommandBuffer commandBuffer,
		                       long fence, long timestampQueryPool) {
			this.index = index;
			this.stagingBuffer = stagingBuffer;
			this.commandPool = commandPool;
			this.commandBuffer = commandBuffer;
			this.fence = fence;
			this.timestampQueryPool = timestampQueryPool;
		}

		int index() { return index; }
		GpuBuffer stagingBuffer() { return stagingBuffer; }
		long commandPool() { return commandPool; }
		VkCommandBuffer commandBuffer() { return commandBuffer; }
		long fence() { return fence; }
		long timestampQueryPool() { return timestampQueryPool; }
	}
}
