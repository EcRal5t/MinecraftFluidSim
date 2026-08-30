package cc.ecisr.mmm.gpuprobe;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/** Owns shader compilation, descriptor bindings and the compute pipeline. */
final class FlipKernelBindings implements AutoCloseable {
	static final int PUSH_BYTES = 88;

	private final VulkanDeviceContext context;
	private long descriptorLayout;
	private long pipelineLayout;
	private long shaderModule;
	private long pipeline;
	private long descriptorPool;
	private long descriptorSet;

	FlipKernelBindings(VulkanDeviceContext context, FlipGpuResources resources) {
		this.context = context;
		try {
			create(resources);
		} catch (RuntimeException | LinkageError exception) {
			try { close(); }
			catch (RuntimeException | LinkageError closeFailure) { exception.addSuppressed(closeFailure); }
			throw exception;
		}
	}

	long pipelineLayout() { return pipelineLayout; }

	void bind(VkCommandBuffer commandBuffer, MemoryStack stack) {
		vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
		vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
				stack.longs(descriptorSet), null);
	}

	private void create(FlipGpuResources resources) {
		try (MemoryStack stack = stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings =
					VkDescriptorSetLayoutBinding.calloc(FlipGpuResources.BUFFER_COUNT, stack);
			for (int i = 0; i < FlipGpuResources.BUFFER_COUNT; i++) {
				bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
						.descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			}
			VkDescriptorSetLayoutCreateInfo descriptorInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
					.sType$Default().pBindings(bindings);
			LongBuffer layoutPointer = stack.mallocLong(1);
			VulkanDeviceContext.check(vkCreateDescriptorSetLayout(context.device(), descriptorInfo, null, layoutPointer),
					"vkCreateDescriptorSetLayout");
			descriptorLayout = layoutPointer.get(0);

			VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
			pushRange.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
			VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
					.pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(pushRange);
			LongBuffer pipelineLayoutPointer = stack.mallocLong(1);
			VulkanDeviceContext.check(vkCreatePipelineLayout(context.device(), pipelineLayoutInfo, null, pipelineLayoutPointer),
					"vkCreatePipelineLayout");
			pipelineLayout = pipelineLayoutPointer.get(0);

			ByteBuffer spirv = compileShader(GpuProbeShaderSource.loadFlip());
			try {
				VkShaderModuleCreateInfo shaderInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
				LongBuffer shaderPointer = stack.mallocLong(1);
				VulkanDeviceContext.check(vkCreateShaderModule(context.device(), shaderInfo, null, shaderPointer),
						"vkCreateShaderModule");
				shaderModule = shaderPointer.get(0);
			} finally {
				MemoryUtil.memFree(spirv);
			}

			VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
					.stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
			VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
			pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
			LongBuffer pipelinePointer = stack.mallocLong(1);
			VulkanDeviceContext.check(vkCreateComputePipelines(context.device(), VK_NULL_HANDLE, pipelineInfo, null,
					pipelinePointer), "vkCreateComputePipelines");
			pipeline = pipelinePointer.get(0);

			VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
			poolSize.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(FlipGpuResources.BUFFER_COUNT);
			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
					.maxSets(1).pPoolSizes(poolSize);
			LongBuffer poolPointer = stack.mallocLong(1);
			VulkanDeviceContext.check(vkCreateDescriptorPool(context.device(), poolInfo, null, poolPointer),
					"vkCreateDescriptorPool");
			descriptorPool = poolPointer.get(0);

			VkDescriptorSetAllocateInfo setInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
					.descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout));
			LongBuffer setPointer = stack.mallocLong(1);
			VulkanDeviceContext.check(vkAllocateDescriptorSets(context.device(), setInfo, setPointer),
					"vkAllocateDescriptorSets");
			descriptorSet = setPointer.get(0);

			VkDescriptorBufferInfo.Buffer infos = VkDescriptorBufferInfo.calloc(FlipGpuResources.BUFFER_COUNT, stack);
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(FlipGpuResources.BUFFER_COUNT, stack);
			for (int i = 0; i < FlipGpuResources.BUFFER_COUNT; i++) {
				GpuBuffer buffer = resources.buffer(i);
				infos.get(i).buffer(buffer.handle()).offset(0).range(buffer.size());
				writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i)
						.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
						.pBufferInfo(VkDescriptorBufferInfo.create(infos.get(i).address(), 1));
			}
			vkUpdateDescriptorSets(context.device(), writes, null);
		}
	}

	private static ByteBuffer compileShader(String source) {
		long compiler = org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize();
		if (compiler == NULL) throw new IllegalStateException("Shaderc compiler unavailable");
		long result = NULL;
		try {
			result = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv(compiler, source,
					org.lwjgl.util.shaderc.Shaderc.shaderc_compute_shader, "mmm-flip.comp", "main", NULL);
			if (result == NULL) throw new IllegalStateException("Shaderc returned NULL");
			if (org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(result)
					!= org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success) {
				throw new IllegalStateException("FLIP shader compile failed: "
						+ org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message(result));
			}
			ByteBuffer bytes = org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes(result);
			if (bytes == null) throw new IllegalStateException("Shaderc returned no SPIR-V");
			ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
			copy.put(bytes).flip();
			return copy;
		} finally {
			if (result != NULL) org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(result);
			org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(compiler);
		}
	}

	@Override
	public void close() {
		if (context.device() == null) return;
		if (descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(context.device(), descriptorPool, null);
		if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(context.device(), pipeline, null);
		if (shaderModule != VK_NULL_HANDLE) vkDestroyShaderModule(context.device(), shaderModule, null);
		if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(context.device(), pipelineLayout, null);
		if (descriptorLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(context.device(), descriptorLayout, null);
		descriptorPool = pipeline = shaderModule = pipelineLayout = descriptorLayout = descriptorSet = VK_NULL_HANDLE;
	}
}
