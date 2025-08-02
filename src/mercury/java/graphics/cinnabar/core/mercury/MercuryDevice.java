package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.*;
import graphics.cinnabar.api.hg.enums.HgFormat;
import graphics.cinnabar.lib.util.MathUtil;
import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import graphics.cinnabar.loader.earlywindow.vulkan.VulkanDebug;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;

import java.util.List;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.VK12.*;

public class MercuryDevice implements HgDevice {
    
    public final MercuryQueue graphicsQueue;
    public final MercuryQueue computeQueue;
    public final MercuryQueue transferQueue;
    private final VkInstance vkInstance;
    private final long debugCallback;
    private final List<String> enabledLayersAndInstanceExtensions;
    private final MercuryDeviceProperties properties;
    private final VkPhysicalDevice vkPhysicalDevice;
    private final VkDevice vkDevice;
    private final long vmaAllocator;
    private int currentVmaFrame = 0;
    
    public MercuryDevice() {
        // TODO: the vulkan instance can be statically created
        try (final var stack = MemoryStack.stackPush()) {
            final var debugCreateInfo = VulkanDebug.getCreateInfo(stack, new VulkanDebug.MessageSeverity[]{VulkanDebug.MessageSeverity.ERROR, VulkanDebug.MessageSeverity.WARNING, VulkanDebug.MessageSeverity.INFO}, new VulkanDebug.MessageType[]{VulkanDebug.MessageType.GENERAL, VulkanDebug.MessageType.VALIDATION});
            final var instanceAndDebugCallback = VulkanStartup.createVkInstance(Mercury.DEBUG_LOGGING, false, debugCreateInfo);
            vkInstance = instanceAndDebugCallback.instance();
            debugCallback = instanceAndDebugCallback.debugCallback();
            enabledLayersAndInstanceExtensions = instanceAndDebugCallback.enabledInsanceExtensions();
        }
        
        try {
            vkPhysicalDevice = VulkanStartup.selectPhysicalDevice(vkInstance, false, -1, enabledLayersAndInstanceExtensions);
        } catch (Exception e) {
            if (debugCallback != -1) {
                vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
            }
            vkDestroyInstance(vkInstance, null);
            throw e;
        }
        
        final VulkanStartup.Device deviceAndQueues;
        try {
            deviceAndQueues = VulkanStartup.createLogicalDeviceAndQueues(vkInstance, vkPhysicalDevice, enabledLayersAndInstanceExtensions);
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
            transferQueue = new MercuryQueue(this, vkTransferQueue.queue(), vkComputeQueue.queueFamily());
        }
        
        properties = MercuryDeviceProperties.create(this);
        
        try (final var stack = MemoryStack.stackPush()) {
            
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
    public MercuryBuffer createBuffer(HgBuffer.MemoryType memoryType, long size, long usage) {
        return new MercuryBuffer(this, memoryType, size, usage);
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
        try (final var stack = MemoryStack.stackPush()) {
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
        try (final var stack = MemoryStack.stackPush()) {
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
}
