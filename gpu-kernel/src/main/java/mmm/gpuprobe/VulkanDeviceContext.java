package mmm.gpuprobe;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderAtomicFloatFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderAtomicInt64Features;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTShaderAtomicFloat.VK_EXT_SHADER_ATOMIC_FLOAT_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRShaderAtomicInt64.VK_KHR_SHADER_ATOMIC_INT64_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/** Owns the Vulkan instance, selected physical device, logical device and compute queue. */
final class VulkanDeviceContext implements AutoCloseable {
	private VkInstance instance;
	private VkPhysicalDevice physicalDevice;
	private VkDevice device;
	private VkQueue queue;
	private int queueFamily;
	private String deviceName;
	private long storageOffsetAlignment;
	private long maxStorageBufferRange;
	private float timestampPeriodNanos;
	private int timestampValidBits;

	VulkanDeviceContext(int deviceIndex) {
		this(deviceIndex, false);
	}

	VulkanDeviceContext(int deviceIndex, boolean inventoryInt64) {
		this(deviceIndex, inventoryInt64, false);
	}

	VulkanDeviceContext(int deviceIndex, boolean inventoryInt64, boolean computeFloat64) {
		try {
			create(deviceIndex, inventoryInt64, computeFloat64);
		} catch (RuntimeException | LinkageError exception) {
			try { close(); }
			catch (RuntimeException | LinkageError closeFailure) { exception.addSuppressed(closeFailure); }
			throw exception;
		}
	}

	VkDevice device() { return device; }
	VkQueue queue() { return queue; }
	int queueFamily() { return queueFamily; }
	String deviceName() { return deviceName; }
	long storageOffsetAlignment() { return storageOffsetAlignment; }
	long maxStorageBufferRange() { return maxStorageBufferRange; }
	float timestampPeriodNanos() { return timestampPeriodNanos; }
	int timestampValidBits() { return timestampValidBits; }
	boolean hasTimestampQueries() { return timestampPeriodNanos > 0.0F && timestampValidBits > 0; }

	int findMemoryType(int bits, int required, MemoryStack stack) {
		VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
		vkGetPhysicalDeviceMemoryProperties(physicalDevice, memory);
		for (int i = 0; i < memory.memoryTypeCount(); i++) {
			if ((bits & (1 << i)) != 0 && (memory.memoryTypes(i).propertyFlags() & required) == required) return i;
		}
		throw new IllegalStateException("No Vulkan memory type for required flags 0x" + Integer.toHexString(required));
	}

	private void create(int deviceIndex, boolean inventoryInt64, boolean computeFloat64) {
		try (MemoryStack stack = stackPush()) {
			VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
					.pApplicationName(stack.UTF8("Matter, Motion & Mechanics Vulkan Compute")).applicationVersion(1)
					.pEngineName(stack.UTF8("Matter, Motion & Mechanics")).engineVersion(1)
					.apiVersion(VK_MAKE_API_VERSION(0, 1, 1, 0));
			VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
			PointerBuffer instancePointer = stack.mallocPointer(1);
			check(vkCreateInstance(instanceInfo, null, instancePointer), "vkCreateInstance");
			instance = new VkInstance(instancePointer.get(0), instanceInfo);

			IntBuffer count = stack.ints(0);
			check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
			PointerBuffer physicalPointers = stack.mallocPointer(count.get(0));
			check(vkEnumeratePhysicalDevices(instance, count, physicalPointers), "vkEnumeratePhysicalDevices(list)");
			String requestedName=System.getProperty("mmm.gpuDeviceName", "").strip();
			if(!requestedName.isEmpty()) {
				int selected=-1;var names=new java.util.ArrayList<String>();
				var candidateProperties=VkPhysicalDeviceProperties.calloc(stack);
				for(int i=0;i<count.get(0);i++) {
					vkGetPhysicalDeviceProperties(new VkPhysicalDevice(physicalPointers.get(i),instance),candidateProperties);
					String name=candidateProperties.deviceNameString();names.add(i+":"+name);
					if(name.toLowerCase(java.util.Locale.ROOT).contains(requestedName.toLowerCase(java.util.Locale.ROOT))) {
						if(selected>=0)throw new IllegalArgumentException("GPU name matches multiple devices: "+requestedName+"; "+names);
						selected=i;
					}
				}
				if(selected<0)throw new IllegalArgumentException("GPU name is absent: "+requestedName+"; available="+names);
				deviceIndex=selected;
			}
			if(deviceIndex<0||deviceIndex>=count.get(0))throw new IllegalArgumentException("GPU index "+deviceIndex+" not in [0,"+count.get(0)+")");
			physicalDevice = new VkPhysicalDevice(physicalPointers.get(deviceIndex), instance);

			VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
			vkGetPhysicalDeviceProperties(physicalDevice, properties);
			deviceName = properties.deviceNameString();
			storageOffsetAlignment = properties.limits().minStorageBufferOffsetAlignment();
			maxStorageBufferRange = Integer.toUnsignedLong(properties.limits().maxStorageBufferRange());
			queueFamily = findComputeQueue(physicalDevice, stack);
			timestampPeriodNanos = properties.limits().timestampPeriod();
			timestampValidBits = timestampValidBits(physicalDevice, queueFamily, stack);

			VkPhysicalDeviceShaderAtomicFloatFeaturesEXT atomicFeatures =
					VkPhysicalDeviceShaderAtomicFloatFeaturesEXT.calloc(stack).sType$Default();
			VkPhysicalDeviceShaderAtomicInt64Features integerAtomics =
					VkPhysicalDeviceShaderAtomicInt64Features.calloc(stack).sType$Default();
			atomicFeatures.pNext(integerAtomics.address());
			VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
					.pNext(atomicFeatures.address());
			vkGetPhysicalDeviceFeatures2(physicalDevice, features);
			if (!inventoryInt64 && !computeFloat64 && !atomicFeatures.shaderBufferFloat32AtomicAdd()) {
				throw new IllegalStateException(deviceName + " lacks shaderBufferFloat32AtomicAdd");
			}
			atomicFeatures.pNext(0L);
			atomicFeatures.shaderBufferFloat32AtomicAdd(true).shaderBufferFloat32Atomics(true);
			VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
			queueInfo.get(0).sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1.0F));
			VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default()
					.pNext(atomicFeatures.address()).pQueueCreateInfos(queueInfo)
					.ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_EXT_SHADER_ATOMIC_FLOAT_EXTENSION_NAME)));
			if (inventoryInt64 || computeFloat64) {
				if (inventoryInt64 && !features.features().shaderInt64()) throw new IllegalStateException(deviceName + " lacks shaderInt64");
				if (inventoryInt64 && !integerAtomics.shaderBufferInt64Atomics()) throw new IllegalStateException(deviceName + " lacks shaderBufferInt64Atomics");
				if (computeFloat64 && !features.features().shaderFloat64()) throw new IllegalStateException(deviceName + " lacks shaderFloat64");
				integerAtomics.shaderBufferInt64Atomics(inventoryInt64).shaderSharedInt64Atomics(false);
				deviceInfo.pNext(inventoryInt64 ? integerAtomics.address() : 0L)
						.ppEnabledExtensionNames(inventoryInt64 ? stack.pointers(stack.UTF8(VK_KHR_SHADER_ATOMIC_INT64_EXTENSION_NAME)) : null)
						.pEnabledFeatures(org.lwjgl.vulkan.VkPhysicalDeviceFeatures.calloc(stack)
								.shaderInt64(inventoryInt64).shaderFloat64(computeFloat64));
			}
			PointerBuffer devicePointer = stack.mallocPointer(1);
			check(vkCreateDevice(physicalDevice, deviceInfo, null, devicePointer), "vkCreateDevice");
			device = new VkDevice(devicePointer.get(0), physicalDevice, deviceInfo);
			PointerBuffer queuePointer = stack.mallocPointer(1);
			vkGetDeviceQueue(device, queueFamily, 0, queuePointer);
			queue = new VkQueue(queuePointer.get(0), device);
		}
	}

	private static int findComputeQueue(VkPhysicalDevice physical, MemoryStack stack) {
		IntBuffer count = stack.ints(0);
		vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
		VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.calloc(count.get(0), stack);
		vkGetPhysicalDeviceQueueFamilyProperties(physical, count, properties);
		int fallback = -1;
		for (int i = 0; i < properties.capacity(); i++) {
			if ((properties.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) == 0) continue;
			fallback = i;
			if ((properties.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) return i;
		}
		if (fallback < 0) throw new IllegalStateException("No compute queue on selected device");
		return fallback;
	}

	private static int timestampValidBits(VkPhysicalDevice physical, int queueFamily, MemoryStack stack) {
		IntBuffer count = stack.ints(0);
		vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
		VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.calloc(count.get(0), stack);
		vkGetPhysicalDeviceQueueFamilyProperties(physical, count, properties);
		return properties.get(queueFamily).timestampValidBits();
	}

	@Override
	public void close() {
		Throwable failure = null;
		if (device != null) {
			int idleResult = vkDeviceWaitIdle(device);
			if (idleResult != VK_SUCCESS && idleResult != VK_ERROR_DEVICE_LOST) {
				failure = new IllegalStateException("vkDeviceWaitIdle(close-device) failed with VkResult " + idleResult);
			}
			vkDestroyDevice(device, null);
			device = null;
			queue = null;
		}
		if (instance != null) {
			vkDestroyInstance(instance, null);
			instance = null;
			physicalDevice = null;
		}
		if (failure instanceof RuntimeException runtime) throw runtime;
	}

	static void check(int result, String operation) {
		if (result == VK_ERROR_DEVICE_LOST) throw new mmm.fluid.SparseMatrixCompute.DeviceLostException(
				operation + " failed with VK_ERROR_DEVICE_LOST");
		if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult " + result);
	}
}
