package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgObject;
import it.unimi.dsi.fastutil.longs.LongIntImmutablePair;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VkDebugUtilsObjectNameInfoEXT;

import static graphics.cinnabar.core.mercury.Mercury.MEMORY_STACK;

public abstract class MercuryObject<T extends HgObject<T>> implements HgObject<T> {
    
    protected final MercuryDevice device;
    
    public MercuryObject(MercuryDevice device) {
        this.device = device;
    }
    
    @Override
    public MercuryDevice device() {
        return device;
    }
    
    protected static MemoryStack memoryStack() {
        return MEMORY_STACK.get();
    }
    
    protected abstract LongIntImmutablePair handleAndType();
    
    @Override
    public T setName(String label) {
        if (device.debugUtilsEnabled()) {
            try (final var stack = memoryStack().push()) {
                final var nameInfo = VkDebugUtilsObjectNameInfoEXT.calloc(stack).sType$Default();
                nameInfo.pObjectName(stack.UTF8(label));
                final var handleType = handleAndType();
                nameInfo.objectHandle(handleType.firstLong());
                nameInfo.objectType(handleType.secondInt());
                EXTDebugUtils.vkSetDebugUtilsObjectNameEXT(device.vkDevice(), nameInfo);
            }
        }
        //noinspection unchecked
        return (T) this;
    }
}
