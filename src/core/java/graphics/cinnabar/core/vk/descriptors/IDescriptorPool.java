package graphics.cinnabar.core.vk.descriptors;

import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.util.Destroyable;

public interface IDescriptorPool extends Destroyable {
    
    @ThreadSafety.Any
    @ThreadSafety.VulkanObjectHandle
    long allocSet(long layout);
    
    @ThreadSafety.Any
    @ThreadSafety.VulkanObjectHandle
    void reset();
}
