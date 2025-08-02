package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgUniformSet;
import it.unimi.dsi.fastutil.objects.ReferenceImmutableList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;

import java.util.List;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.VK10.*;

public class MercuryUniformSetLayout extends MercuryObject implements HgUniformSet.Layout {
    public final long handle;
    private final List<Binding> bindings;
    
    public MercuryUniformSetLayout(MercuryDevice device, HgUniformSet.Layout.CreateInfo createInfo) {
        super(device);
        this.bindings = new ReferenceImmutableList<>(createInfo.bindings());
        
        try (final var stack = MemoryStack.stackPush()) {
            
            final var vkBindings = VkDescriptorSetLayoutBinding.calloc(createInfo.bindings().size(), stack);
            for (int i = 0; i < createInfo.bindings().size(); i++) {
                final var binding = createInfo.bindings().get(i);
                vkBindings.position(i);
                vkBindings.binding(binding.location());
                vkBindings.descriptorType(MercuryConst.vkDescriptorType(binding.type()));
                vkBindings.descriptorCount(binding.count());
                vkBindings.stageFlags(VK_SHADER_STAGE_ALL); // TODO: stage flags properly?
            }
            vkBindings.position(0);
            
            final var vkCreateInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            vkCreateInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            vkCreateInfo.pBindings(vkBindings);
            
            final var longPtr = stack.callocLong(1);
            checkVkCode(vkCreateDescriptorSetLayout(device.vkDevice(), vkCreateInfo, null, longPtr));
            handle = longPtr.get(0);
        }
    }
    
    @Override
    public void destroy() {
        vkDestroyDescriptorSetLayout(device.vkDevice(), handle, null);
        
    }
    
    @Override
    public List<Binding> bindings() {
        return bindings;
    }
    
    public long vkDescriptorSetLayout() {
        return handle;
    }
    
    @Override
    public MercuryUniformSetPool createPool(HgUniformSet.Pool.CreateInfo createInfo) {
        return new MercuryUniformSetPool(this, createInfo);
    }
}
