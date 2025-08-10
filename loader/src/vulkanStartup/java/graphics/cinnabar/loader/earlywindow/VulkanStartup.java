package graphics.cinnabar.loader.earlywindow;

// this is one of the first classes compiled, so the error check goes here
#if !NEO && !FABRIC
#error "Unknown loader, either NEO or FABRIC should be defined"
#endif
#if NEO && FABRIC
#error "Both NEO and FABRIC cannot be defined at the same time"
#endif

#if NEO
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.neoforged.fml.loading.FMLLoader;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCString;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static graphics.cinnabar.loader.earlywindow.GLFWClassloadHelper.glfwExtGetPhysicalDevicePresentationSupport;
import static org.lwjgl.vulkan.EXTDebugMarker.VK_EXT_DEBUG_MARKER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugReport.VK_EXT_DEBUG_REPORT_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK12.*;
#endif

#if FABRIC

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCString;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static graphics.cinnabar.loader.earlywindow.GLFWClassloadHelper.glfwExtGetPhysicalDevicePresentationSupport;
import static org.lwjgl.vulkan.EXTDebugMarker.VK_EXT_DEBUG_MARKER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugReport.VK_EXT_DEBUG_REPORT_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK12.*;
#endif

public class VulkanStartup {
    
    public static Instance createVkInstance(boolean validationLayers, boolean enableMesaOverlay, @Nullable VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo) {
        
        #if FABRIC
        VulkanStartup.Config.mcVersionString = SharedConstants.getCurrentVersion().name();
        VulkanStartup.Config.cinnabarVersionString = FabricLoader.getInstance().getModContainer("cinnabar").get().getMetadata().getVersion().getFriendlyString();
        #endif
        
        try (var stack = MemoryStack.stackPush()) {
            final var appInfo = VkApplicationInfo.calloc(stack);
            final var appName = stack.UTF8("Minecraft");
            final var engineName = stack.UTF8("Cinnabar");
            final var mcVersionChunks = Config.mcVersionString.split("-")[0].split("\\.");
            final int appVersion = VK_MAKE_VERSION(Integer.parseInt(mcVersionChunks[0]), Integer.parseInt(mcVersionChunks[1]), Integer.parseInt(mcVersionChunks[2]));
            final String modVersionString = Config.cinnabarVersionString;
            final var modVersionChunks = modVersionString.split("-")[0].split("\\.");
            final int engineVersion = VK_MAKE_VERSION(Integer.parseInt(modVersionChunks[0]), Integer.parseInt(modVersionChunks[1]), Integer.parseInt(modVersionChunks[2]));
            appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
            appInfo.pApplicationName(appName);
            appInfo.applicationVersion(appVersion);
            appInfo.pEngineName(engineName);
            appInfo.engineVersion(engineVersion);
            appInfo.apiVersion(VK_API_VERSION_1_2);
            
            final var enabledInstanceExtensions = new ReferenceArrayList<String>();
            final var enabledLayerNames = new ReferenceArrayList<String>();
            
            
            final var createInfo = VkInstanceCreateInfo.calloc(stack).sType$Default();
            createInfo.pApplicationInfo(appInfo);
            #if NEO
            if (FMLLoader.isProduction() && validationLayers) {
            #elif FABRIC
            if (!FabricLoader.getInstance().isDevelopmentEnvironment() && validationLayers) {
            #endif
                TinyFileDialogs.tinyfd_messageBox("Minecraft: Cinnabar", """
                        Debug mode enabled
                        Enabling this option significantly hurts performance, and may result in crashes due to debug checks
                        """, "ok", "warn", false);
            }
            final var layerCountPtr = stack.ints(0);
            vkEnumerateInstanceLayerProperties(layerCountPtr, null);
            final var layerCount = layerCountPtr.get(0);
            final var layerProperties = VkLayerProperties.calloc(layerCount, stack);
            vkEnumerateInstanceLayerProperties(layerCountPtr, layerProperties);
            
            final var extensionCount = stack.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((String) null, extensionCount, null);
            final var extensionProperties = VkExtensionProperties.calloc(extensionCount.get(0), stack);
            vkEnumerateInstanceExtensionProperties((String) null, extensionCount, extensionProperties);
            
            if (validationLayers) {
                LOGGER.info("Vulkan validation layers requested");
                boolean hasKHRValidation = false;
                for (int i = 0; i < layerProperties.capacity(); i++) {
                    layerProperties.position(i);
                    if (layerProperties.layerNameString().equals("VK_LAYER_KHRONOS_validation")) {
                        hasKHRValidation = true;
                        break;
                    }
                }
                boolean hasEXTDebug = false;
                for (int i = 0; i < extensionProperties.capacity(); i++) {
                    extensionProperties.position(i);
                    if (extensionProperties.extensionNameString().equals(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                        hasEXTDebug = true;
                        break;
                    }
                }
                if (!hasKHRValidation) {
                    LOGGER.error("Unable to initialize validation layers, are they installed?");
                    validationLayers = false;
                } else if (!hasEXTDebug) {
                    LOGGER.error("Unable to initialize validation layers, VK_EXT_debug_utils not present");
                    validationLayers = false;
                } else {
                    #if NEO
                    if (FMLLoader.isProduction()) {
                    #elif FABRIC
                    if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
                    #endif
                        TinyFileDialogs.tinyfd_messageBox("Minecraft: Cinnabar", """
                                Vulkan validation layers enabled
                                Enabling this options significantly hurts performance
                                """, "ok", "warn", false);
                    }
                    LOGGER.warn("Vulkan validation layers enabled, performance may suffer!");
                    
                    enabledLayerNames.add("VK_LAYER_KHRONOS_validation");
                    enabledInstanceExtensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
                    
                    if (debugCreateInfo != null) {
                        createInfo.pNext(debugCreateInfo.address());
                    }
                }
            }
            if (enableMesaOverlay && System.getenv().get("ENABLE_VULKAN_RENDERDOC_CAPTURE") == null) {
                for (int i = 0; i < layerCount; i++) {
                    layerProperties.position(i);
                    // overlay requested, renderdoc isn't attached, and we have the mesa overlay, enable it
                    if (layerProperties.layerNameString().equals("VK_LAYER_MESA_overlay")) {
                        enabledLayerNames.add("VK_LAYER_MESA_overlay");
                        break;
                    }
                }
            }
            
            for (int i = 0; i < optionalInstanceExtensions.size(); i++) {
                final var extensionName = optionalInstanceExtensions.get(i);
                boolean hasExtension = false;
                for (int j = 0; j < extensionProperties.capacity(); j++) {
                    extensionProperties.position(j);
                    final var currentExtension = extensionProperties.extensionNameString();
                    if (currentExtension.equals(extensionName)) {
                        hasExtension = true;
                        break;
                    }
                }
                if (!hasExtension) {
                    LOGGER.info("Missing optional instance extension {}", extensionName);
                    continue;
                }
                enabledInstanceExtensions.add(extensionName);
            }
            
            var layerPointers = stack.mallocPointer(enabledLayerNames.size());
            for (int i = 0; i < enabledLayerNames.size(); i++) {
                layerPointers.put(i, stack.UTF8(enabledLayerNames.get(i)));
            }
            createInfo.ppEnabledLayerNames(layerPointers);
            
            
            @Nullable final var glfwExtensions = GLFWClassloadHelper.glfwGetRequiredInstanceExtensions();
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
            
            @Nullable final var allocationCallbacks = Configuration.DEBUG_MEMORY_ALLOCATOR.get(false) ? callbacks() : null;
            final var instancePointer = stack.mallocPointer(1);
            final var instanceCreateCode = vkCreateInstance(createInfo, allocationCallbacks, instancePointer);
            if (instanceCreateCode != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan instance");
            }
            final var vkInstance = new VkInstance(instancePointer.get(0), createInfo);
            LOGGER.info("VkInstance created");
            
            final long debugCallback;
            if (validationLayers && debugCreateInfo != null) {
                var lp = stack.mallocLong(1);
                final var debugCallbackCode = vkCreateDebugUtilsMessengerEXT(vkInstance, debugCreateInfo, null, lp);
                if (debugCallbackCode != VK_SUCCESS) {
                    vkDestroyInstance(vkInstance, null);
                    throw new RuntimeException("Failed to create Vulkan instance");
                }
                debugCallback = lp.get();
            } else {
                debugCallback = -1;
            }
            
            enabledLayerNames.addAll(enabledInstanceExtensions);
            return new Instance(vkInstance, debugCallback, Collections.unmodifiableList(enabledLayerNames));
        }
    }
    
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public record Instance(VkInstance instance, long debugCallback, List<String> enabledInsanceExtensions) {
        public void destroy() {
            if (debugCallback != -1) {
                vkDestroyDebugUtilsMessengerEXT(instance, debugCallback, null);
            }
            vkDestroyInstance(instance, null);
        }
    }
    
    public record Queue(int queueFamily, VkQueue queue) {
    }
    
    public record Device(VkDevice device, List<Queue> queues, List<String> enabledDeviceExtensions) {
        public void destroy() {
            vkDestroyDevice(device, null);
        }
    }
    
    private static final List<String> optionalInstanceExtensions = List.of(
            VK_EXT_DEBUG_REPORT_EXTENSION_NAME
    );
    
    private static final List<String> requiredDeviceExtensions = List.of(
            VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME,
            VK_KHR_SWAPCHAIN_EXTENSION_NAME
    );
    
    private static final List<Pair<String, List<String>>> optionalDeviceExtensions = List.of(
            new ObjectObjectImmutablePair<>(VK_EXT_DEBUG_MARKER_EXTENSION_NAME, List.of(VK_EXT_DEBUG_REPORT_EXTENSION_NAME)),
            new ObjectObjectImmutablePair<>(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME, List.of())
    );
    
    private static final boolean supported = ((Supplier<Boolean>) () -> {
        LOGGER.info("Querying Vulkan support");
        try {
            final var instanceRecord = createVkInstance(false, false, null);
            try {
                selectPhysicalDevice(instanceRecord.instance, false, -1, instanceRecord.enabledInsanceExtensions());
                // if we made it here, there is a supported device, so it will be used
                LOGGER.info("Compatible card found, using Vulkan");
                return true;
            } finally {
                instanceRecord.destroy();
            }
        } catch (RuntimeException ignored) {
            ignored.printStackTrace();
        }
        LOGGER.info("No compatible card found, using OpenGL");
        return false;
    }).get();
    
    public static boolean isSupported() {
        return supported;
    }
    
    private static VkAllocationCallbacks callbacks() {
        final var callbacks = VkAllocationCallbacks.calloc();
        callbacks.pfnAllocation(new VkAllocationFunction() {
            @Override
            public long invoke(long pUserData, long size, long alignment, int allocationScope) {
                return MemoryUtil.nmemAlignedAlloc(alignment, size);
            }
        });
        callbacks.pfnReallocation(new VkReallocationFunction() {
            @Override
            public long invoke(long pUserData, long pOriginal, long size, long alignment, int allocationScope) {
                final var newAlloc = MemoryUtil.nmemRealloc(pOriginal, size);
                if ((newAlloc & (alignment - 1)) == 0) {
                    return newAlloc;
                }
                final var alignedAlloc = MemoryUtil.nmemAlignedAlloc(alignment, size);
                LibCString.nmemcpy(alignedAlloc, newAlloc, size);
                MemoryUtil.nmemFree(newAlloc);
                return alignedAlloc;
            }
        });
        callbacks.pfnFree(new VkFreeFunction() {
            @Override
            public void invoke(long pUserData, long pMemory) {
                MemoryUtil.nmemFree(pMemory);
            }
        });
        
        return callbacks;
    }
    
    public static class Config {
        public static String mcVersionString = null;
        public static String cinnabarVersionString = null;
    }
    
    public static VkPhysicalDevice selectPhysicalDevice(VkInstance vkInstance, boolean manualDeviceSelection, int forcedDeviceIndex, List<String> enabledLayersAndInstanceExtensions) {
        try (var stack = MemoryStack.stackPush()) {
            final var physicalDeviceCountPtr = stack.callocInt(1);
            vkEnumeratePhysicalDevices(vkInstance, physicalDeviceCountPtr, null);
            final var physicalDeviceCount = physicalDeviceCountPtr.get(0);
            final var physicalDevices = stack.callocPointer(physicalDeviceCount);
            vkEnumeratePhysicalDevices(vkInstance, physicalDeviceCountPtr, physicalDevices);
            
            final var queueFamilyCountPtr = stack.callocInt(1);
            final var selectedPhysicalDeviceProperties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            final var properties2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            final var deviceProperties = properties2.properties();
            
            final var physicalDeviceFeatures = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            final var physicalDeviceFeatures10 = physicalDeviceFeatures.features();
            final var physicalDeviceFeatures11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
            final var physicalDeviceFeatures12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            final var sync2Features = VkPhysicalDeviceSynchronization2FeaturesKHR.calloc(stack).sType$Default();
            physicalDeviceFeatures.pNext(physicalDeviceFeatures11);
            physicalDeviceFeatures.pNext(physicalDeviceFeatures12);
            physicalDeviceFeatures.pNext(sync2Features);
            
            @Nullable
            VkPhysicalDevice selectedPhysicalDevice = null;
            for (int i = 0; i < physicalDeviceCount; i++) {
                if (forcedDeviceIndex != -1 && i != forcedDeviceIndex) {
                    LOGGER.info("Skipping device index {} as specific device index {} requested", i, forcedDeviceIndex);
                    continue;
                }
                final var physicalDevicePtr = physicalDevices.get(i);
                final var physicalDevice = new VkPhysicalDevice(physicalDevicePtr, vkInstance);
                
                vkGetPhysicalDeviceProperties2(physicalDevice, properties2);
                
                LOGGER.info("Considering device {}", deviceProperties.deviceNameString());
                
                if (deviceProperties.apiVersion() < VK_API_VERSION_1_2) {
                    LOGGER.info("Skipping device, version too low");
                    continue;
                }
                
                final var extensionCountPtr = stack.callocInt(1);
                int totalExtensionProperties = 0;
                vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, extensionCountPtr, null);
                totalExtensionProperties += extensionCountPtr.get(0);
                for (int j = 0; j < enabledLayersAndInstanceExtensions.size(); j++) {
                    vkEnumerateDeviceExtensionProperties(physicalDevice, enabledLayersAndInstanceExtensions.get(j), extensionCountPtr, null);
                    totalExtensionProperties += extensionCountPtr.get(0);
                }
                final var extensionProperties = VkExtensionProperties.calloc(totalExtensionProperties, stack);
                vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, extensionCountPtr, null);
                extensionProperties.limit(extensionProperties.position() + extensionCountPtr.get(0));
                vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, extensionCountPtr, extensionProperties);
                extensionProperties.position(extensionProperties.position() + extensionCountPtr.get(0));
                for (int j = 0; j < enabledLayersAndInstanceExtensions.size(); j++) {
                    vkEnumerateDeviceExtensionProperties(physicalDevice, enabledLayersAndInstanceExtensions.get(j), extensionCountPtr, null);
                    extensionProperties.limit(extensionProperties.position() + extensionCountPtr.get(0));
                    vkEnumerateDeviceExtensionProperties(physicalDevice, enabledLayersAndInstanceExtensions.get(j), extensionCountPtr, extensionProperties);
                    extensionProperties.position(extensionProperties.position() + extensionCountPtr.get(0));
                }
                extensionProperties.position(0);
                
                boolean hasAllRequiredExtensions = true;
                for (int j = 0; j < requiredDeviceExtensions.size(); j++) {
                    final var extensionName = requiredDeviceExtensions.get(j);
                    boolean hasExtension = false;
                    for (int k = 0; k < totalExtensionProperties; k++) {
                        extensionProperties.position(k);
                        final var currentExtension = extensionProperties.extensionNameString();
                        if (currentExtension.equals(extensionName)) {
                            hasExtension = true;
                            break;
                        }
                    }
                    if (!hasExtension) {
                        LOGGER.info("Missing required extension ({})", extensionName);
                        hasAllRequiredExtensions = false;
                    }
                }
                if (!hasAllRequiredExtensions) {
                    LOGGER.info("Skipping device, missing required extension");
                    continue;
                }
                
                vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, queueFamilyCountPtr, null);
                final var queueFamilyCount = queueFamilyCountPtr.get(0);
                final var queueFamilyProperties2 = VkQueueFamilyProperties2.calloc(queueFamilyCount, stack);
                for (int j = 0; j < queueFamilyCount; j++) {
                    queueFamilyProperties2.position(j).sType$Default();
                }
                queueFamilyProperties2.position(0);
                vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, queueFamilyCountPtr, queueFamilyProperties2);
                boolean hasGraphicsQueue = false;
                boolean hasDedicatedComputeQueue = false;
                boolean hasDedicatedTransferQueue = false;
                for (int j = 0; j < queueFamilyCount; j++) {
                    if (!glfwExtGetPhysicalDevicePresentationSupport(vkInstance, physicalDevice, 0)) {
                        continue;
                    }
                    int familyUsedQueues = 0;
                    final var queueFamilyProperties = queueFamilyProperties2.queueFamilyProperties();
                    // look for the spec guaranteed combined graphics/compute queue
                    // both graphics and compute queues are implicit transfer queues
                    // graphics queue must also support present (all do in reality)
                    final var graphicsQueueBits = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
                    if ((queueFamilyProperties.queueFlags() & graphicsQueueBits) == graphicsQueueBits && glfwExtGetPhysicalDevicePresentationSupport(vkInstance, physicalDevice, j)) {
                        hasGraphicsQueue = true;
                        familyUsedQueues++;
                    }
                    if (queueFamilyProperties.queueCount() <= familyUsedQueues) {
                        continue;
                    }
                    if ((queueFamilyProperties.queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                        hasDedicatedComputeQueue = true;
                        familyUsedQueues++;
                    }
                    if (queueFamilyProperties.queueCount() <= familyUsedQueues) {
                        continue;
                    }
                    // graphics and compute imply transfer
                    // actual queue selection will pick the best queue for transfer
                    if ((queueFamilyProperties.queueFlags() & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT | VK_QUEUE_TRANSFER_BIT)) != 0) {
                        hasDedicatedTransferQueue = true;
                    }
                }
                if (!hasGraphicsQueue) {
                    LOGGER.info("Skipping device, could not find suitable graphics queue");
                    continue;
                }
                
                vkGetPhysicalDeviceFeatures2(physicalDevice, physicalDeviceFeatures);
                if (!hasAllRequiredFeatures(physicalDeviceFeatures10, physicalDeviceFeatures11, physicalDeviceFeatures12, sync2Features)) {
                    LOGGER.info("Skipping device, missing required features");
                    continue;
                }
                
                final var currentDeviceType = deviceProperties.deviceType();
                if (manualDeviceSelection && forcedDeviceIndex == -1) {
                    
                    final var deviceName = deviceProperties.deviceNameString();
                    final var deviceTypeStr = switch (deviceProperties.deviceType()) {
                        case VK_PHYSICAL_DEVICE_TYPE_OTHER -> "Unknown";
                        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "Integrated GPU";
                        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "Discrete GPU";
                        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "Virtual GPU";
                        case VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU (Software)";
                        default -> throw new IllegalStateException("Unexpected value: " + deviceProperties.deviceType());
                    };
                    final var APIVersionEncoded = deviceProperties.apiVersion();
                    final var driverVersionEncoded = deviceProperties.driverVersion();
                    
                    final var baseMessageStr = """
                            Manual Vulkan device selection
                            
                            Device Index: %d/%d
                            Device name: %s
                            Device type: %s
                            Has dedicated compute queue: %b
                            Has dedicated transfer queue: %b
                            API version: %d.%d.%d
                            Driver version: %d.%d.%d
                            
                            Select this device?
                            """;
                    final var formattedMessageStr = baseMessageStr.formatted(
                            i + 1, physicalDeviceCount,
                            deviceName,
                            deviceTypeStr,
                            hasDedicatedComputeQueue,
                            hasDedicatedTransferQueue,
                            VK_VERSION_MAJOR(APIVersionEncoded), VK_VERSION_MINOR(APIVersionEncoded), VK_VERSION_PATCH(APIVersionEncoded),
                            VK_VERSION_MAJOR(driverVersionEncoded), VK_VERSION_MINOR(driverVersionEncoded), VK_VERSION_PATCH(driverVersionEncoded));
                    final var result = TinyFileDialogs.tinyfd_messageBox("Minecraft: Cinnabar", formattedMessageStr, "yesno", "info", true);
                    if (result) {
                        LOGGER.info("Device {} selected by user", deviceName);
                        selectedPhysicalDevice = physicalDevice;
                        break;
                    }
                    LOGGER.info("Device {} rejected by user", deviceName);
                    if (i == physicalDeviceCount - 1) {
                        LOGGER.info("All vulkan devices rejected by user");
                        TinyFileDialogs.tinyfd_messageBox("Minecraft: Cinnabar", """
                                Manual Vulkan device selection
                                
                                All vulkan devices rejected, exiting.
                                """, "ok", "info", true);
                        System.exit(1);
                    }
                } else {
                    if (currentDeviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                        // if its discrete, its selected
                        selectedPhysicalDevice = physicalDevice;
                        break;
                    }
                    // this physical device will work (probably), but might be suboptimal (ie: actually the CPU)
                    // if the currently selected device is better (ie: discrete vs integrated), don't use this one
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
            }
            if (selectedPhysicalDevice == null) {
                throw new IllegalStateException("Failed to select VkPhysicalDevice");
            }
            vkGetPhysicalDeviceProperties2(selectedPhysicalDevice, properties2);
            LOGGER.info("VkPhysicalDevice selected!");
            final var APIVersionEncoded = deviceProperties.apiVersion();
            final var driverVersionEncoded = deviceProperties.driverVersion();
            LOGGER.info("Device name: {}", deviceProperties.deviceNameString());
            LOGGER.info("Device API version: {}.{}.{}", VK_VERSION_MAJOR(APIVersionEncoded), VK_VERSION_MINOR(APIVersionEncoded), VK_VERSION_PATCH(APIVersionEncoded));
            LOGGER.info("Device driver version: {}.{}.{}", VK_VERSION_MAJOR(driverVersionEncoded), VK_VERSION_MINOR(driverVersionEncoded), VK_VERSION_PATCH(driverVersionEncoded));
            return selectedPhysicalDevice;
        }
    }
    
    private static void logMissingFeature(String featureName) {
        LOGGER.info("Skipping device, missing required feature {}", featureName);
    }
    
    private static boolean hasAllRequiredFeatures(
            VkPhysicalDeviceFeatures physicalDeviceFeatures10,
            VkPhysicalDeviceVulkan11Features physicalDeviceFeatures11,
            VkPhysicalDeviceVulkan12Features physicalDeviceFeatures12,
            VkPhysicalDeviceSynchronization2FeaturesKHR sync2Features
    ) {
        boolean hasAllFeatures = true;
        
        if (!physicalDeviceFeatures10.drawIndirectFirstInstance()) {
            logMissingFeature("drawIndirectFirstInstance");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures10.fillModeNonSolid()) {
            logMissingFeature("fillModeNonSolid");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures10.multiDrawIndirect()) {
            logMissingFeature("multiDrawIndirect");
            hasAllFeatures = false;
        }
        
        if (!physicalDeviceFeatures11.shaderDrawParameters()) {
            logMissingFeature("shaderDrawParameters");
            hasAllFeatures = false;
        }
        
        if (!physicalDeviceFeatures12.timelineSemaphore()) {
            logMissingFeature("timelineSemaphore");
            hasAllFeatures = false;
        }
        
        if (!sync2Features.synchronization2()) {
            logMissingFeature("synchronization2");
            hasAllFeatures = false;
        }
        
        return hasAllFeatures;
    }
    
    public static Device createLogicalDeviceAndQueues(VkInstance vkInstance, VkPhysicalDevice vkPhysicalDevice, List<String> enabledLayersAndInstanceExtensions) {
        try (var stack = MemoryStack.stackPush()) {
            
            int graphicsQueueFamily = -1;
            int graphicsQueueIndex = 0;
            int computeQueueFamily = -1;
            int computeQueueFamilyBits = -1;
            int computeQueueIndex = 0;
            int transferQueueFamily = -1;
            int transferQueueFamilyBits = -1;
            int transferQueueIndex = 0;
            final var queueFamilies = new IntArrayList();
            final var queueCounts = new IntArrayList();
            /*
             * up to 3 queues will be used
             * main graphics as a graphics+compute queue, this is required by the spec for any graphics capable device
             * async compute queue, as just compute required (less bits preferred)
             * async transfer queue, as just transfer required (less bits preferred)
             * on Intel/Linux, these will all map to the same queue
             * on Intel/Windows, compute and transfer may be their own queues (and families), depends on underlying GPU
             * on AMD, graphics will be the universal, compute will be an ACE, and transfer will be the DMA unit (or ACE if DMA unavailable)
             * on Nvidia, graphics and compute will both be universal queues, and transfer will be dedicated transfer
             * on Qualcomm/Windows, all three will be independent queues, but the same family
             * theoretically could use more for async compute/transfer, but theres no need really
             */
            {
                final var queueFamilyCountPtr = stack.callocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties2(vkPhysicalDevice, queueFamilyCountPtr, null);
                final var queueFamilyCount = queueFamilyCountPtr.get(0);
                final var queueFamilyProperties2 = VkQueueFamilyProperties2.calloc(queueFamilyCount, stack);
                for (int j = 0; j < queueFamilyCount; j++) {
                    queueFamilyProperties2.position(j).sType$Default();
                }
                queueFamilyProperties2.position(0);
                vkGetPhysicalDeviceQueueFamilyProperties2(vkPhysicalDevice, queueFamilyCountPtr, queueFamilyProperties2);
                for (int j = 0; j < queueFamilyCount; j++) {
                    if (!glfwExtGetPhysicalDevicePresentationSupport(vkInstance, vkPhysicalDevice, j)) {
                        continue;
                    }
                    int familyUsedQueues = 0;
                    queueFamilyProperties2.position(j);
                    final var queueFamilyProperties = queueFamilyProperties2.queueFamilyProperties();
                    // look for the spec guaranteed combined graphics/compute queue
                    // both graphics and compute queues are implicit transfer queues
                    // graphics queue must also support present (all do in reality)
                    final var graphicsQueueBits = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
                    if (graphicsQueueFamily == -1 && (queueFamilyProperties.queueFlags() & graphicsQueueBits) == graphicsQueueBits && glfwExtGetPhysicalDevicePresentationSupport(vkInstance, vkPhysicalDevice, j)) {
                        graphicsQueueFamily = j;
                        familyUsedQueues++;
                    }
                    if (queueFamilyProperties.queueCount() <= familyUsedQueues) {
                        continue;
                    }
                    if ((queueFamilyProperties.queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                        // pick the most specific queue family available
                        if (computeQueueFamily == -1 || Integer.bitCount(queueFamilyProperties.queueFlags()) < Integer.bitCount(computeQueueFamilyBits)) {
                            computeQueueFamily = j;
                            computeQueueFamilyBits = queueFamilyProperties.queueFlags();
                            familyUsedQueues++;
                        }
                    }
                    if (queueFamilyProperties.queueCount() <= familyUsedQueues) {
                        continue;
                    }
                    // graphics and compute imply transfer
                    if ((queueFamilyProperties.queueFlags() & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT | VK_QUEUE_TRANSFER_BIT)) != 0) {
                        // pick the most specific queue family available, generally graphics/compute queues also specify transfer, so this should pick one without that specified
                        if (transferQueueFamily == -1 || Integer.bitCount(queueFamilyProperties.queueFlags()) < Integer.bitCount(transferQueueFamilyBits)) {
                            transferQueueFamily = j;
                            transferQueueFamilyBits = queueFamilyProperties.queueFlags();
                            familyUsedQueues++;
                        }
                    }
                }
                
                queueFamilies.add(graphicsQueueFamily);
                queueCounts.add(1);
                if (queueFamilies.contains(computeQueueFamily)) {
                    final var queueCreateIndex = queueFamilies.indexOf(computeQueueFamily);
                    final var queueIndex = queueCounts.getInt(queueCreateIndex);
                    computeQueueIndex = queueIndex;
                    queueCounts.set(queueCreateIndex, queueIndex + 1);
                } else if (computeQueueFamily != -1) {
                    queueFamilies.add(computeQueueFamily);
                    queueCounts.add(1);
                }
                if (queueFamilies.contains(transferQueueFamily)) {
                    final var queueCreateIndex = queueFamilies.indexOf(transferQueueFamily);
                    final var queueIndex = queueCounts.getInt(queueCreateIndex);
                    transferQueueIndex = queueIndex;
                    queueCounts.set(queueCreateIndex, queueIndex + 1);
                } else if (transferQueueFamily != -1) {
                    queueFamilies.add(transferQueueFamily);
                    queueCounts.add(1);
                }
            }
            
            final var prioritiesPtr = stack.callocFloat(16);
            for (int i = 0; i < 16; i++) {
                prioritiesPtr.put(i, 1.0f);
            }
            final var queueCreateInfos = VkDeviceQueueCreateInfo.calloc(queueCounts.size(), stack);
            for (int i = 0; i < queueCounts.size(); i++) {
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
            final var sync2Features = VkPhysicalDeviceSynchronization2FeaturesKHR.calloc(stack).sType$Default();
            
            physicalDeviceFeatures10.drawIndirectFirstInstance(true);
            physicalDeviceFeatures10.fillModeNonSolid(true);
            physicalDeviceFeatures10.multiDrawIndirect(true);
            
            physicalDeviceFeatures11.shaderDrawParameters(true);
            
            physicalDeviceFeatures12.timelineSemaphore(true);
            
            sync2Features.synchronization2(true);
            
            final var deviceCreateInfo = VkDeviceCreateInfo.calloc(stack).sType$Default();
            deviceCreateInfo.pQueueCreateInfos(queueCreateInfos);
            deviceCreateInfo.pEnabledFeatures(physicalDeviceFeatures10);
            deviceCreateInfo.pNext(physicalDeviceFeatures11);
            deviceCreateInfo.pNext(physicalDeviceFeatures12);
            deviceCreateInfo.pNext(sync2Features);
            
            final var extensionCountPtr = stack.callocInt(1);
            int totalExtensionProperties = 0;
            vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, (String) null, extensionCountPtr, null);
            totalExtensionProperties += extensionCountPtr.get(0);
            for (int i = 0; i < enabledLayersAndInstanceExtensions.size(); i++) {
                vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, enabledLayersAndInstanceExtensions.get(i), extensionCountPtr, null);
                totalExtensionProperties += extensionCountPtr.get(0);
            }
            final var extensionProperties = VkExtensionProperties.calloc(totalExtensionProperties, stack);
            vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, (String) null, extensionCountPtr, null);
            extensionProperties.limit(extensionProperties.position() + extensionCountPtr.get(0));
            vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, (String) null, extensionCountPtr, extensionProperties);
            extensionProperties.position(extensionProperties.position() + extensionCountPtr.get(0));
            for (int i = 0; i < enabledLayersAndInstanceExtensions.size(); i++) {
                vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, enabledLayersAndInstanceExtensions.get(i), extensionCountPtr, null);
                extensionProperties.limit(extensionProperties.position() + extensionCountPtr.get(0));
                vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, enabledLayersAndInstanceExtensions.get(i), extensionCountPtr, extensionProperties);
                extensionProperties.position(extensionProperties.position() + extensionCountPtr.get(0));
            }
            extensionProperties.position(0);
            
            LOGGER.debug("Listing device extensions");
            for (int j = 0; j < totalExtensionProperties; j++) {
                extensionProperties.position(j);
                final var currentExtension = extensionProperties.extensionNameString();
                LOGGER.debug(currentExtension);
            }
            
            var enabledExtensions = new ObjectArrayList<String>();
            for (int i = 0; i < requiredDeviceExtensions.size(); i++) {
                final var extensionName = requiredDeviceExtensions.get(i);
                boolean hasExtension = false;
                for (int j = 0; j < totalExtensionProperties; j++) {
                    extensionProperties.position(j);
                    final var currentExtension = extensionProperties.extensionNameString();
                    if (currentExtension.equals(extensionName)) {
                        hasExtension = true;
                        break;
                    }
                }
                if (!hasExtension) {
                    throw new IllegalStateException("Missing required device extension " + extensionName);
                }
                enabledExtensions.add(extensionName);
            }
            for (int i = 0; i < optionalDeviceExtensions.size(); i++) {
                final var optionalExtension = optionalDeviceExtensions.get(i);
                final var extensionName = optionalExtension.first();
                final var dependencies = optionalExtension.second();
                boolean missingDependency = false;
                for (String dependency : dependencies) {
                    if (enabledExtensions.contains(dependency)) {
                        continue;
                    }
                    if (enabledLayersAndInstanceExtensions.contains(dependency)) {
                        continue;
                    }
                    LOGGER.info("Optional device extension {} missing dependency {}", extensionName, dependency);
                    missingDependency = true;
                }
                if (missingDependency) {
                    continue;
                }
                boolean hasExtension = false;
                for (int j = 0; j < totalExtensionProperties; j++) {
                    extensionProperties.position(j);
                    final var currentExtension = extensionProperties.extensionNameString();
                    if (currentExtension.equals(extensionName)) {
                        hasExtension = true;
                        break;
                    }
                }
                if (!hasExtension) {
                    LOGGER.info("Missing optional device extension {}", extensionName);
                    continue;
                }
                enabledExtensions.add(extensionName);
            }
            var enabledExtensionsPtr = stack.mallocPointer(enabledExtensions.size());
            for (int i = 0; i < enabledExtensions.size(); i++) {
                enabledExtensionsPtr.put(i, stack.UTF8(enabledExtensions.get(i)));
            }
            deviceCreateInfo.ppEnabledExtensionNames(enabledExtensionsPtr);
            
            final var pointerPointer = stack.mallocPointer(1);
            final var deviceCreateCode = vkCreateDevice(vkPhysicalDevice, deviceCreateInfo, null, pointerPointer);
            if (deviceCreateCode != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan device");
            }
            LOGGER.info("VkDevice created");
            
            final var logicalDevice = new VkDevice(pointerPointer.get(0), vkPhysicalDevice, deviceCreateInfo);
            final var queues = new ReferenceArrayList<Queue>();
            vkGetDeviceQueue(logicalDevice, graphicsQueueFamily, graphicsQueueIndex, pointerPointer);
            queues.add(new Queue(graphicsQueueFamily, new VkQueue(pointerPointer.get(0), logicalDevice)));
            if (computeQueueFamily != -1) {
                vkGetDeviceQueue(logicalDevice, computeQueueFamily, computeQueueIndex, pointerPointer);
                queues.add(new Queue(computeQueueFamily, new VkQueue(pointerPointer.get(0), logicalDevice)));
            } else {
                // no compute queue? alias graphics queue
                queues.add(queues.getFirst());
            }
            if (transferQueueFamily != -1) {
                vkGetDeviceQueue(logicalDevice, transferQueueFamily, transferQueueIndex, pointerPointer);
                queues.add(new Queue(transferQueueFamily, new VkQueue(pointerPointer.get(0), logicalDevice)));
            } else {
                // no transfer queue? alias compute queue
                // note: may be the graphics queue
                queues.add(queues.get(1));
            }
            return new Device(logicalDevice, queues, Collections.unmodifiableList(enabledExtensions));
        }
    }
}
