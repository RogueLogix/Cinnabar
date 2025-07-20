package graphics.cinnabar.core.vk.descriptors;

import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.exceptions.RedirectImplemented;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.api.vk.VulkanNameable;
import graphics.cinnabar.api.vk.VulkanObject;

public interface IDescriptorPool extends Destroyable {
    
    @ThreadSafety.Any
    @ThreadSafety.VulkanObjectHandle
    long allocSet(long layout);
    
    @ThreadSafety.Any
    @ThreadSafety.VulkanObjectHandle
    void reset();
}
