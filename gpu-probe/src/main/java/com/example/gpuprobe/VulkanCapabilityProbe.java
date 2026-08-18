package com.example.gpuprobe;

import org.lwjgl.PointerBuffer;
import org.lwjgl.Version;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.vma.Vma.vmaCreateAllocator;
import static org.lwjgl.util.vma.Vma.vmaDestroyAllocator;
import static org.lwjgl.vulkan.VK10.*;

/** Creates no window or surface. The dispatch writes a known sequence into a storage buffer. */
final class VulkanCapabilityProbe {
	private static final int VALUE_COUNT = 1_024;
	private static final String COMPUTE_SHADER = """
			#version 450
			layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
			layout(set = 0, binding = 0, std430) buffer Data { uint values[]; } data;
			void main() {
			    uint i = gl_GlobalInvocationID.x;
			    if (i < 1024) data.values[i] = i * 3u + 7u;
			}
			""";

	private VulkanCapabilityProbe() { }

	static ProbeReport run(int requestedDevice) {
		long started = System.nanoTime();
		VkInstance instance = null;
		try (MemoryStack stack = stackPush()) {
			VkApplicationInfo applicationInfo = VkApplicationInfo.calloc(stack)
					.sType$Default()
					.pApplicationName(stack.UTF8("Real Water GPU Probe"))
					.applicationVersion(1)
					.pEngineName(stack.UTF8("Real Water"))
					.engineVersion(1)
					.apiVersion(VK_MAKE_API_VERSION(0, 1, 1, 0));
			VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
					.sType$Default()
					.pApplicationInfo(applicationInfo);
			PointerBuffer instancePointer = stack.mallocPointer(1);
			check(vkCreateInstance(instanceInfo, null, instancePointer), "vkCreateInstance");
			instance = new VkInstance(instancePointer.get(0), instanceInfo);

			IntBuffer deviceCount = stack.ints(0);
			check(vkEnumeratePhysicalDevices(instance, deviceCount, null), "vkEnumeratePhysicalDevices(count)");
			if (deviceCount.get(0) == 0) throw new IllegalStateException("Vulkan reported no physical devices");
			PointerBuffer devicePointers = stack.mallocPointer(deviceCount.get(0));
			check(vkEnumeratePhysicalDevices(instance, deviceCount, devicePointers), "vkEnumeratePhysicalDevices(list)");

			List<DeviceReport> devices = new ArrayList<>();
			List<VkPhysicalDevice> physicalDevices = new ArrayList<>();
			for (int i = 0; i < devicePointers.remaining(); i++) {
				VkPhysicalDevice physicalDevice = new VkPhysicalDevice(devicePointers.get(i), instance);
				physicalDevices.add(physicalDevice);
				devices.add(describeDevice(physicalDevice, i));
			}
			if (requestedDevice < 0 || requestedDevice >= physicalDevices.size()) {
				throw new IllegalArgumentException("Requested Vulkan device " + requestedDevice + " but found " + physicalDevices.size());
			}

			DeviceReport selected = devices.get(requestedDevice);
			VkPhysicalDevice physicalDevice = physicalDevices.get(requestedDevice);
			if (selected.computeQueueFamily() < 0) {
				throw new IllegalStateException("Selected device has no compute queue: " + selected.name());
			}
			DispatchReport dispatch = runDispatch(instance, physicalDevice, selected.computeQueueFamily());
			long elapsed = System.nanoTime() - started;
			return new ProbeReport(
					true,
					Version.getVersion(),
					devices,
					requestedDevice,
					dispatch.shadercLoaded(),
					dispatch.vmaCreated(),
					dispatch.checksum(),
					dispatch.dispatchNanos() / 1_000_000.0,
					elapsed / 1_000_000.0,
					null
			);
		} finally {
			if (instance != null) vkDestroyInstance(instance, null);
		}
	}

	private static DeviceReport describeDevice(VkPhysicalDevice device, int index) {
		try (MemoryStack stack = stackPush()) {
			VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
			vkGetPhysicalDeviceProperties(device, properties);
			IntBuffer queueCount = stack.ints(0);
			vkGetPhysicalDeviceQueueFamilyProperties(device, queueCount, null);
			VkQueueFamilyProperties.Buffer queues = VkQueueFamilyProperties.calloc(queueCount.get(0), stack);
			vkGetPhysicalDeviceQueueFamilyProperties(device, queueCount, queues);
			int computeFamily = -1;
			for (int i = 0; i < queues.capacity(); i++) {
				if ((queues.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
					computeFamily = i;
					if ((queues.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) break;
				}
			}

			IntBuffer extensionCount = stack.ints(0);
			check(vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, extensionCount, null), "vkEnumerateDeviceExtensionProperties(count)");
			boolean floatAtomics = false;
			try (VkExtensionProperties.Buffer extensions = VkExtensionProperties.calloc(extensionCount.get(0))) {
				check(vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, extensionCount, extensions), "vkEnumerateDeviceExtensionProperties(list)");
				for (int i = 0; i < extensions.capacity(); i++) {
					if ("VK_EXT_shader_atomic_float".equals(extensions.get(i).extensionNameString())) floatAtomics = true;
				}
			}

			VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
			vkGetPhysicalDeviceMemoryProperties(device, memory);
			long deviceLocalBytes = 0L;
			for (int i = 0; i < memory.memoryHeapCount(); i++) {
				VkMemoryHeap heap = memory.memoryHeaps(i);
				if ((heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) deviceLocalBytes += heap.size();
			}
			return new DeviceReport(
					index,
					properties.deviceNameString(),
					version(properties.apiVersion()),
					Integer.toUnsignedString(properties.driverVersion()),
					String.format("0x%04X", properties.vendorID()),
					String.format("0x%04X", properties.deviceID()),
					computeFamily,
					floatAtomics,
					deviceLocalBytes,
					properties.limits().maxComputeWorkGroupInvocations(),
					properties.limits().timestampPeriod()
			);
		}
	}

	private static DispatchReport runDispatch(VkInstance instance, VkPhysicalDevice physicalDevice, int queueFamily) {
		VkDevice device = null;
		long allocator = NULL;
		long buffer = VK_NULL_HANDLE;
		long memory = VK_NULL_HANDLE;
		long descriptorLayout = VK_NULL_HANDLE;
		long pipelineLayout = VK_NULL_HANDLE;
		long shaderModule = VK_NULL_HANDLE;
		long pipeline = VK_NULL_HANDLE;
		long descriptorPool = VK_NULL_HANDLE;
		long commandPool = VK_NULL_HANDLE;
		long mappedAddress = NULL;
		try (MemoryStack stack = stackPush()) {
			VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
			queueInfo.get(0)
					.sType$Default()
					.queueFamilyIndex(queueFamily)
					.pQueuePriorities(stack.floats(1.0F));
			VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
					.sType$Default()
					.pQueueCreateInfos(queueInfo);
			PointerBuffer devicePointer = stack.mallocPointer(1);
			check(vkCreateDevice(physicalDevice, deviceInfo, null, devicePointer), "vkCreateDevice");
			device = new VkDevice(devicePointer.get(0), physicalDevice, deviceInfo);
			PointerBuffer queuePointer = stack.mallocPointer(1);
			vkGetDeviceQueue(device, queueFamily, 0, queuePointer);
			VkQueue queue = new VkQueue(queuePointer.get(0), device);

			VmaVulkanFunctions vulkanFunctions = VmaVulkanFunctions.calloc(stack).set(instance, device);
			VmaAllocatorCreateInfo allocatorInfo = VmaAllocatorCreateInfo.calloc(stack)
					.instance(instance)
					.physicalDevice(physicalDevice)
					.device(device)
					.pVulkanFunctions(vulkanFunctions)
					.vulkanApiVersion(VK_MAKE_API_VERSION(0, 1, 1, 0));
			PointerBuffer allocatorPointer = stack.mallocPointer(1);
			check(vmaCreateAllocator(allocatorInfo, allocatorPointer), "vmaCreateAllocator");
			allocator = allocatorPointer.get(0);

			long byteSize = VALUE_COUNT * Integer.BYTES;
			VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
					.sType$Default()
					.size(byteSize)
					.usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
					.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer bufferPointer = stack.mallocLong(1);
			check(vkCreateBuffer(device, bufferInfo, null, bufferPointer), "vkCreateBuffer");
			buffer = bufferPointer.get(0);
			VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
			vkGetBufferMemoryRequirements(device, buffer, requirements);
			int memoryType = findMemoryType(physicalDevice, requirements.memoryTypeBits(),
					VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack);
			VkMemoryAllocateInfo allocationInfo = VkMemoryAllocateInfo.calloc(stack)
					.sType$Default()
					.allocationSize(requirements.size())
					.memoryTypeIndex(memoryType);
			LongBuffer memoryPointer = stack.mallocLong(1);
			check(vkAllocateMemory(device, allocationInfo, null, memoryPointer), "vkAllocateMemory");
			memory = memoryPointer.get(0);
			check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
			PointerBuffer mapped = stack.mallocPointer(1);
			check(vkMapMemory(device, memory, 0, byteSize, 0, mapped), "vkMapMemory");
			mappedAddress = mapped.get(0);
			MemoryUtil.memSet(mappedAddress, 0, byteSize);

			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
			bindings.get(0)
					.binding(0)
					.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
					.descriptorCount(1)
					.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			VkDescriptorSetLayoutCreateInfo descriptorLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
					.sType$Default()
					.pBindings(bindings);
			LongBuffer descriptorLayoutPointer = stack.mallocLong(1);
			check(vkCreateDescriptorSetLayout(device, descriptorLayoutInfo, null, descriptorLayoutPointer), "vkCreateDescriptorSetLayout");
			descriptorLayout = descriptorLayoutPointer.get(0);

			VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
					.sType$Default()
					.pSetLayouts(stack.longs(descriptorLayout));
			LongBuffer pipelineLayoutPointer = stack.mallocLong(1);
			check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pipelineLayoutPointer), "vkCreatePipelineLayout");
			pipelineLayout = pipelineLayoutPointer.get(0);

			ByteBuffer spirv = compileShader(COMPUTE_SHADER);
			try {
				VkShaderModuleCreateInfo shaderInfo = VkShaderModuleCreateInfo.calloc(stack)
						.sType$Default()
						.pCode(spirv);
				LongBuffer shaderPointer = stack.mallocLong(1);
				check(vkCreateShaderModule(device, shaderInfo, null, shaderPointer), "vkCreateShaderModule");
				shaderModule = shaderPointer.get(0);
			} finally {
				MemoryUtil.memFree(spirv);
			}

			VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
					.sType$Default()
					.stage(VK_SHADER_STAGE_COMPUTE_BIT)
					.module(shaderModule)
					.pName(stack.UTF8("main"));
			VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
			pipelineInfo.get(0)
					.sType$Default()
					.stage(stageInfo)
					.layout(pipelineLayout);
			LongBuffer pipelinePointer = stack.mallocLong(1);
			check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pipelinePointer), "vkCreateComputePipelines");
			pipeline = pipelinePointer.get(0);

			VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
			poolSize.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
					.sType$Default()
					.maxSets(1)
					.pPoolSizes(poolSize);
			LongBuffer poolPointer = stack.mallocLong(1);
			check(vkCreateDescriptorPool(device, poolInfo, null, poolPointer), "vkCreateDescriptorPool");
			descriptorPool = poolPointer.get(0);
			VkDescriptorSetAllocateInfo setInfo = VkDescriptorSetAllocateInfo.calloc(stack)
					.sType$Default()
					.descriptorPool(descriptorPool)
					.pSetLayouts(stack.longs(descriptorLayout));
			LongBuffer setPointer = stack.mallocLong(1);
			check(vkAllocateDescriptorSets(device, setInfo, setPointer), "vkAllocateDescriptorSets");
			long descriptorSet = setPointer.get(0);
			VkDescriptorBufferInfo.Buffer descriptorBuffer = VkDescriptorBufferInfo.calloc(1, stack);
			descriptorBuffer.get(0).buffer(buffer).offset(0).range(byteSize);
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
			writes.get(0)
					.sType$Default()
					.dstSet(descriptorSet)
					.dstBinding(0)
					.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
					.descriptorCount(1)
					.pBufferInfo(descriptorBuffer);
			vkUpdateDescriptorSets(device, writes, null);

			VkCommandPoolCreateInfo commandPoolInfo = VkCommandPoolCreateInfo.calloc(stack)
					.sType$Default()
					.queueFamilyIndex(queueFamily);
			LongBuffer commandPoolPointer = stack.mallocLong(1);
			check(vkCreateCommandPool(device, commandPoolInfo, null, commandPoolPointer), "vkCreateCommandPool");
			commandPool = commandPoolPointer.get(0);
			VkCommandBufferAllocateInfo commandInfo = VkCommandBufferAllocateInfo.calloc(stack)
					.sType$Default()
					.commandPool(commandPool)
					.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
					.commandBufferCount(1);
			PointerBuffer commandPointer = stack.mallocPointer(1);
			check(vkAllocateCommandBuffers(device, commandInfo, commandPointer), "vkAllocateCommandBuffers");
			VkCommandBuffer commandBuffer = new VkCommandBuffer(commandPointer.get(0), device);
			VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
			check(vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer");
			vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
			vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
					stack.longs(descriptorSet), null);
			vkCmdDispatch(commandBuffer, VALUE_COUNT / 64, 1, 1);
			VkMemoryBarrier.Buffer hostReadBarrier = VkMemoryBarrier.calloc(1, stack);
			hostReadBarrier.get(0)
					.sType$Default()
					.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
					.dstAccessMask(VK_ACCESS_HOST_READ_BIT);
			vkCmdPipelineBarrier(
					commandBuffer,
					VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
					VK_PIPELINE_STAGE_HOST_BIT,
					0,
					hostReadBarrier,
					null,
					null
			);
			check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");

			long dispatchStarted = System.nanoTime();
			VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.calloc(1, stack);
			submitInfo.get(0).sType$Default().pCommandBuffers(stack.pointers(commandBuffer));
			check(vkQueueSubmit(queue, submitInfo, VK_NULL_HANDLE), "vkQueueSubmit");
			check(vkQueueWaitIdle(queue), "vkQueueWaitIdle");
			long dispatchNanos = System.nanoTime() - dispatchStarted;

			IntBuffer values = MemoryUtil.memIntBuffer(mappedAddress, VALUE_COUNT);
			long checksum = 0L;
			for (int i = 0; i < VALUE_COUNT; i++) {
				int expected = i * 3 + 7;
				if (values.get(i) != expected) {
					throw new IllegalStateException("Compute mismatch at " + i + ": " + values.get(i) + " != " + expected);
				}
				checksum += Integer.toUnsignedLong(values.get(i));
			}
			return new DispatchReport(true, true, checksum, dispatchNanos);
		} finally {
			if (device != null) {
				vkDeviceWaitIdle(device);
				if (mappedAddress != NULL) vkUnmapMemory(device, memory);
				if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, null);
				if (descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool, null);
				if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, null);
				if (shaderModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, shaderModule, null);
				if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout, null);
				if (descriptorLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
				if (buffer != VK_NULL_HANDLE) vkDestroyBuffer(device, buffer, null);
				if (memory != VK_NULL_HANDLE) vkFreeMemory(device, memory, null);
				if (allocator != NULL) vmaDestroyAllocator(allocator);
				vkDestroyDevice(device, null);
			}
		}
	}

	private static ByteBuffer compileShader(String source) {
		long compiler = shaderc_compiler_initialize();
		if (compiler == NULL) throw new IllegalStateException("shaderc_compiler_initialize returned NULL");
		long result = NULL;
		try {
			result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader, "realwater-probe.comp", "main", NULL);
			if (result == NULL) throw new IllegalStateException("shaderc_compile_into_spv returned NULL");
			int status = shaderc_result_get_compilation_status(result);
			if (status != shaderc_compilation_status_success) {
				throw new IllegalStateException("Shaderc failed: " + shaderc_result_get_error_message(result));
			}
			ByteBuffer bytes = shaderc_result_get_bytes(result);
			if (bytes == null) throw new IllegalStateException("Shaderc returned no SPIR-V bytes");
			ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
			copy.put(bytes).flip();
			return copy;
		} finally {
			if (result != NULL) shaderc_result_release(result);
			shaderc_compiler_release(compiler);
		}
	}

	private static int findMemoryType(VkPhysicalDevice physicalDevice, int typeBits, int requiredFlags, MemoryStack stack) {
		VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
		vkGetPhysicalDeviceMemoryProperties(physicalDevice, properties);
		for (int i = 0; i < properties.memoryTypeCount(); i++) {
			if ((typeBits & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & requiredFlags) == requiredFlags) return i;
		}
		throw new IllegalStateException("No Vulkan memory type supports flags 0x" + Integer.toHexString(requiredFlags));
	}

	private static void check(int result, String operation) {
		if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult " + result);
	}

	private static String version(int packed) {
		return ((packed >>> 22) & 0x3ff) + "." + ((packed >>> 12) & 0x3ff) + "." + (packed & 0xfff);
	}

	record DeviceReport(
			int index,
			String name,
			String apiVersion,
			String driverVersion,
			String vendorId,
			String deviceId,
			int computeQueueFamily,
			boolean shaderAtomicFloatExtension,
			long deviceLocalBytes,
			int maxComputeWorkGroupInvocations,
			float timestampPeriodNanos
	) {
		String format() {
			return String.format(
					"[%d] %s | Vulkan %s | driver %s | vendor %s device %s | computeQ %d | floatAtomicExt %s | local %.2f GiB | maxWG %d | timestamp %.3f ns",
					index, name, apiVersion, driverVersion, vendorId, deviceId, computeQueueFamily,
					shaderAtomicFloatExtension, deviceLocalBytes / 1_073_741_824.0,
					maxComputeWorkGroupInvocations, timestampPeriodNanos
			);
		}
	}

	record DispatchReport(boolean shadercLoaded, boolean vmaCreated, long checksum, long dispatchNanos) { }

	record ProbeReport(
			boolean success,
			String lwjglVersion,
			List<DeviceReport> devices,
			int selectedDevice,
			boolean shadercLoaded,
			boolean vmaCreated,
			long checksum,
			double dispatchMilliseconds,
			double totalMilliseconds,
			String failure
	) {
		static ProbeReport failure(Throwable failure) {
			return new ProbeReport(false, Version.getVersion(), List.of(), -1, false, false, 0L, 0.0, 0.0,
					failure.getClass().getSimpleName() + ": " + failure.getMessage());
		}

		String oneLine() {
			if (!success) return "GPU probe FAIL，CPU fallback active：" + failure;
			DeviceReport selected = devices.get(selectedDevice);
			return String.format("GPU probe PASS | %s | Vulkan %s | Shaderc %s | VMA %s | dispatch %.3f ms | checksum %d | CPU fallback retained",
					selected.name(), selected.apiVersion(), shadercLoaded, vmaCreated, dispatchMilliseconds, checksum);
		}

		String format() {
			StringBuilder text = new StringBuilder();
			text.append(oneLine()).append('\n');
			text.append("LWJGL ").append(lwjglVersion).append(" | devices ").append(devices.size())
					.append(" | total ").append(String.format("%.3f ms", totalMilliseconds)).append('\n');
			for (DeviceReport device : devices) text.append(device.format()).append('\n');
			return text.toString().stripTrailing();
		}
	}
}
