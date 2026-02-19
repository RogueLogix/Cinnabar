package graphics.cinnabar.core.mercury;

import com.mojang.logging.LogUtils;
import graphics.cinnabar.api.annotations.API;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Struct;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.util.function.Function;
import java.util.function.LongFunction;

import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES;

public class MercuryDeviceStartup {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private static void logMissingFeature(String featureName) {
        LOGGER.info("Skipping device, missing required feature {}", featureName);
    }
    
    public static void allocFeatureChain(MemoryStack stack, VkPhysicalDeviceFeatures2 features2) {
        findOrAlloc(features2.address(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES, VkPhysicalDeviceVulkan11Features::create, VkPhysicalDeviceVulkan11Features::calloc, stack);
        findOrAlloc(features2.address(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, VkPhysicalDeviceVulkan12Features::create, VkPhysicalDeviceVulkan12Features::calloc, stack);
        findOrAlloc(features2.address(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES, VkPhysicalDeviceSynchronization2FeaturesKHR::create, VkPhysicalDeviceSynchronization2FeaturesKHR::calloc, stack);
    }
    
    public static boolean hasAllRequiredFeatures(VkPhysicalDeviceFeatures2 featuresChain) {
        @Nullable
        final var physicalDeviceFeatures11 = findPNextStruct(featuresChain.address(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES, VkPhysicalDeviceVulkan11Features::create);
        @Nullable
        final var physicalDeviceFeatures12 = findPNextStruct(featuresChain.address(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, VkPhysicalDeviceVulkan12Features::create);
        @Nullable
        final var sync2Features = findPNextStruct(featuresChain.address(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES, VkPhysicalDeviceSynchronization2FeaturesKHR::create);
        
        if (physicalDeviceFeatures11 == null || physicalDeviceFeatures12 == null || sync2Features == null) {
            throw new IllegalStateException();
        }
        return hasAllRequiredFeatures(featuresChain.features(), physicalDeviceFeatures11, physicalDeviceFeatures12, sync2Features);
    }
    
    public static void enableRequiredFeatures(VkPhysicalDeviceFeatures2 deviceFeatures, VkPhysicalDeviceFeatures2 enabledFeatures) {
        final var physicalDeviceFeatures10 = enabledFeatures.features();
        @Nullable
        final var physicalDeviceFeatures11 = findPNextStruct(enabledFeatures.address(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES, VkPhysicalDeviceVulkan11Features::create);
        @Nullable
        final var physicalDeviceFeatures12 = findPNextStruct(enabledFeatures.address(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, VkPhysicalDeviceVulkan12Features::create);
        @Nullable
        final var sync2Features = findPNextStruct(enabledFeatures.address(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES, VkPhysicalDeviceSynchronization2FeaturesKHR::create);
        
        if (physicalDeviceFeatures11 == null || physicalDeviceFeatures12 == null || sync2Features == null) {
            throw new IllegalStateException();
        }
        
        physicalDeviceFeatures10.multiDrawIndirect(true);
        physicalDeviceFeatures10.drawIndirectFirstInstance(true);
        physicalDeviceFeatures10.fillModeNonSolid(true);
        physicalDeviceFeatures10.samplerAnisotropy(true);
        
        physicalDeviceFeatures11.shaderDrawParameters(true);
        
        physicalDeviceFeatures12.timelineSemaphore(true);
        
        sync2Features.synchronization2(true);
    }
    
    @API
    public static <T extends Struct<T>> T findOrAlloc(long firstStruct, int structType, LongFunction<T> creationFunc, Function<MemoryStack, T> allocFunc, MemoryStack stack) {
        final var addr = findPNextStruct(firstStruct, structType);
        if (addr == 0) {
            final var newStruct = allocFunc.apply(stack);
            VkPhysicalDeviceFeatures2.nsType(newStruct.address(), structType);
            appendPNext(firstStruct, newStruct.address());
            return newStruct;
        }
        return creationFunc.apply(addr);
    }
    
    @API
    @Nullable
    public static <T> T findPNextStruct(long firstStruct, int structType, LongFunction<T> creationFunc) {
        final var addr = findPNextStruct(firstStruct, structType);
        if (addr == 0) {
            return null;
        }
        return creationFunc.apply(addr);
    }
    
    public static long findPNextStruct(long firstStruct, int structType) {
        for (long currentStruct = firstStruct; currentStruct != 0; currentStruct = VkPhysicalDeviceFeatures2.npNext(currentStruct)) {
            if (VkPhysicalDeviceFeatures2.nsType(currentStruct) == structType) {
                return currentStruct;
            }
        }
        return 0;
    }
    
    public static long appendPNext(long firstStruct, long newStruct) {
        final var appendLast = lastPNext(newStruct);
        VkPhysicalDeviceFeatures2.npNext(appendLast, VkPhysicalDeviceFeatures2.npNext(firstStruct));
        VkPhysicalDeviceFeatures2.npNext(firstStruct, newStruct);
        return 0;
    }
    
    public static long lastPNext(long struct) {
        while (true) {
            final var next = VkPhysicalDeviceFeatures2.npNext(struct);
            if(next == 0) {
                break;
            }
            struct = next;
        }
        return struct;
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
        if (!physicalDeviceFeatures10.samplerAnisotropy()) {
            logMissingFeature("samplerAnisotropy");
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
}
