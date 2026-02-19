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
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import static graphics.cinnabar.loader.earlywindow.GLFWClassloadHelper.glfwExtGetPhysicalDevicePresentationSupport;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTLayerSettings.VK_EXT_LAYER_SETTINGS_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTLayerSettings.VK_LAYER_SETTING_TYPE_BOOL32_EXT;
import static org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK12.*;
#endif

public class VulkanStartup {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static class Config {
        public static String mcVersionString = "1.21.99";
        public static String cinnabarVersionString = "0.0.0";
    }
    
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
    
    private static final String VALIDATION_LAYER_NAME = "VK_LAYER_KHRONOS_validation";
    
    private static final List<String> optionalInstanceExtensions = List.of(
            VK_EXT_DEBUG_UTILS_EXTENSION_NAME,
            VK_EXT_LAYER_SETTINGS_EXTENSION_NAME
    );
    
    private static final List<Pair<String, List<String>>> optionalDeviceExtensions = List.of(
            new ObjectObjectImmutablePair<>(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME, List.of())
    );
    
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
                final var realloc = MemoryUtil.nmemRealloc(pOriginal, size);
                if ((realloc & (alignment - 1)) == 0) {
                    return realloc;
                }
                final var newAlignedAlloc = MemoryUtil.nmemAlignedAlloc(alignment, size);
                MemoryUtil.memCopy(realloc, newAlignedAlloc, size);
                MemoryUtil.nmemFree(realloc);
                return newAlignedAlloc;
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
    
    public static Instance createVkInstance(boolean validationLayers, @Nullable VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo) {
        
        #if FABRIC
        VulkanStartup.Config.mcVersionString = SharedConstants.getCurrentVersion().name();
        VulkanStartup.Config.cinnabarVersionString = FabricLoader.getInstance().getModContainer("cinnabar").get().getMetadata().getVersion().getFriendlyString();
        #endif
        
        try (var stack = MemoryStack.stackPush()) {
            final var appInfo = VkApplicationInfo.calloc(stack);
            final var appName = stack.UTF8("Minecraft");
            final var engineName = stack.UTF8("Cinnabar");
            var mcVersionChunks = Config.mcVersionString.split(" ")[0].split("\\.");
            final int appVersion;
            if (mcVersionChunks.length == 1 && mcVersionChunks[0].matches("[0-9][0-9]w[0-9][0-9]a")) {
                // snapshot version, zero is fine
                appVersion = 0;
            } else {
                if (mcVersionChunks.length == 2) {
                    // something like 26.1, rather than 26.1.0
                    mcVersionChunks = new String[]{mcVersionChunks[0], mcVersionChunks[1], "0"};
                }
                appVersion = VK_MAKE_VERSION(Integer.parseInt(mcVersionChunks[0]), Integer.parseInt(mcVersionChunks[1]), Integer.parseInt(mcVersionChunks[2]));
            }
            final String modVersionString = Config.cinnabarVersionString;
            final var modVersionChunks = modVersionString.split("-")[0].split("\\.");
            final int engineVersion;
            if (modVersionChunks.length == 1 && modVersionChunks[0].equals("${version}")) {
                // fabric dev environment, zero is fine
                engineVersion = 0;
            } else {
                engineVersion = VK_MAKE_VERSION(Integer.parseInt(modVersionChunks[0]), Integer.parseInt(modVersionChunks[1]), Integer.parseInt(modVersionChunks[2]));
            }
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
            final var layerCountPtr = stack.ints(0);
            vkEnumerateInstanceLayerProperties(layerCountPtr, null);
            final var layerCount = layerCountPtr.get(0);
            final var layerProperties = VkLayerProperties.calloc(layerCount, stack);
            vkEnumerateInstanceLayerProperties(layerCountPtr, layerProperties);
            
            final var extensionCount = stack.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((String) null, extensionCount, null);
            final var extensionProperties = VkExtensionProperties.calloc(extensionCount.get(0), stack);
            vkEnumerateInstanceExtensionProperties((String) null, extensionCount, extensionProperties);
            
            // validation layer init is always attempted, but only stateless is enabled if they aren't requested
            boolean hasKHRValidation = false;
            for (int i = 0; i < layerProperties.capacity(); i++) {
                layerProperties.position(i);
                if (layerProperties.layerNameString().equals(VALIDATION_LAYER_NAME)) {
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
                enabledLayerNames.add("VK_LAYER_KHRONOS_validation");
                enabledInstanceExtensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
                
                if (debugCreateInfo != null) {
                    createInfo.pNext(debugCreateInfo);
                }
                
                final var layerNameStr = stack.UTF8(VALIDATION_LAYER_NAME);
                final var TRUE = stack.calloc(4).putInt(0, VK_TRUE);
                final var FALSE = stack.calloc(4).putInt(0, VK_FALSE);
                final var settings = VkLayerSettingEXT.calloc(32);
                if (validationLayers) {
                    settings.get().set(layerNameStr, stack.UTF8("validate_sync"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, TRUE);
                    settings.get().set(layerNameStr, stack.UTF8("printf_enable"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, TRUE);
                    settings.get().set(layerNameStr, stack.UTF8("gpuav_enable"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, TRUE);
//                    settings.get().set(layerNameStr, stack.UTF8("gpuav_safe_mode"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, TRUE);
                    settings.get().set(layerNameStr, stack.UTF8("enable_message_limit"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, FALSE);
                } else {
                    settings.get().set(layerNameStr, stack.UTF8("fine_grained_locking"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, FALSE);
                    settings.get().set(layerNameStr, stack.UTF8("validate_core"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, FALSE);
                    settings.get().set(layerNameStr, stack.UTF8("check_image_layout"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, FALSE);
                    settings.get().set(layerNameStr, stack.UTF8("check_command_buffer"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, FALSE);
                    settings.get().set(layerNameStr, stack.UTF8("check_object_in_use"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, FALSE);
                    settings.get().set(layerNameStr, stack.UTF8("check_query"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, FALSE);
                    settings.get().set(layerNameStr, stack.UTF8("check_shaders"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, FALSE);
                    settings.get().set(layerNameStr, stack.UTF8("unique_handles"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, FALSE);
                    settings.get().set(layerNameStr, stack.UTF8("object_lifetime"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, FALSE);
                    settings.get().set(layerNameStr, stack.UTF8("stateless_param"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, TRUE);
                    settings.get().set(layerNameStr, stack.UTF8("thread_safety"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, FALSE);
                    settings.get().set(layerNameStr, stack.UTF8("syncval_submit_time_validation"), VK_LAYER_SETTING_TYPE_BOOL32_EXT, 1, FALSE);
                }
                settings.flip();
                final var layerSettings = VkLayerSettingsCreateInfoEXT.calloc(stack).sType$Default();
                layerSettings.pSettings(settings);
                createInfo.pNext(layerSettings);
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
            
            
            @Nullable
            final var glfwExtensions = GLFWClassloadHelper.glfwGetRequiredInstanceExtensions();
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
            
            @Nullable
            final var allocationCallbacks = Configuration.DEBUG_MEMORY_ALLOCATOR.get(false) ? callbacks() : null;
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
    
    public static VkPhysicalDevice selectPhysicalDevice(VkInstance vkInstance, BiConsumer<MemoryStack, VkPhysicalDeviceFeatures2> featureChainBuilder, Predicate<VkPhysicalDeviceFeatures2> featureChainChecker, int forcedDeviceIndex, List<String> enabledLayersAndInstanceExtensions, List<String> requiredDeviceExtensions) {
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
                
                try (var _ = stack.push()) {
                    final var featuresChain = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
                    featureChainBuilder.accept(stack, featuresChain);
                    vkGetPhysicalDeviceFeatures2(physicalDevice, featuresChain);
                    if (!featureChainChecker.test(featuresChain)) {
                        LOGGER.info("Skipping device, missing required features");
                        continue;
                    }
                }
                
                final var currentDeviceType = deviceProperties.deviceType();
                
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
                    // TODO: min image transfer granularity matters, VKonD3D12 doesnt like what MC does
                    //       but the actual device is first, so, this fixes it
                    //       properly check for that
                    if (currentDeviceType == selectedDeviceType) {
                        // if the type is identical, prefer the device reported first
                        continue;
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
                        if (currentDeviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) {
                            // prefer vGPUs over iGPUs
                            continue;
                        }
                    }
                }
                selectedPhysicalDevice = physicalDevice;
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
    
    public static Device createLogicalDeviceAndQueues(VkInstance vkInstance, VkPhysicalDevice vkPhysicalDevice, List<String> enabledLayersAndInstanceExtensions, VkPhysicalDeviceFeatures2 enabledFeatures, List<String> requiredDeviceExtensions) {
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
            
            final var deviceCreateInfo = VkDeviceCreateInfo.calloc(stack).sType$Default();
            deviceCreateInfo.pQueueCreateInfos(queueCreateInfos);
            deviceCreateInfo.pEnabledFeatures(enabledFeatures.features());
            deviceCreateInfo.pNext(enabledFeatures.pNext());
            
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
                throw new RuntimeException("Failed to create Vulkan device " + deviceCreateCode);
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
