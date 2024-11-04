package dev.logix.cinnabar.internal.vulkan;

import dev.logix.cinnabar.Cinnabar;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import net.roguelogix.phosphophyllite.util.Pair;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static dev.logix.cinnabar.Cinnabar.LOGGER;
import static dev.logix.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetPhysicalDevicePresentationSupport;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceQueueFamilyProperties2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

@NonnullDefault
public class VulkanCore implements Destroyable {
    public final VkInstance vkInstance;
    private final long debugCallback;
    public final VkPhysicalDevice vkPhysicalDevice;
    
    public VulkanCore() {
        final var instanceAndDebugCallback = createVkInstance();
        vkInstance = instanceAndDebugCallback.first();
        debugCallback = instanceAndDebugCallback.second();
        vkPhysicalDevice = selectPhysicalDevice();
    }
    
    @Override
    public void destroy() {
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
            
            
            final var createInfo = VkInstanceCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            createInfo.set(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, 0, 0, appInfo, null, null);
            
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
                    
                    var layerPointers = stack.mallocPointer(2);
                    layerPointers.put(0, stack.UTF8("VK_LAYER_KHRONOS_validation"));
                    layerPointers.put(1, stack.UTF8("VK_LAYER_RENDERDOC_Capture"));
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
    
    private VkPhysicalDevice selectPhysicalDevice() {
        try (var stack = MemoryStack.stackPush()) {
            final var physicalDeviceCountPtr = stack.callocInt(1);
            vkEnumeratePhysicalDevices(vkInstance, physicalDeviceCountPtr, null);
            final var physicalDeviceCount = physicalDeviceCountPtr.get(0);
            final var physicalDevices = stack.callocPointer(physicalDeviceCount);
            vkEnumeratePhysicalDevices(vkInstance, physicalDeviceCountPtr, physicalDevices);
            
            final var queueFamilyCountPtr = stack.callocInt(1);
            final var currentPhysicalDeviceProperties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            final var selectedPhysicalDeviceProperties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            @Nullable
            VkPhysicalDevice selectedPhysicalDevice = null;
            for (int i = 0; i < physicalDeviceCount; i++) {
                final var physicalDevicePtr = physicalDevices.get(i);
                final var physicalDevice = new VkPhysicalDevice(physicalDevicePtr, vkInstance);
                
                vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, queueFamilyCountPtr, null);
                final var queueFamilyCount = queueFamilyCountPtr.get(0);
                final var queueFamilyProperties2 = VkQueueFamilyProperties2.calloc(queueFamilyCount, stack).sType$Default();
                vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, queueFamilyCountPtr, queueFamilyProperties2);
                for (int j = 0; j < queueFamilyCount; j++) {
                    if (!glfwGetPhysicalDevicePresentationSupport(vkInstance, physicalDevice, 0)) {
                        continue;
                    }
                    final var queueFamilyProperties = queueFamilyProperties2.queueFamilyProperties();
                    final var requiredFlags = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_TRANSFER_BIT;
                    if ((queueFamilyProperties.queueFlags() & requiredFlags) != requiredFlags) {
                        continue;
                    }
                    // this physical device will work, but might be suboptimal (ie: actually the CPU)
                    // if the currently selected device is better (ie: discrete vs integrated), dont use this one
                    if (selectedPhysicalDevice != null) {
                        vkGetPhysicalDeviceProperties2(physicalDevice, currentPhysicalDeviceProperties);
                        vkGetPhysicalDeviceProperties2(selectedPhysicalDevice, selectedPhysicalDeviceProperties);
                        final var currentDeviceType = currentPhysicalDeviceProperties.properties().deviceType();
                        final var selectedDeviceType = selectedPhysicalDeviceProperties.properties().deviceType();
                        if (selectedDeviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                            // if the currently selected device is a discrete card, it _will_ be selected
                            // usually, this is the primary card in a system, if there are multiple
                            return selectedPhysicalDevice;
                        }
                        if (selectedDeviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU || selectedDeviceType == VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU) {
                            if (currentDeviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                                // once again, return first discrete card
                                return physicalDevice;
                            }
                        }
                        if (currentDeviceType == VK_PHYSICAL_DEVICE_TYPE_OTHER) {
                            // if we have any other device selected, skip other devices
                            continue;
                        }
                        if (currentDeviceType == VK_PHYSICAL_DEVICE_TYPE_CPU) {
                            // if we have any other device selected, skip CPU devices
                            continue;
                        }
                        if (selectedDeviceType == VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU) {
                            if (currentDeviceType == VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU) {
                                // prefer vGPUs over iGPUs
                                continue;
                            }
                        }
                    }
                    selectedPhysicalDevice = physicalDevice;
                }
            }
            if (selectedPhysicalDevice != null) {
                return selectedPhysicalDevice;
            }
            throw new IllegalStateException("Unable to find compatible Vulkan device");
        }
    }
}
