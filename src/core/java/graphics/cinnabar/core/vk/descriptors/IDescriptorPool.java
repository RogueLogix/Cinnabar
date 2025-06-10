package graphics.cinnabar.core.vk.descriptors;

import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.exceptions.RedirectImplemented;
import graphics.cinnabar.core.vk.VulkanObject;

public interface IDescriptorPool extends VulkanObject {
    
    static IDescriptorPool create(boolean transientPool) {
        throw new RedirectImplemented();
    }
    
    @ThreadSafety.Any
    @ThreadSafety.VulkanObjectHandle
    long allocSet(long layout);
    
    @ThreadSafety.Any
    @ThreadSafety.VulkanObjectHandle
    void reset();
}
