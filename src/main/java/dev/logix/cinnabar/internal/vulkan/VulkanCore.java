package dev.logix.cinnabar.internal.vulkan;

import dev.logix.cinnabar.Cinnabar;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.roguelogix.phosphophyllite.util.Pair;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NonnullDefault;
import org.lwjgl.vulkan.*;

import static dev.logix.cinnabar.Cinnabar.LOGGER;
import static dev.logix.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

@NonnullDefault
public class VulkanCore implements Destroyable {
    public final VkInstance vkInstance;
    private final long debugCallback;
    
    public VulkanCore() {
        final var instanceAndDebugCallback = createVkInstance();
        vkInstance = instanceAndDebugCallback.first();
        debugCallback = instanceAndDebugCallback.second();
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
            
            VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo = null;
            if (Cinnabar.CONFIG.EnableValidationLayers) {
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
            
            final var glfwExtensions = glfwGetRequiredInstanceExtensions();
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
}
