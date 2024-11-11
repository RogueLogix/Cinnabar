package graphics.cinnabar.internal.vulkan;

import graphics.cinnabar.Cinnabar;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntReferenceImmutablePair;
import it.unimi.dsi.fastutil.ints.IntReferencePair;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import net.roguelogix.phosphophyllite.util.Pair;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.List;

import static graphics.cinnabar.Cinnabar.LOGGER;
import static graphics.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetPhysicalDevicePresentationSupport;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

@NonnullDefault
public class VulkanCore implements Destroyable {
    private static final List<String> requiredDeviceExtensions = List.of(VK_KHR_SWAPCHAIN_EXTENSION_NAME, VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME);
    
    public final VkInstance vkInstance;
    private final long debugCallback;
    public final VkPhysicalDevice vkPhysicalDevice;
    public final VkDevice vkLogicalDevice;
    private final VkPhysicalDeviceProperties2 properties2;
    public final VkPhysicalDeviceLimits limits;
    public final VkPhysicalDeviceExternalMemoryHostPropertiesEXT externalMemoryHostProperties;
    public final int hostPtrMemoryTypeBits;
    
    public final VkQueue graphicsQueue;
    public final int graphicsQueueFamily;
    @Nullable
    public final VkQueue computeQueue;
    public final int comptueQueueFamily;
    @Nullable
    public final VkQueue transferQueue;
    public final int transferQueueFamily;
    
    
    public VulkanCore() {
        final var instanceAndDebugCallback = createVkInstance();
        vkInstance = instanceAndDebugCallback.first();
        debugCallback = instanceAndDebugCallback.second();
        vkPhysicalDevice = selectPhysicalDevice(vkInstance);
        try {
            final var deviceAndQueues = createLogicalDeviceAndQueues(vkPhysicalDevice);
            vkLogicalDevice = deviceAndQueues.first();
            final var queues = deviceAndQueues.right();
            graphicsQueue = queues.getFirst().second();
            graphicsQueueFamily = queues.getFirst().firstInt();
            computeQueue = queues.get(1).second();
            comptueQueueFamily = queues.get(1).firstInt();
            transferQueue = queues.get(2).second();
            transferQueueFamily = queues.get(2).firstInt();
        } catch (Exception e) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
            vkDestroyInstance(vkInstance, null);
            throw e;
        }
        
        properties2 = VkPhysicalDeviceProperties2.calloc().sType$Default();
        limits = properties2.properties().limits();
        externalMemoryHostProperties = VkPhysicalDeviceExternalMemoryHostPropertiesEXT.calloc().sType$Default();
        properties2.pNext(externalMemoryHostProperties);
        vkGetPhysicalDeviceProperties2(vkPhysicalDevice, properties2);
        // uint32 in C++, 2^32 - 1
        if (limits.maxMemoryAllocationCount() != -1) {
            destroy();
            throw new IllegalStateException("VK device must allow unlimited allocations");
        }
        
        int memoryFlags = 0;
        try (var stack = MemoryStack.stackPush()) {
            final var properties = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default();
            vkGetPhysicalDeviceMemoryProperties2(vkLogicalDevice.getPhysicalDevice(), properties);
            final var memoryProperties = properties.memoryProperties();
            for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
                memoryProperties.memoryTypes().position(i);
                final var propertyFlags = memoryProperties.memoryTypes().propertyFlags();
                final var requiredFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                if ((propertyFlags & requiredFlags) == requiredFlags) {
                    memoryFlags |= 1 << i;
                }
            }
        }
        hostPtrMemoryTypeBits = memoryFlags;
    }
    
    @Override
    public void destroy() {
        externalMemoryHostProperties.free();
        properties2.free();
        vkDestroyDevice(vkLogicalDevice, null);
        vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
        vkDestroyInstance(vkInstance, null);
    }
    
    private static Pair<VkInstance, Long> createVkInstance() {
        try (var stack = MemoryStack.stackPush()) {
            final var appInfo = VkApplicationInfo.calloc(stack);
            final var appName = stack.UTF8("Minecraft");
            final var engineName = stack.UTF8("Cinnabar");
            // TODO: pull this from FML/MC, so i dont have to update this every update
            final var appVersion = VK_MAKE_VERSION(1, 21, 1);
            final var engineVersion = VK_MAKE_VERSION(0, 0, 0);
            appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
            appInfo.pApplicationName(appName);
            appInfo.applicationVersion(appVersion);
            appInfo.pEngineName(engineName);
            appInfo.engineVersion(engineVersion);
            appInfo.apiVersion(VK_API_VERSION_1_3);
            
            final var enabledInstanceExtensions = new ReferenceArrayList<String>();
            
            
            final var createInfo = VkInstanceCreateInfo.calloc(stack).sType$Default();
            createInfo.pApplicationInfo(appInfo);
            
            @Nullable
            VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo = null;
            if (Cinnabar.CONFIG.EnableValidationLayers || Cinnabar.CONFIG.Debug) {
                LOGGER.info("Vulkan validation layers requested");
                boolean hasKHRValidation = false;
                try (var ignored = stack.push()) {
                    final var layerCount = stack.mallocInt(1);
                    vkEnumerateInstanceLayerProperties(layerCount, null);
                    final var layerProperties = VkLayerProperties.calloc(layerCount.get(0), stack);
                    vkEnumerateInstanceLayerProperties(layerCount, layerProperties);
                    for (int i = 0; i < layerProperties.capacity(); i++) {
                        layerProperties.position(i);
                        if (layerProperties.layerNameString().equals("VK_LAYER_KHRONOS_validation")) {
                            hasKHRValidation = true;
                            break;
                        }
                    }
                }
                boolean hasEXTDebug = false;
                try (var ignored = stack.push()) {
                    final var extensionCount = stack.mallocInt(1);
                    vkEnumerateInstanceExtensionProperties((String) null, extensionCount, null);
                    final var extensionProperties = VkExtensionProperties.calloc(extensionCount.get(0), stack);
                    vkEnumerateInstanceExtensionProperties((String) null, extensionCount, extensionProperties);
                    for (int i = 0; i < extensionProperties.capacity(); i++) {
                        extensionProperties.position(i);
                        if (extensionProperties.extensionNameString().equals(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                            hasEXTDebug = true;
                            break;
                        }
                    }
                }
                if (!hasKHRValidation) {
                    LOGGER.error("Unable to initialize validation layers, are they installed?");
                } else if (!hasEXTDebug) {
                    LOGGER.error("Unable to initialize validation layers, VK_EXT_debug_utils not present");
                } else {
                    LOGGER.warn("Vulkan validation layers enabled, performance may suffer!");
                    
                    var layerPointers = stack.mallocPointer(1);
                    layerPointers.put(0, stack.UTF8("VK_LAYER_KHRONOS_validation"));
                    // renderdoc does not support VK_EXT_external_memory_host
//                    layerPointers.put(1, stack.UTF8("VK_LAYER_RENDERDOC_Capture"));
                    enabledInstanceExtensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
                    
                    createInfo.ppEnabledLayerNames(layerPointers);
                    debugCreateInfo = VulkanDebug.getCreateInfo(stack);
                    createInfo.pNext(debugCreateInfo.address());
                }
            }
            
            @Nullable final var glfwExtensions = glfwGetRequiredInstanceExtensions();
            if (glfwExtensions == null) {
                throw new IllegalStateException("GLFW unable to present VK image");
            }
            
            final var extensionPointers = stack.mallocPointer(enabledInstanceExtensions.size() + glfwExtensions.capacity());
            for (int i = 0; i < enabledInstanceExtensions.size(); i++) {
                extensionPointers.put(i, stack.UTF8(enabledInstanceExtensions.get(i)));
            }
            for (int i = 0; i < glfwExtensions.capacity(); i++) {
                int index = i + enabledInstanceExtensions.size();
                extensionPointers.put(index, glfwExtensions.get(i));
            }
            createInfo.ppEnabledExtensionNames(extensionPointers);
            
            final var instancePointer = stack.mallocPointer(1);
            throwFromCode(vkCreateInstance(createInfo, null, instancePointer));
            final var vkInstance = new VkInstance(instancePointer.get(0), createInfo);
            LOGGER.info("VkInstance created");
            
            final long debugCallback;
            if (debugCreateInfo != null) {
                var lp = stack.mallocLong(1);
                throwFromCode(vkCreateDebugUtilsMessengerEXT(vkInstance, debugCreateInfo, null, lp));
                debugCallback = lp.get();
            } else {
                debugCallback = -1;
            }
            
            return new Pair<>(vkInstance, debugCallback);
        }
    }
    
    private static VkPhysicalDevice selectPhysicalDevice(VkInstance instance) {
        // TODO: query support for all required extensions and capabilities
        try (var stack = MemoryStack.stackPush()) {
            final var physicalDeviceCountPtr = stack.callocInt(1);
            vkEnumeratePhysicalDevices(instance, physicalDeviceCountPtr, null);
            final var physicalDeviceCount = physicalDeviceCountPtr.get(0);
            final var physicalDevices = stack.callocPointer(physicalDeviceCount);
            vkEnumeratePhysicalDevices(instance, physicalDeviceCountPtr, physicalDevices);
            
            final var queueFamilyCountPtr = stack.callocInt(1);
            final var currentPhysicalDeviceProperties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            final var selectedPhysicalDeviceProperties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            final var properties2 = VkPhysicalDeviceProperties2.calloc().sType$Default();
            final var limits = properties2.properties().limits();
            final var externalBufferInfo = VkPhysicalDeviceExternalBufferInfo.calloc(stack).sType$Default();
            // host buffers will never get used as anything except transfer src
            externalBufferInfo.usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            externalBufferInfo.handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT);
            final var externalBufferPropertiesPtr = VkExternalBufferProperties.calloc(stack).sType$Default();
            final var externalBufferProperties = externalBufferPropertiesPtr.externalMemoryProperties();
            @Nullable
            VkPhysicalDevice selectedPhysicalDevice = null;
            for (int i = 0; i < physicalDeviceCount; i++) {
                final var physicalDevicePtr = physicalDevices.get(i);
                final var physicalDevice = new VkPhysicalDevice(physicalDevicePtr, instance);
                
                vkGetPhysicalDeviceProperties2(physicalDevice, properties2);
                // uint32 in C++, 2^32 - 1
                if (limits.maxMemoryAllocationCount() != -1) {
                    LOGGER.info("Skipping device, not enough allocations available");
                    continue;
                }
                
                
                vkGetPhysicalDeviceExternalBufferProperties(physicalDevice, externalBufferInfo, externalBufferPropertiesPtr);
                if ((externalBufferProperties.externalMemoryFeatures() & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) == 0) {
                    LOGGER.info("Skipping device, unable to import host memory for transfer src");
                    continue;
                }
                if ((externalBufferProperties.externalMemoryFeatures() & VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT) != 0) {
                    LOGGER.info("Skipping device, dedicated allocation required for imported host memory");
                    continue;
                }
                
                vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, queueFamilyCountPtr, null);
                final var queueFamilyCount = queueFamilyCountPtr.get(0);
                final var queueFamilyProperties2 = VkQueueFamilyProperties2.calloc(queueFamilyCount, stack).sType$Default();
                vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, queueFamilyCountPtr, queueFamilyProperties2);
                boolean hasGraphicsQueue = false;
                boolean hasComputeQueue = false;
                for (int j = 0; j < queueFamilyCount; j++) {
                    if (!glfwGetPhysicalDevicePresentationSupport(instance, physicalDevice, 0)) {
                        continue;
                    }
                    final var queueFamilyProperties = queueFamilyProperties2.queueFamilyProperties();
                    // there being a graphics queue implies that there is a common graphics + compute queue
                    // and both graphics and compute queues are implicit transfer queues as well
                    if ((queueFamilyProperties.queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                        hasGraphicsQueue = true;
                    }
                    if ((queueFamilyProperties.queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                        hasComputeQueue = true;
                    }
                }
                if (!hasComputeQueue || !hasGraphicsQueue) {
                    continue;
                }
                vkGetPhysicalDeviceProperties2(physicalDevice, currentPhysicalDeviceProperties);
                final var currentDeviceType = currentPhysicalDeviceProperties.properties().deviceType();
                if (currentDeviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                    // if its discrete, its selected
                    selectedPhysicalDevice = physicalDevice;
                    break;
                }
                // this physical device will work (probably), but might be suboptimal (ie: actually the CPU)
                // if the currently selected device is better (ie: discrete vs integrated), dont use this one
                if (selectedPhysicalDevice != null) {
                    vkGetPhysicalDeviceProperties2(selectedPhysicalDevice, selectedPhysicalDeviceProperties);
                    final var selectedDeviceType = selectedPhysicalDeviceProperties.properties().deviceType();
                    if (currentDeviceType == VK_PHYSICAL_DEVICE_TYPE_OTHER) {
                        // if we have any other device selected, skip other devices
                        continue;
                    }
                    if (currentDeviceType == VK_PHYSICAL_DEVICE_TYPE_CPU) {
                        // if we have any other device selected, skip CPU devices
                        continue;
                    }
                    if (selectedDeviceType == VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU) {
                        if (currentDeviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) {
                            // prefer vGPUs over iGPUs
                            continue;
                        }
                    }
                }
                selectedPhysicalDevice = physicalDevice;
            }
            if (selectedPhysicalDevice != null) {
                // TODO: log physical device info, ie: name
                LOGGER.info("VkPhysicalDevice selected");
                return selectedPhysicalDevice;
            }
            throw new IllegalStateException("Unable to find compatible Vulkan physical device");
        }
    }
    
    private static Pair<VkDevice, List<IntReferencePair<VkQueue>>> createLogicalDeviceAndQueues(VkPhysicalDevice physicalDevice) {
        try (var stack = MemoryStack.stackPush()) {
            
            int graphicsQueueFamily = 0;
            int graphicsQueueIndex = 0;
            int computeQueueFamily = -1;
            int computeQueueIndex = 0;
            int transferQueueFamily = -1;
            int transferQueueIndex = 0;
            final var queueFamilies = new IntArrayList();
            final var queueCounts = new IntArrayList();
            
            // TODO: query and select this properly
            //       Nvidia, AMD, Intel, and Qualcomm, this will work to get the universal queue
            //       Nvidia has 16 universal queues, and others for transfer
            //       AMD has 4 compute and 3 transfer queues, besides the universal one
            //       Intel has dedicated compute and dedicated transfer queue, on windows, but only the universal on linux
            //       Qualcomm only has the one family in the first place, but three queues
            //
            //       up to 3 queues will be used
            //       main graphics as a graphics+compute queue, this is required by the spec for any graphics capable device
            //       async compute queue, as just compute required (less bits preferred)
            //       async transfer queue, as just transfer required (less bits preferred)
            //       on Intel/Linux, these will all map to the same queue
            //       on Intel/Windows, compute and transfer may be there own queues (and families), depends on underlying GPU
            //       on AMD, graphics will be the universal, compute will be an ACE, and transfer will be the DMA unit (or ACE if DMA unavailable)
            //       on Nvidia, graphics and compute will both be universal queues, and transfer will be dedicated transfer
            //       on Qualcomm/Windows, all three will be independent queues, but the same family
            //       theoretically could use more for async compute/transfer, but theres no need really
            
            queueFamilies.add(0);
            queueCounts.add(1);
            
            final var prioritiesPtr = stack.callocFloat(16);
            for (int i = 0; i < 16; i++) {
                prioritiesPtr.put(i, 1.0f);
            }
            final var queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack);
            for (int i = 0; i < 1; i++) {
                queueCreateInfos.position(i);
                queueCreateInfos.sType$Default();
                
                final var queueFamilyIndex = queueFamilies.getInt(i);
                final var queueCount = queueCounts.getInt(i);
                
                queueCreateInfos.queueFamilyIndex(queueFamilyIndex);
                prioritiesPtr.limit(Math.min(prioritiesPtr.capacity(), queueCount));
                queueCreateInfos.pQueuePriorities(prioritiesPtr);
            }
            queueCreateInfos.position(0);
            
            final var physicalDeviceFeatures10 = VkPhysicalDeviceFeatures.calloc(stack);
            final var physicalDeviceFeatures11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
            final var physicalDeviceFeatures12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            final var physicalDeviceFeatures13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
            
            physicalDeviceFeatures10.drawIndirectFirstInstance(true);
            physicalDeviceFeatures10.fillModeNonSolid(true);
            physicalDeviceFeatures10.logicOp(true);
            physicalDeviceFeatures10.multiDrawIndirect(true);
            // no support from macos, but would love to use this
            // granted, mac doesnt have 1.3 support either, so theres that
//            physicalDeviceFeatures10.sparseBinding(true);
            
            physicalDeviceFeatures11.storageBuffer16BitAccess(true);
            physicalDeviceFeatures11.uniformAndStorageBuffer16BitAccess(true);
            physicalDeviceFeatures11.shaderDrawParameters(true);
            
            physicalDeviceFeatures12.drawIndirectCount(true);
            physicalDeviceFeatures12.descriptorIndexing(true);
            physicalDeviceFeatures12.descriptorBindingUniformBufferUpdateAfterBind(true);
            physicalDeviceFeatures12.descriptorBindingSampledImageUpdateAfterBind(true);
            physicalDeviceFeatures12.descriptorBindingStorageImageUpdateAfterBind(true);
            physicalDeviceFeatures12.descriptorBindingStorageBufferUpdateAfterBind(true);
            physicalDeviceFeatures12.descriptorBindingUniformTexelBufferUpdateAfterBind(true);
            physicalDeviceFeatures12.descriptorBindingStorageTexelBufferUpdateAfterBind(true);
            physicalDeviceFeatures12.descriptorBindingUpdateUnusedWhilePending(true);
            physicalDeviceFeatures12.descriptorBindingPartiallyBound(true);
            physicalDeviceFeatures12.descriptorBindingVariableDescriptorCount(true);
            physicalDeviceFeatures12.runtimeDescriptorArray(true);
            physicalDeviceFeatures12.scalarBlockLayout(true);
            physicalDeviceFeatures12.shaderFloat16(true); // low support, 75%
            physicalDeviceFeatures12.shaderInt8(true); // low support, 92%
            physicalDeviceFeatures12.shaderStorageBufferArrayNonUniformIndexing(true);
            physicalDeviceFeatures12.shaderStorageImageArrayNonUniformIndexing(true);
            physicalDeviceFeatures12.shaderStorageTexelBufferArrayDynamicIndexing(true);
            physicalDeviceFeatures12.shaderStorageTexelBufferArrayNonUniformIndexing(true);
            physicalDeviceFeatures12.shaderUniformBufferArrayNonUniformIndexing(true);
            physicalDeviceFeatures12.shaderUniformTexelBufferArrayDynamicIndexing(true);
            physicalDeviceFeatures12.shaderUniformTexelBufferArrayNonUniformIndexing(true);
            physicalDeviceFeatures12.storageBuffer8BitAccess(true);
            physicalDeviceFeatures12.storagePushConstant8(true); // low support, 65%
            physicalDeviceFeatures12.timelineSemaphore(true);
            physicalDeviceFeatures12.uniformAndStorageBuffer8BitAccess(true);
            physicalDeviceFeatures12.uniformBufferStandardLayout(true); // low support, 80%
            physicalDeviceFeatures12.vulkanMemoryModel(true);
            
            physicalDeviceFeatures13.computeFullSubgroups(true);
            physicalDeviceFeatures13.descriptorBindingInlineUniformBlockUpdateAfterBind(true);
            physicalDeviceFeatures13.dynamicRendering(true);
            physicalDeviceFeatures13.inlineUniformBlock(true);
            physicalDeviceFeatures13.maintenance4(true);
            physicalDeviceFeatures13.privateData(true);
            physicalDeviceFeatures13.shaderTerminateInvocation(true);
            physicalDeviceFeatures13.subgroupSizeControl(true);
            physicalDeviceFeatures13.synchronization2(true);
            
            
            final var deviceCreateInfo = VkDeviceCreateInfo.calloc(stack).sType$Default();
            deviceCreateInfo.pQueueCreateInfos(queueCreateInfos);
            
            deviceCreateInfo.pEnabledFeatures(physicalDeviceFeatures10);
            deviceCreateInfo.pNext(physicalDeviceFeatures11);
            deviceCreateInfo.pNext(physicalDeviceFeatures12);
            deviceCreateInfo.pNext(physicalDeviceFeatures13);
            
            var extensions = stack.mallocPointer(requiredDeviceExtensions.size());
            for (int i = 0; i < requiredDeviceExtensions.size(); i++) {
                extensions.put(i, stack.UTF8(requiredDeviceExtensions.get(i)));
            }
            deviceCreateInfo.ppEnabledExtensionNames(extensions);
            
            final var pointerPointer = stack.mallocPointer(1);
            final var code = vkCreateDevice(physicalDevice, deviceCreateInfo, null, pointerPointer);
            if (code != VK_SUCCESS) {
                // TODO: more details on creation failure
                throw new IllegalStateException("Failed to create VkDevice " + code);
            }
            LOGGER.info("VkDevice created");
            
            final var logicalDevice = new VkDevice(pointerPointer.get(0), physicalDevice, deviceCreateInfo);
            final var queues = new ReferenceArrayList<IntReferencePair<VkQueue>>();
            vkGetDeviceQueue(logicalDevice, graphicsQueueFamily, graphicsQueueIndex, pointerPointer);
            queues.add(new IntReferenceImmutablePair<>(graphicsQueueFamily, new VkQueue(pointerPointer.get(0), logicalDevice)));
            if (computeQueueFamily != -1) {
                vkGetDeviceQueue(logicalDevice, computeQueueFamily, computeQueueIndex, pointerPointer);
                queues.add(new IntReferenceImmutablePair<>(computeQueueFamily, new VkQueue(pointerPointer.get(0), logicalDevice)));
            } else {
                // no compute queue? alias graphics queue
                queues.add(queues.get(0));
            }
            if (transferQueueFamily != -1) {
                vkGetDeviceQueue(logicalDevice, transferQueueFamily, transferQueueIndex, pointerPointer);
                queues.add(new IntReferenceImmutablePair<>(transferQueueFamily, new VkQueue(pointerPointer.get(0), logicalDevice)));
            } else {
                // no transfer queue? alias compute queue
                // note: may be the graphics queue
                queues.add(queues.get(1));
            }
            return new Pair<>(logicalDevice, queues);
        }
    }
}
