package graphics.cinnabar.core.vk.descriptors;

import graphics.cinnabar.api.vk.VulkanObject;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import it.unimi.dsi.fastutil.objects.ReferenceImmutableList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;

import java.util.List;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.EXTDebugReport.VK_DEBUG_REPORT_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT_EXT;
import static org.lwjgl.vulkan.VK10.*;

public class DescriptorSetLayout implements VulkanObject {
    
    public final CinnabarDevice device;
    public final long handle;
    public final ReferenceImmutableList<DescriptorSetBinding> bindings;
    
    public DescriptorSetLayout(CinnabarDevice device, List<DescriptorSetBinding> bindings) {
        this.device = device;
        this.bindings = new ReferenceImmutableList<>(bindings);
        
        try (final var stack = MemoryStack.stackPush()) {
            
            final var vkBindings = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
            for (int i = 0; i < bindings.size(); i++) {
                final var binding = bindings.get(i);
                vkBindings.position(i);
                vkBindings.binding(binding.bindingPoint());
                vkBindings.descriptorType(binding.type());
                vkBindings.descriptorCount(binding.count());
                vkBindings.stageFlags(binding.stageFlags());
            }
            vkBindings.position(0);
            
            final var createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            createInfo.pBindings(vkBindings);
            
            final var longPtr = stack.callocLong(1);
            checkVkCode(vkCreateDescriptorSetLayout(device.vkDevice, createInfo, null, longPtr));
            handle = longPtr.get(0);
        }
    }
    
    @Override
    public void destroy() {
        vkDestroyDescriptorSetLayout(device.vkDevice, handle, null);
    }
    
    @Override
    public long handle() {
        return handle;
    }
    
    @Override
    public int objectType() {
        return VK_DEBUG_REPORT_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT_EXT;
    }
}
