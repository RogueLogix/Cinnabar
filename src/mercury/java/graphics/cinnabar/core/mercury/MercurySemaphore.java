package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgSemaphore;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSignalInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;

import static org.lwjgl.vulkan.VK12.*;

public class MercurySemaphore extends MercuryObject implements HgSemaphore {
    
    private final long handle;
    
    public MercurySemaphore(MercuryDevice device, long initialValue) {
        super(device);
        
        try (final var stack = memoryStack().push()) {
            final var createInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            final var typeCreateInfo = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default();
            typeCreateInfo.semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE);
            typeCreateInfo.initialValue(initialValue);
            if (initialValue != -1) {
                createInfo.pNext(typeCreateInfo);
            }
            
            final var handlePtr = stack.longs(1);
            vkCreateSemaphore(device.vkDevice(), createInfo, null, handlePtr);
            handle = handlePtr.get(0);
        }
    }
    
    @Override
    public void destroy() {
        vkDestroySemaphore(device.vkDevice(), handle, null);
    }
    
    public long vkSemaphore() {
        return handle;
    }
    
    @Override
    public long value() {
        try (final var stack = memoryStack().push()) {
            final var valuePtr = stack.longs(0);
            vkGetSemaphoreCounterValue(device.vkDevice(), handle, valuePtr);
            return valuePtr.get(0);
        }
    }
    
    @Override
    public void waitValue(long value, long timeout) {
        try (final var stack = memoryStack().push()) {
            final var waitInfo = VkSemaphoreWaitInfo.calloc(stack).sType$Default();
            waitInfo.semaphoreCount(1);
            waitInfo.pSemaphores(stack.longs(handle));
            waitInfo.pValues(stack.longs(value));
            vkWaitSemaphores(device.vkDevice(), waitInfo, timeout);
        }
    }
    
    @Override
    public void singlaValue(long value) {
        try (final var stack = memoryStack().push()) {
            final var signalInfo = VkSemaphoreSignalInfo.calloc(stack).sType$Default();
            signalInfo.semaphore(handle);
            signalInfo.value(value);
            vkSignalSemaphore(device.vkDevice(), signalInfo);
        }
    }
}
