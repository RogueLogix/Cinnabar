package graphics.cinnabar.core;


import com.mojang.logging.LogUtils;
import graphics.cinnabar.api.CinnabarAPI;
import graphics.cinnabar.api.CinnabarAPIBootstrapper;
import graphics.cinnabar.api.annotations.UsedFromReflection;
import graphics.cinnabar.lib.CinnabarLibBootstrapper;
import graphics.cinnabar.lib.config.ConfigManager;
import graphics.cinnabar.lib.config.ConfigType;
import graphics.cinnabar.lib.config.RegisterConfig;
import graphics.cinnabar.lib.threading.CleanupThread;
import graphics.cinnabar.lib.util.Pair;
import graphics.cinnabar.lib.vulkan.VulkanDebug;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntReferenceImmutablePair;
import it.unimi.dsi.fastutil.ints.IntReferencePair;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.neoforged.fml.loading.FMLLoader;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.util.List;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static graphics.cinnabar.lib.helpers.GLFWClassloadHelper.glfwExtGetPhysicalDevicePresentationSupport;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTExtendedDynamicState2.VK_EXT_EXTENDED_DYNAMIC_STATE_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTExtendedDynamicState3.VK_EXT_EXTENDED_DYNAMIC_STATE_3_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

public class CinnabarCore {
    
    public static final Logger CINNABAR_CORE_LOG = LogUtils.getLogger();
    
    @RegisterConfig(name = CinnabarAPI.MOD_ID + "-core", type = ConfigType.CLIENT, rootLevelType = ConfigType.CLIENT)
    public static final CinnabarCoreConfig CONFIG = new CinnabarCoreConfig();
    
    static {
        try {
            ConfigManager.registerConfig(CONFIG, CinnabarCore.class.getField("CONFIG").getAnnotation(RegisterConfig.class));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }
    
    public static final boolean DEBUG = CONFIG.Debug;
    
    public static final VkInstance vkInstance;
    private static final long debugCallback;
    
    private static final List<String> requiredDeviceExtensions = List.of(
            // extended dynamic state is part of VK 1.3
            // state_2 still has logic op being dynamic
            VK_EXT_EXTENDED_DYNAMIC_STATE_2_EXTENSION_NAME,
            VK_EXT_EXTENDED_DYNAMIC_STATE_3_EXTENSION_NAME,
            VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME,
            VK_KHR_SWAPCHAIN_EXTENSION_NAME
    );
    
    public static final VkPhysicalDevice vkPhysicalDevice;
    
    public static final VkDevice vkDevice;
    
    public static final VkQueue graphicsQueue;
    public static final int graphicsQueueFamily;
    @Nullable
    public static final VkQueue computeQueue;
    public static final int computeQueueFamily;
    @Nullable
    public static final VkQueue transferQueue;
    public static final int transferQueueFamily;
    
    static {
        CinnabarLibBootstrapper.bootstrap();
        CINNABAR_CORE_LOG.info("Initializing CinnabarCore");
        final var instanceAndDebugCallback = createVkInstance();
        vkInstance = instanceAndDebugCallback.first();
        debugCallback = instanceAndDebugCallback.second();
        vkPhysicalDevice = selectPhysicalDevice();
        final Pair<VkDevice, List<IntReferencePair<VkQueue>>> deviceAndQueues;
        try {
            deviceAndQueues = createLogicalDeviceAndQueues();
        } catch (Exception e) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
            vkDestroyInstance(vkInstance, null);
            throw e;
        }
        vkDevice = deviceAndQueues.first();
        final var queues = deviceAndQueues.second();
        graphicsQueue = queues.getFirst().second();
        graphicsQueueFamily = queues.getFirst().firstInt();
        computeQueue = queues.get(1).second();
        computeQueueFamily = queues.get(1).firstInt();
        transferQueue = queues.get(2).second();
        transferQueueFamily = queues.get(2).firstInt();
        CinnabarAPIBootstrapper.boostrap();
    }
    
    @UsedFromReflection("graphics.cinnabar.service.CinnabarEarlyWindowProvider.updateModuleReads")
    public static void startup() {
    }
    
    public static void shutdown() {
        CleanupThread.shutdown();
        vkDestroyDevice(vkDevice, null);
        vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
        vkDestroyInstance(vkInstance, null);
        CINNABAR_CORE_LOG.info("CinnabarCore Shutdown");
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
            final var enabledLayerNames = new ReferenceArrayList<String>();
            
            
            final var createInfo = VkInstanceCreateInfo.calloc(stack).sType$Default();
            createInfo.pApplicationInfo(appInfo);
            
            @Nullable
            VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo = null;
            if (FMLLoader.isProduction() && CONFIG.Debug) {
                TinyFileDialogs.tinyfd_messageBox("Minecraft: Cinnabar", """
                        Debug mode enabled
                        Enabling this option significantly hurts performance, and may result in crashes due to debug checks
                        """, "ok", "warn", false);
            }
            if (CONFIG.EnableValidationLayers || CONFIG.Debug) {
                CINNABAR_CORE_LOG.info("Vulkan validation layers requested");
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
                    CINNABAR_CORE_LOG.error("Unable to initialize validation layers, are they installed?");
                } else if (!hasEXTDebug) {
                    CINNABAR_CORE_LOG.error("Unable to initialize validation layers, VK_EXT_debug_utils not present");
                } else {
                    if (FMLLoader.isProduction() && !CONFIG.Debug) {
                        TinyFileDialogs.tinyfd_messageBox("Minecraft: Cinnabar", """
                                Vulkan validation layers enabled
                                Enabling this options significantly hurts performance
                                """, "ok", "warn", false);
                    }
                    CINNABAR_CORE_LOG.warn("Vulkan validation layers enabled, performance may suffer!");
                    
                    enabledLayerNames.add("VK_LAYER_KHRONOS_validation");
                    enabledInstanceExtensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
                    
                    debugCreateInfo = VulkanDebug.getCreateInfo(stack, CONFIG.MessageSeverities, CONFIG.MessageTypes);
                    createInfo.pNext(debugCreateInfo.address());
                }
            }
            if (CONFIG.EnableMesaOverlay && System.getenv().get("ENABLE_VULKAN_RENDERDOC_CAPTURE") == null) {
                final var layerCountPtr = stack.ints(0);
                vkEnumerateInstanceLayerProperties(layerCountPtr, null);
                final var layerCount = layerCountPtr.get(0);
                final var properties = VkLayerProperties.calloc(layerCount, stack);
                vkEnumerateInstanceLayerProperties(layerCountPtr, properties);
                for (int i = 0; i < layerCount; i++) {
                    properties.position(i);
                    // overlay requested, renderdoc isn't attached, and we have the mesa overlay, enable it
                    if (properties.layerNameString().equals("VK_LAYER_MESA_overlay")) {
                        enabledLayerNames.add("VK_LAYER_MESA_overlay");
                        break;
                    }
                }
            }
            
            var layerPointers = stack.mallocPointer(enabledLayerNames.size());
            for (int i = 0; i < enabledLayerNames.size(); i++) {
                layerPointers.put(i, stack.UTF8(enabledLayerNames.get(i)));
            }
            createInfo.ppEnabledLayerNames(layerPointers);
            
            
            @Nullable
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
            checkVkCode(vkCreateInstance(createInfo, null, instancePointer));
            final var vkInstance = new VkInstance(instancePointer.get(0), createInfo);
            CINNABAR_CORE_LOG.info("VkInstance created");
            
            final long debugCallback;
            if (debugCreateInfo != null) {
                var lp = stack.mallocLong(1);
                checkVkCode(vkCreateDebugUtilsMessengerEXT(vkInstance, debugCreateInfo, null, lp));
                debugCallback = lp.get();
            } else {
                debugCallback = -1;
            }
            
            return new Pair<>(vkInstance, debugCallback);
        }
    }
    
    private static VkPhysicalDevice selectPhysicalDevice() {
        try (var stack = MemoryStack.stackPush()) {
            final var physicalDeviceCountPtr = stack.callocInt(1);
            vkEnumeratePhysicalDevices(vkInstance, physicalDeviceCountPtr, null);
            final var physicalDeviceCount = physicalDeviceCountPtr.get(0);
            final var physicalDevices = stack.callocPointer(physicalDeviceCount);
            vkEnumeratePhysicalDevices(vkInstance, physicalDeviceCountPtr, physicalDevices);
            
            final var queueFamilyCountPtr = stack.callocInt(1);
            final var selectedPhysicalDeviceProperties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            final var properties2 = VkPhysicalDeviceProperties2.calloc().sType$Default();
            final var deviceProperties = properties2.properties();
            final var limits = properties2.properties().limits();
            final var externalBufferInfo = VkPhysicalDeviceExternalBufferInfo.calloc(stack).sType$Default();
            // host buffers will never get used as anything except transfer src
            externalBufferInfo.usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            externalBufferInfo.handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT);
            final var externalBufferPropertiesPtr = VkExternalBufferProperties.calloc(stack).sType$Default();
            final var externalBufferProperties = externalBufferPropertiesPtr.externalMemoryProperties();
            
            final var physicalDeviceFeatures = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            final var physicalDeviceFeatures10 = physicalDeviceFeatures.features();
            final var physicalDeviceFeatures11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
            final var physicalDeviceFeatures12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            final var physicalDeviceFeatures13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
            final var extendedDynamicState2Features = VkPhysicalDeviceExtendedDynamicState2FeaturesEXT.calloc(stack).sType$Default();
            final var extendedDynamicState3Features = VkPhysicalDeviceExtendedDynamicState3FeaturesEXT.calloc(stack).sType$Default();
            physicalDeviceFeatures.pNext(physicalDeviceFeatures11);
            physicalDeviceFeatures.pNext(physicalDeviceFeatures12);
            physicalDeviceFeatures.pNext(physicalDeviceFeatures13);
            physicalDeviceFeatures.pNext(extendedDynamicState2Features);
            physicalDeviceFeatures.pNext(extendedDynamicState3Features);
            
            @Nullable
            VkPhysicalDevice selectedPhysicalDevice = null;
            for (int i = 0; i < physicalDeviceCount; i++) {
                if (CONFIG.ForcedVulkanDeviceIndex != -1 && i != CONFIG.ForcedVulkanDeviceIndex) {
                    CINNABAR_CORE_LOG.info("Skipping device index {} as specific device index {} requested", i, CONFIG.ForcedVulkanDeviceIndex);
                    continue;
                }
                final var physicalDevicePtr = physicalDevices.get(i);
                final var physicalDevice = new VkPhysicalDevice(physicalDevicePtr, vkInstance);
                
                vkGetPhysicalDeviceProperties2(physicalDevice, properties2);
                
                CINNABAR_CORE_LOG.info("Considering device {}", deviceProperties.deviceNameString());
                
                // uint32 in C++, 2^32 - 1
                if (limits.maxMemoryAllocationCount() != -1) {
                    CINNABAR_CORE_LOG.info("Skipping device, not enough allocations available");
                    continue;
                }
                
                vkGetPhysicalDeviceExternalBufferProperties(physicalDevice, externalBufferInfo, externalBufferPropertiesPtr);
                if ((externalBufferProperties.externalMemoryFeatures() & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) == 0) {
                    CINNABAR_CORE_LOG.info("Skipping device, unable to import host memory for transfer src");
                    continue;
                }
                if ((externalBufferProperties.externalMemoryFeatures() & VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT) != 0) {
                    CINNABAR_CORE_LOG.info("Skipping device, dedicated allocation required for imported host memory");
                    continue;
                }
                
                vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, queueFamilyCountPtr, null);
                final var queueFamilyCount = queueFamilyCountPtr.get(0);
                final var queueFamilyProperties2 = VkQueueFamilyProperties2.calloc(queueFamilyCount, stack).sType$Default();
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
                    CINNABAR_CORE_LOG.info("Skipping device, could not find suitable graphics queue");
                    continue;
                }
                
                vkGetPhysicalDeviceFeatures2(physicalDevice, physicalDeviceFeatures);
                if (!hasAllRequiredFeatures(physicalDeviceFeatures10, physicalDeviceFeatures11, physicalDeviceFeatures12, physicalDeviceFeatures13, extendedDynamicState2Features, extendedDynamicState3Features)) {
                    CINNABAR_CORE_LOG.info("Skipping device, missing required features");
                    continue;
                }
                
                final var currentDeviceType = deviceProperties.deviceType();
                if (CONFIG.ManualDeviceSelection && CONFIG.ForcedVulkanDeviceIndex == -1) {
                    
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
                        CINNABAR_CORE_LOG.info("Device {} selected by user", deviceName);
                        selectedPhysicalDevice = physicalDevice;
                        break;
                    }
                    CINNABAR_CORE_LOG.info("Device {} rejected by user", deviceName);
                    if (i == physicalDeviceCount - 1) {
                        CINNABAR_CORE_LOG.info("All vulkan devices rejected by user");
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
            }
            if (selectedPhysicalDevice == null) {
                throw new IllegalStateException("Failed to select VkPhysicalDevice");
            }
            vkGetPhysicalDeviceProperties2(selectedPhysicalDevice, properties2);
            CINNABAR_CORE_LOG.info("VkPhysicalDevice selected!");
            final var APIVersionEncoded = deviceProperties.apiVersion();
            final var driverVersionEncoded = deviceProperties.driverVersion();
            CINNABAR_CORE_LOG.info("Device name: {}", deviceProperties.deviceNameString());
            CINNABAR_CORE_LOG.info("Device API version: {}.{}.{}", VK_VERSION_MAJOR(APIVersionEncoded), VK_VERSION_MINOR(APIVersionEncoded), VK_VERSION_PATCH(APIVersionEncoded));
            CINNABAR_CORE_LOG.info("Device driver version: {}.{}.{}", VK_VERSION_MAJOR(driverVersionEncoded), VK_VERSION_MINOR(driverVersionEncoded), VK_VERSION_PATCH(driverVersionEncoded));
            return selectedPhysicalDevice;
        }
    }
    
    private static void logMissingFeature(String featureName) {
        CINNABAR_CORE_LOG.info("Skipping device, missing required feature {}", featureName);
    }
    
    private static boolean hasAllRequiredFeatures(
            VkPhysicalDeviceFeatures physicalDeviceFeatures10,
            VkPhysicalDeviceVulkan11Features physicalDeviceFeatures11,
            VkPhysicalDeviceVulkan12Features physicalDeviceFeatures12,
            VkPhysicalDeviceVulkan13Features physicalDeviceFeatures13,
            VkPhysicalDeviceExtendedDynamicState2FeaturesEXT extendedDynamicState2Features,
            VkPhysicalDeviceExtendedDynamicState3FeaturesEXT extendedDynamicState3Features
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
        if (!physicalDeviceFeatures10.logicOp()) {
            logMissingFeature("logicOp");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures10.multiDrawIndirect()) {
            logMissingFeature("multiDrawIndirect");
            hasAllFeatures = false;
        }
        
        if (!physicalDeviceFeatures11.storageBuffer16BitAccess()) {
            logMissingFeature("storageBuffer16BitAccess");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures11.uniformAndStorageBuffer16BitAccess()) {
            logMissingFeature("uniformAndStorageBuffer16BitAccess");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures11.shaderDrawParameters()) {
            logMissingFeature("shaderDrawParameters");
            hasAllFeatures = false;
        }
        
        if (!physicalDeviceFeatures12.drawIndirectCount()) {
            logMissingFeature("drawIndirectCount");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.descriptorIndexing()) {
            logMissingFeature("descriptorIndexing");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.descriptorBindingUniformBufferUpdateAfterBind()) {
            logMissingFeature("descriptorBindingUniformBufferUpdateAfterBind");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.descriptorBindingSampledImageUpdateAfterBind()) {
            logMissingFeature("descriptorBindingSampledImageUpdateAfterBind");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.descriptorBindingStorageImageUpdateAfterBind()) {
            logMissingFeature("descriptorBindingStorageImageUpdateAfterBind");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.descriptorBindingStorageBufferUpdateAfterBind()) {
            logMissingFeature("descriptorBindingStorageBufferUpdateAfterBind");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.descriptorBindingUniformTexelBufferUpdateAfterBind()) {
            logMissingFeature("descriptorBindingUniformTexelBufferUpdateAfterBind");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.descriptorBindingStorageTexelBufferUpdateAfterBind()) {
            logMissingFeature("descriptorBindingStorageTexelBufferUpdateAfterBind");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.descriptorBindingUpdateUnusedWhilePending()) {
            logMissingFeature("descriptorBindingUpdateUnusedWhilePending");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.descriptorBindingPartiallyBound()) {
            logMissingFeature("descriptorBindingPartiallyBound");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.descriptorBindingVariableDescriptorCount()) {
            logMissingFeature("descriptorBindingVariableDescriptorCount");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.runtimeDescriptorArray()) {
            logMissingFeature("runtimeDescriptorArray");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.scalarBlockLayout()) {
            logMissingFeature("scalarBlockLayout");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.shaderFloat16()) {
            logMissingFeature("shaderFloat16");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.shaderInt8()) {
            logMissingFeature("shaderInt8");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.shaderStorageBufferArrayNonUniformIndexing()) {
            logMissingFeature("shaderStorageBufferArrayNonUniformIndexing");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.shaderStorageImageArrayNonUniformIndexing()) {
            logMissingFeature("shaderStorageImageArrayNonUniformIndexing");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.shaderStorageTexelBufferArrayDynamicIndexing()) {
            logMissingFeature("shaderStorageTexelBufferArrayDynamicIndexing");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.shaderStorageTexelBufferArrayNonUniformIndexing()) {
            logMissingFeature("shaderStorageTexelBufferArrayNonUniformIndexing");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.shaderUniformBufferArrayNonUniformIndexing()) {
            logMissingFeature("shaderUniformBufferArrayNonUniformIndexing");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.shaderUniformTexelBufferArrayDynamicIndexing()) {
            logMissingFeature("shaderUniformTexelBufferArrayDynamicIndexing");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.shaderUniformTexelBufferArrayNonUniformIndexing()) {
            logMissingFeature("shaderUniformTexelBufferArrayNonUniformIndexing");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.storageBuffer8BitAccess()) {
            logMissingFeature("storageBuffer8BitAccess");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.storagePushConstant8()) {
            logMissingFeature("storagePushConstant8");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.timelineSemaphore()) {
            logMissingFeature("timelineSemaphore");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.uniformAndStorageBuffer8BitAccess()) {
            logMissingFeature("uniformAndStorageBuffer8BitAccess");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.uniformBufferStandardLayout()) {
            logMissingFeature("uniformBufferStandardLayout");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures12.vulkanMemoryModel()) {
            logMissingFeature("vulkanMemoryModel");
            hasAllFeatures = false;
        }
        
        if (!physicalDeviceFeatures13.computeFullSubgroups()) {
            logMissingFeature("computeFullSubgroups");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures13.descriptorBindingInlineUniformBlockUpdateAfterBind()) {
            logMissingFeature("descriptorBindingInlineUniformBlockUpdateAfterBind");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures13.dynamicRendering()) {
            logMissingFeature("dynamicRendering");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures13.inlineUniformBlock()) {
            logMissingFeature("inlineUniformBlock");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures13.maintenance4()) {
            logMissingFeature("maintenance4");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures13.privateData()) {
            logMissingFeature("privateData");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures13.shaderTerminateInvocation()) {
            logMissingFeature("shaderTerminateInvocation");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures13.subgroupSizeControl()) {
            logMissingFeature("subgroupSizeControl");
            hasAllFeatures = false;
        }
        if (!physicalDeviceFeatures13.synchronization2()) {
            logMissingFeature("synchronization2");
            hasAllFeatures = false;
        }
        
        if (!extendedDynamicState2Features.extendedDynamicState2LogicOp()) {
            logMissingFeature("extendedDynamicState2LogicOp");
            hasAllFeatures = false;
        }
        if (!extendedDynamicState3Features.extendedDynamicState3LogicOpEnable()) {
            logMissingFeature("extendedDynamicState3LogicOpEnable");
            hasAllFeatures = false;
        }
        if (!extendedDynamicState3Features.extendedDynamicState3ColorBlendEnable()) {
            logMissingFeature("extendedDynamicState3ColorBlendEnable");
            hasAllFeatures = false;
        }
        if (!extendedDynamicState3Features.extendedDynamicState3ColorBlendEquation()) {
            logMissingFeature("extendedDynamicState3ColorBlendEquation");
            hasAllFeatures = false;
        }
        if (!extendedDynamicState3Features.extendedDynamicState3ColorWriteMask()) {
            logMissingFeature("extendedDynamicState3ColorWriteMask");
            hasAllFeatures = false;
        }
        return hasAllFeatures;
    }
    
    private static Pair<VkDevice, List<IntReferencePair<VkQueue>>> createLogicalDeviceAndQueues() {
        try (var stack = MemoryStack.stackPush()) {
            
            int graphicsQueueFamily = 0;
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
                final var queueFamilyProperties2 = VkQueueFamilyProperties2.calloc(queueFamilyCount, stack).sType$Default();
                vkGetPhysicalDeviceQueueFamilyProperties2(vkPhysicalDevice, queueFamilyCountPtr, queueFamilyProperties2);
                for (int j = 0; j < queueFamilyCount; j++) {
                    if (!glfwExtGetPhysicalDevicePresentationSupport(vkInstance, vkPhysicalDevice, 0)) {
                        continue;
                    }
                    int familyUsedQueues = 0;
                    final var queueFamilyProperties = queueFamilyProperties2.queueFamilyProperties();
                    // look for the spec guaranteed combined graphics/compute queue
                    // both graphics and compute queues are implicit transfer queues
                    // graphics queue must also support present (all do in reality)
                    final var graphicsQueueBits = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
                    if ((queueFamilyProperties.queueFlags() & graphicsQueueBits) == graphicsQueueBits && glfwExtGetPhysicalDevicePresentationSupport(vkInstance, vkPhysicalDevice, j)) {
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
                        // pick the most specific queue family available
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
                } else {
                    queueFamilies.add(computeQueueFamily);
                    queueCounts.add(1);
                }
                if (queueFamilies.contains(transferQueueIndex)) {
                    final var queueCreateIndex = queueFamilies.indexOf(transferQueueFamily);
                    final var queueIndex = queueCounts.getInt(queueCreateIndex);
                    computeQueueIndex = queueIndex;
                    queueCounts.set(queueCreateIndex, queueIndex + 1);
                } else {
                    queueFamilies.add(transferQueueFamily);
                    queueCounts.add(1);
                }
            }
            
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
            final var extendedDynamicState2Features = VkPhysicalDeviceExtendedDynamicState2FeaturesEXT.calloc(stack).sType$Default();
            final var extendedDynamicState3Features = VkPhysicalDeviceExtendedDynamicState3FeaturesEXT.calloc(stack).sType$Default();
            
            physicalDeviceFeatures10.drawIndirectFirstInstance(true);
            physicalDeviceFeatures10.fillModeNonSolid(true);
            physicalDeviceFeatures10.logicOp(true);
            physicalDeviceFeatures10.multiDrawIndirect(true);
            
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
            
            extendedDynamicState2Features.extendedDynamicState2LogicOp(true);
            
            extendedDynamicState3Features.extendedDynamicState3LogicOpEnable(true);
            extendedDynamicState3Features.extendedDynamicState3ColorBlendEnable(true);
            extendedDynamicState3Features.extendedDynamicState3ColorBlendEquation(true);
            extendedDynamicState3Features.extendedDynamicState3ColorWriteMask(true);
            
            
            final var deviceCreateInfo = VkDeviceCreateInfo.calloc(stack).sType$Default();
            deviceCreateInfo.pQueueCreateInfos(queueCreateInfos);
            
            deviceCreateInfo.pEnabledFeatures(physicalDeviceFeatures10);
            deviceCreateInfo.pNext(physicalDeviceFeatures11);
            deviceCreateInfo.pNext(physicalDeviceFeatures12);
            deviceCreateInfo.pNext(physicalDeviceFeatures13);
            deviceCreateInfo.pNext(extendedDynamicState2Features);
            deviceCreateInfo.pNext(extendedDynamicState3Features);
            
            final var extensionCount = stack.callocInt(1);
            vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, (String) null, extensionCount, null);
            final var extensionProperties = VkExtensionProperties.calloc(extensionCount.get(0), stack);
            vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, (String) null, extensionCount, extensionProperties);
            
            var extensions = stack.mallocPointer(requiredDeviceExtensions.size());
            for (int i = 0; i < requiredDeviceExtensions.size(); i++) {
                boolean hasExtension = false;
                for (int j = 0; j < extensionCount.get(0); j++) {
                    extensionProperties.position(j);
                    if (extensionProperties.extensionNameString().equals(requiredDeviceExtensions.get(i))) {
                        hasExtension = true;
                        break;
                    }
                }
                if (!hasExtension) {
                    throw new IllegalStateException("Missing device extension " + requiredDeviceExtensions.get(i));
                }
                extensions.put(i, stack.UTF8(requiredDeviceExtensions.get(i)));
            }
            deviceCreateInfo.ppEnabledExtensionNames(extensions);
            
            final var pointerPointer = stack.mallocPointer(1);
            checkVkCode(vkCreateDevice(vkPhysicalDevice, deviceCreateInfo, null, pointerPointer));
            CINNABAR_CORE_LOG.info("VkDevice created");
            
            final var logicalDevice = new VkDevice(pointerPointer.get(0), vkPhysicalDevice, deviceCreateInfo);
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
