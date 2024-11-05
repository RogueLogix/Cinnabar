package dev.logix.cinnabar.internal.vulkan;

import dev.logix.cinnabar.Cinnabar;
import it.unimi.dsi.fastutil.ints.Int2ReferenceArrayMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import net.roguelogix.phosphophyllite.util.Pair;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.List;

import static dev.logix.cinnabar.Cinnabar.LOGGER;
import static dev.logix.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetPhysicalDevicePresentationSupport;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceQueueFamilyProperties2;
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
    
    public VulkanCore() {
        final var instanceAndDebugCallback = createVkInstance();
        vkInstance = instanceAndDebugCallback.first();
        debugCallback = instanceAndDebugCallback.second();
        vkPhysicalDevice = selectPhysicalDevice(vkInstance);
        try {
            final var deviceAndQueues = createLogicalDeviceAndQueues(vkPhysicalDevice);
            vkLogicalDevice = deviceAndQueues.first();
        } catch (Exception e) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
            vkDestroyInstance(vkInstance, null);
            throw e;
        }
        properties2 = VkPhysicalDeviceProperties2.calloc().sType$Default();
        limits = properties2.properties().limits();
        vkGetPhysicalDeviceProperties2(vkPhysicalDevice, properties2);
    }
    
    @Override
    public void destroy() {
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
            @Nullable
            VkPhysicalDevice selectedPhysicalDevice = null;
            for (int i = 0; i < physicalDeviceCount; i++) {
                final var physicalDevicePtr = physicalDevices.get(i);
                final var physicalDevice = new VkPhysicalDevice(physicalDevicePtr, instance);
                
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
    
    private static Pair<VkDevice, Int2ReferenceArrayMap<List<VkQueue>>> createLogicalDeviceAndQueues(VkPhysicalDevice physicalDevice) {
        try (var stack = MemoryStack.stackPush()) {
            final var queueFamilies = new IntArrayList();
            final var queueCounts = new IntArrayList();
            
            // TODO: query and select this properly
            //       Nvidia, AMD, Intel, and Qualcomm, this will work to get the universal queue
            //       Nvidia has 16 universal queues, and others for transfer
            //       AMD has 4 compute and 3 transfer queues, besides the universal one
            //       Intel has dedicated compute and dedicated transfer queue, on windows, but only the universal on linux
            //       Qualcomm only has the one family in the first place, but three queues
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
            final var queues = new Int2ReferenceArrayMap<List<VkQueue>>();
            for (int i = 0; i < queueFamilies.size(); i++) {
                final var queueFamilyIndex = queueFamilies.getInt(i);
                final var queueCount = queueCounts.getInt(i);
                final var familyQueues = new ReferenceArrayList<VkQueue>();
                for (int j = 0; j < queueCount; j++) {
                    vkGetDeviceQueue(logicalDevice, queueFamilyIndex, j, pointerPointer);
                    familyQueues.add(new VkQueue(pointerPointer.get(0), logicalDevice));
                }
                queues.put(queueFamilyIndex, familyQueues);
            }
            return new Pair<>(logicalDevice, queues);
        }
    }
}
