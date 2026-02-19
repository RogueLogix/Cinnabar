package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.exceptions.VkOutOfDeviceMemory;
import graphics.cinnabar.api.hg.*;
import graphics.cinnabar.api.hg.enums.HgFormat;
import graphics.cinnabar.lib.util.MathUtil;
import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import graphics.cinnabar.loader.earlywindow.vulkan.VulkanDebug;
import it.unimi.dsi.fastutil.longs.LongLongImmutablePair;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import static graphics.cinnabar.core.mercury.Mercury.MEMORY_STACK;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK12.*;

public class MercuryDevice implements HgDevice {
    
    private static final List<String> requiredDeviceExtensions = List.of(
            VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME,
            VK_KHR_SWAPCHAIN_EXTENSION_NAME
    );
    
    public final MercuryQueue graphicsQueue;
    public final MercuryQueue computeQueue;
    public final MercuryQueue transferQueue;
    private final VkInstance vkInstance;
    private final long debugCallback;
    private final List<String> enabledLayersAndInstanceExtensions;
    private final MercuryDeviceProperties properties;
    private final VkPhysicalDevice vkPhysicalDevice;
    private final VkDevice vkDevice;
    private final List<String> enabledDeviceExtensions;
    private final long vmaAllocator;
    private int currentVmaFrame = 0;
    
    public final boolean UMA;
    public final int allowedHostBufferMemoryBits;
    public final int allowedDeviceBufferMemoryBits;
    
    @Nullable
    private AllocFailedCallback allocFailedCallback;
    
    public MercuryDevice(HgDevice.CreateInfo createInfo) {
        // TODO: the vulkan instance can be statically created
        try (final var stack = memoryStack().push()) {
            final var debugCreateInfo = VulkanDebug.getCreateInfo(stack, new VulkanDebug.MessageSeverity[]{VulkanDebug.MessageSeverity.ERROR, VulkanDebug.MessageSeverity.WARNING, VulkanDebug.MessageSeverity.INFO}, new VulkanDebug.MessageType[]{VulkanDebug.MessageType.GENERAL, VulkanDebug.MessageType.VALIDATION});
            final var instanceAndDebugCallback = VulkanStartup.createVkInstance(Mercury.VULKAN_VALIDATION, debugCreateInfo);
            vkInstance = instanceAndDebugCallback.instance();
            debugCallback = instanceAndDebugCallback.debugCallback();
            enabledLayersAndInstanceExtensions = instanceAndDebugCallback.enabledInsanceExtensions();
        }
        
        final var featureChainBuilders = createInfo.featureChainBuilders().clone();
        final var featureCheckers = createInfo.featureCheckers().clone();
        final var featureEnablers = createInfo.featureEnablers().clone();
        final var extensions = createInfo.requiredDeviceExtensions().clone();
        extensions.addAll(requiredDeviceExtensions);
        
        featureChainBuilders.add(MercuryDeviceStartup::allocFeatureChain);
        featureCheckers.add(MercuryDeviceStartup::hasAllRequiredFeatures);
        featureEnablers.add(MercuryDeviceStartup::enableRequiredFeatures);
        
        final BiConsumer<MemoryStack, VkPhysicalDeviceFeatures2> featureChainBuilder = (stack, featureChain) -> {
            for (final var chainBuilder : featureChainBuilders) {
                chainBuilder.accept(stack, featureChain);
            }
        };
        final Predicate<VkPhysicalDeviceFeatures2> featureChecker = features2 -> {
            for (final var checker : featureCheckers) {
                if (!checker.test(features2)) {
                    return false;
                }
            }
            return true;
        };
        
        try {
            vkPhysicalDevice = VulkanStartup.selectPhysicalDevice(vkInstance, featureChainBuilder, featureChecker, -1, enabledLayersAndInstanceExtensions, extensions);
        } catch (Exception e) {
            if (debugCallback != -1) {
                vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
            }
            vkDestroyInstance(vkInstance, null);
            throw e;
        }
        
        final VulkanStartup.Device deviceAndQueues;
        try (final var stack = MemoryStack.stackPush()) {
            
            final var deviceFeatures2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            featureChainBuilder.accept(stack, deviceFeatures2);
            vkGetPhysicalDeviceFeatures2(vkPhysicalDevice, deviceFeatures2);
            final var enabledFeatures = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            featureChainBuilder.accept(stack, enabledFeatures);
            for (final var enabler : featureEnablers) {
                enabler.accept(deviceFeatures2, enabledFeatures);
            }
            
            deviceAndQueues = VulkanStartup.createLogicalDeviceAndQueues(vkInstance, vkPhysicalDevice, enabledLayersAndInstanceExtensions, enabledFeatures, extensions);
        } catch (Exception e) {
            if (debugCallback != -1) {
                vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
            }
            vkDestroyInstance(vkInstance, null);
            throw e;
        }
        
        vkDevice = deviceAndQueues.device();
        final var queues = deviceAndQueues.queues();
        final var vkGraphicsQueue = queues.get(0);
        final var vkComputeQueue = queues.get(1);
        final var vkTransferQueue = queues.get(2);
        graphicsQueue = new MercuryQueue(this, vkGraphicsQueue.queue(), vkGraphicsQueue.queueFamily());
        if (vkGraphicsQueue == vkComputeQueue) {
            computeQueue = graphicsQueue;
        } else {
            computeQueue = new MercuryQueue(this, vkComputeQueue.queue(), vkComputeQueue.queueFamily());
        }
        if (vkComputeQueue == vkTransferQueue) {
            transferQueue = computeQueue;
        } else {
            transferQueue = new MercuryQueue(this, vkTransferQueue.queue(), vkTransferQueue.queueFamily());
        }
        enabledDeviceExtensions = deviceAndQueues.enabledDeviceExtensions();
        
        properties = MercuryDeviceProperties.create(this);
        
        try (final var stack = memoryStack().push()) {
            
            final var vmaVulkanFunctions = VmaVulkanFunctions.calloc(stack);
            vmaVulkanFunctions.set(vkInstance, vkDevice);
            
            final var allocatorCreateInfo = VmaAllocatorCreateInfo.calloc(stack);
            allocatorCreateInfo.flags(0);
            allocatorCreateInfo.vulkanApiVersion(VK_API_VERSION_1_2);
            allocatorCreateInfo.physicalDevice(vkPhysicalDevice);
            allocatorCreateInfo.device(vkDevice);
            allocatorCreateInfo.instance(vkInstance);
            allocatorCreateInfo.pVulkanFunctions(vmaVulkanFunctions);
            
            final var handlePtr = stack.pointers(0);
            vmaCreateAllocator(allocatorCreateInfo, handlePtr);
            vmaAllocator = handlePtr.get(0);
        }
        
        final int hostVisibleCoherentDeviceLocal = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        final int hostVisibleCoherent = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        
        try (final var stack = memoryStack().push()) {
            final var handlePtr = stack.pointers(0);
            vmaGetMemoryProperties(vmaAllocator, handlePtr);
            final var memoryProperties = VkPhysicalDeviceMemoryProperties.create(handlePtr.get(0));
            final var heapCount = memoryProperties.memoryHeapCount();
            if (heapCount == 1) {
                // UMA path
                UMA = true;
                int mappableTypes = 0;
                for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
                    final var type = memoryProperties.memoryTypes(i);
                    // this must exist by spec
                    if ((type.propertyFlags() & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) == (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                        mappableTypes |= 1 << i;
                    }
                }
                allowedHostBufferMemoryBits = mappableTypes;
                allowedDeviceBufferMemoryBits = mappableTypes;
            } else {
                // non-UMA, probably
                UMA = false;
                
                // fist, pick primary heaps
                // w/o ReBAR, there is usually a device-local host-visible chunk that is significantly smaller than the main vram pool
                // if that is the case, ignore it and use the big one
                int hostHeap = -1;
                int deviceHeap = -1;
                for (int i = 0; i < heapCount; i++) {
                    final var heap = memoryProperties.memoryHeaps(i);
                    if ((heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                        // device heap, is it bigger than the current one?
                        // the w/o ReBAR heap is (almost) always 256MB, while the main pool is bigger
                        if (deviceHeap == -1 || heap.size() > memoryProperties.memoryHeaps(deviceHeap).size()) {
                            deviceHeap = i;
                        }
                    } else {
                        // host heap, is it bigger than the current one?
                        // usually there is only one, but just in case
                        if (hostHeap == -1 || heap.size() > memoryProperties.memoryHeaps(hostHeap).size()) {
                            hostHeap = i;
                        }
                    }
                }
                
                
                int mappableDeviceTypes = 0;
                int deviceTypes = 0;
                int hostTypes = 0;
                for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
                    final var type = memoryProperties.memoryTypes(i);
                    if (type.heapIndex() == deviceHeap && (type.propertyFlags() & (hostVisibleCoherentDeviceLocal)) == hostVisibleCoherentDeviceLocal) {
                        mappableDeviceTypes |= 1 << i;
                    }
                    if (type.heapIndex() == deviceHeap && (type.propertyFlags() & (VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) == VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) {
                        deviceTypes |= 1 << i;
                    }
                    
                    if (type.heapIndex() == hostHeap && (type.propertyFlags() & (hostVisibleCoherent)) == hostVisibleCoherent) {
                        hostTypes |= 1 << i;
                    }
                }
                // if there are mappable types, use those. not all GPUs will have them for the selected heap
                allowedDeviceBufferMemoryBits = mappableDeviceTypes == 0 ? deviceTypes : mappableDeviceTypes;
                allowedHostBufferMemoryBits = hostTypes;
            }
            
        }
    }
    
    @Override
    public void destroy() {
        vmaDestroyAllocator(vmaAllocator);
        vkDestroyDevice(vkDevice, null);
        if (debugCallback != -1) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
        }
        vkDestroyInstance(vkInstance, null);
    }
    
    public VkDevice vkDevice() {
        return vkDevice;
    }
    
    public long vmaAllocator() {
        return vmaAllocator;
    }
    
    @Override
    public Properties properties() {
        return properties;
    }
    
    private static MemoryStack memoryStack() {
        return MEMORY_STACK.get();
    }
    
    @Override
    public void setAllocFailedCallback(@Nullable AllocFailedCallback callback) {
        this.allocFailedCallback = callback;
    }
    
    public boolean allocFailed(HgBuffer.MemoryRequest request, long size) {
        return allocFailedCallback != null && allocFailedCallback.allocFailed(request == HgBuffer.MemoryRequest.GPU || UMA, size);
    }
    
    @Override
    public void waitIdle() {
        vkDeviceWaitIdle(vkDevice);
    }
    
    @Override
    public MercuryQueue queue(HgQueue.Type queueType) {
        return switch (queueType) {
            case GRAPHICS -> graphicsQueue;
            case COMPUTE -> computeQueue;
            case TRANSFER -> transferQueue;
        };
    }
    
    @Override
    public MercuryBuffer createBuffer(HgBuffer.MemoryRequest memoryRequest, long size, long usage) {
        while (true) {
            @Nullable
            final var buffer = tryCreateBuffer(memoryRequest, size, usage);
            if (buffer != null) {
                return buffer;
            }
            if (!allocFailed(memoryRequest, size)) {
                throw new VkOutOfDeviceMemory();
            }
        }
    }
    
    @Nullable
    @Override
    public MercuryBuffer tryCreateBuffer(HgBuffer.MemoryRequest memoryType, long size, long usage) {
        return MercuryBuffer.attemptCreate(this, memoryType, size, usage);
    }
    
    @Override
    public MercuryImage createImage(HgImage.Type type, HgFormat format, int width, int height, int depth, int layers, int mipLevels, long usage, int flags, boolean hostMemory) {
        return new MercuryImage(this, type, format, width, height, depth, layers, mipLevels, usage, flags, hostMemory);
    }
    
    @Override
    public MercurySampler createSampler(HgSampler.CreateInfo createInfo) {
        return new MercurySampler(this, createInfo);
    }
    
    @Override
    public MercuryFramebuffer createFramebuffer(HgFramebuffer.CreateInfo createInfo) {
        return new MercuryFramebuffer(this, createInfo);
    }
    
    @Override
    public MercuryRenderPass createRenderPass(HgRenderPass.CreateInfo createInfo) {
        return new MercuryRenderPass(this, createInfo);
    }
    
    @Override
    public MercuryUniformSetLayout createUniformSetLayout(HgUniformSet.Layout.CreateInfo createInfo) {
        return new MercuryUniformSetLayout(this, createInfo);
    }
    
    @Override
    public MercuryShaderSet createShaderSet(HgGraphicsPipeline.ShaderSet.CreateInfo createInfo) {
        return new MercuryShaderSet(this, createInfo);
    }
    
    @Override
    public MercuryGraphicsPipelineLayout createPipelineLayout(HgGraphicsPipeline.Layout.CreateInfo createInfo) {
        return new MercuryGraphicsPipelineLayout(this, createInfo);
    }
    
    @Override
    public MercuryGraphicsPipeline createPipeline(HgGraphicsPipeline.CreateInfo createInfo) {
        return new MercuryGraphicsPipeline(this, createInfo);
    }
    
    @Override
    public MercurySurface createSurface(long glfwWindowHandle) {
        return new MercurySurface(this, glfwWindowHandle);
    }
    
    @Override
    public MercurySemaphore createSemaphore(long initialValue) {
        return new MercurySemaphore(this, initialValue);
    }
    
    @Override
    public boolean waitSemaphores(List<HgSemaphore.Op> hgSemaphores, long timeout, boolean any) {
        try (final var stack = memoryStack().push()) {
            final var count = hgSemaphores.size();
            final var handles = stack.callocLong(count);
            final var values = stack.callocLong(count);
            for (int i = 0; i < count; i++) {
                final var op = hgSemaphores.get(i);
                handles.put(i, ((MercurySemaphore) op.semaphore()).vkSemaphore());
                values.put(i, op.value());
            }
            final var waitInfo = VkSemaphoreWaitInfo.calloc(stack).sType$Default();
            waitInfo.flags(any ? VK_SEMAPHORE_WAIT_ANY_BIT : 0);
            waitInfo.semaphoreCount(count);
            waitInfo.pSemaphores(handles);
            waitInfo.pValues(values);
            return vkWaitSemaphores(vkDevice, waitInfo, -1) == VK_SUCCESS;
        }
    }
    
    @Override
    public void addDebugText(List<String> lines) {
        try (final var stack = memoryStack().push()) {
            final var stats = VmaBudget.calloc(VK_MAX_MEMORY_HEAPS, stack);
            vmaGetHeapBudgets(vmaAllocator, stats);
            for (int i = 0; i < VK_MAX_MEMORY_HEAPS; i++) {
                stats.position(i);
                if (stats.usage() == 0) {
                    continue;
                }
                lines.add(String.format("Heap %d usage: %s/%s/%s", i, MathUtil.byteString(stats.statistics().allocationBytes()), MathUtil.byteString(stats.statistics().blockBytes()), MathUtil.byteString(stats.budget())));
            }
        }
    }
    
    @Override
    public void markFame() {
        vmaSetCurrentFrameIndex(vmaAllocator, currentVmaFrame++);
    }
    
    @Override
    public LongLongImmutablePair hostLocalMemoryStats() {
        try (final var stack = memoryStack().push()) {
            final var propsPtr = stack.pointers(0);
            vmaGetMemoryProperties(vmaAllocator, propsPtr);
            final var props = VkPhysicalDeviceMemoryProperties.createSafe(propsPtr.get(0));
            int primaryDeviceLocalheap = 0;
            for (int i = 0; i < props.memoryHeapCount(); i++) {
                if ((props.memoryHeaps().position(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) == 0) {
                    primaryDeviceLocalheap = i;
                    break;
                }
            }
            final var stats = VmaBudget.calloc(VK_MAX_MEMORY_HEAPS, stack);
            vmaGetHeapBudgets(vmaAllocator, stats);
            stats.position(primaryDeviceLocalheap);
            return new LongLongImmutablePair(stats.statistics().allocationBytes(), stats.budget());
        }
    }
    
    @Override
    public boolean UMA() {
        return UMA;
    }
    
    @Override
    public LongLongImmutablePair deviceLocalMemoryStats() {
        try (final var stack = memoryStack().push()) {
            final var propsPtr = stack.pointers(0);
            vmaGetMemoryProperties(vmaAllocator, propsPtr);
            final var props = VkPhysicalDeviceMemoryProperties.createSafe(propsPtr.get(0));
            int primaryDeviceLocalheap = 0;
            for (int i = 0; i < props.memoryHeapCount(); i++) {
                if ((props.memoryHeaps().position(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    primaryDeviceLocalheap = i;
                    break;
                }
            }
            final var stats = VmaBudget.calloc(VK_MAX_MEMORY_HEAPS, stack);
            vmaGetHeapBudgets(vmaAllocator, stats);
            stats.position(primaryDeviceLocalheap);
            return new LongLongImmutablePair(stats.statistics().allocationBytes(), stats.budget());
        }
    }
    
    public boolean debugUtilsEnabled() {
        return enabledLayersAndInstanceExtensions.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    }
    
    @Override
    public HgDevice setName(String label) {
        if (debugUtilsEnabled()) {
            try (final var stack = memoryStack().push()) {
                final var nameInfo = VkDebugUtilsObjectNameInfoEXT.calloc(stack).sType$Default();
                nameInfo.pObjectName(stack.UTF8(label));
                nameInfo.objectHandle(vkDevice.address());
                nameInfo.objectType(VK_OBJECT_TYPE_DEVICE);
                EXTDebugUtils.vkSetDebugUtilsObjectNameEXT(vkDevice, nameInfo);
            }
        }
        return this;
    }
}
