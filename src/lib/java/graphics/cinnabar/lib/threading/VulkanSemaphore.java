package graphics.cinnabar.lib.threading;

import graphics.cinnabar.api.threading.ISemaphore;
import graphics.cinnabar.api.vk.VulkanObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK12.*;

public class VulkanSemaphore implements VulkanObject, ISemaphore {
    
    private final VkDevice device;
    private final long handle;
    
    public VulkanSemaphore(VkDevice device) {
        this.device = device;
        try (final var stack = MemoryStack.stackPush()) {
            final var createInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            final var typeCreateInfo = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default();
            typeCreateInfo.semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE);
            createInfo.pNext(typeCreateInfo);
            
            final var handlePtr = stack.longs(1);
            vkCreateSemaphore(device, createInfo, null, handlePtr);
            handle = handlePtr.get(0);
        }
    }
    
    @Override
    public void destroy() {
        vkDestroySemaphore(device, handle, null);
    }
    
    @Override
    public long handle() {
        return handle;
    }
    
    @Override
    public int objectType() {
        return VK_OBJECT_TYPE_SEMAPHORE;
    }
    
    public VkDevice device() {
        return device;
    }
    
    @Override
    public long value() {
        try (final var stack = MemoryStack.stackPush()) {
            final var valuePtr = stack.longs(0);
            vkGetSemaphoreCounterValue(device, handle, valuePtr);
            return valuePtr.get(0);
        }
    }
    
    @Override
    public void wait(long value, long timeout) {
        try (final var stack = MemoryStack.stackPush()) {
            final var waitInfo = VkSemaphoreWaitInfo.calloc(stack).sType$Default();
            waitInfo.semaphoreCount(1);
            waitInfo.pSemaphores(stack.longs(handle));
            waitInfo.pValues(stack.longs(value));
            vkWaitSemaphores(device, waitInfo, timeout);
        }
    }
    
    @Override
    public void signal(long value) {
        try (final var stack = MemoryStack.stackPush()) {
            final var signalInfo = VkSemaphoreSignalInfo.calloc(stack).sType$Default();
            signalInfo.semaphore(handle);
            signalInfo.value(value);
            vkSignalSemaphore(device, signalInfo);
        }
    }
}
