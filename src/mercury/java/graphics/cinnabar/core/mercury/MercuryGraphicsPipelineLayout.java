package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgGraphicsPipeline;
import graphics.cinnabar.api.hg.HgUniformSet;
import it.unimi.dsi.fastutil.longs.LongIntImmutablePair;
import it.unimi.dsi.fastutil.objects.ReferenceImmutableList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

import java.util.List;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.VK10.*;

public class MercuryGraphicsPipelineLayout extends MercuryObject<HgGraphicsPipeline.Layout> implements HgGraphicsPipeline.Layout {
    private final long handle;
    private final List<HgUniformSet.Layout> uniformSetLayouts;
    
    public MercuryGraphicsPipelineLayout(MercuryDevice device, HgGraphicsPipeline.Layout.CreateInfo createInfo) {
        super(device);
        this.uniformSetLayouts = new ReferenceImmutableList<>(createInfo.uniformLayouts());
        try (final var stack = memoryStack().push()) {
            final var pipelineLayoutCreateInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            final var descriptorSetLayoutHandles = stack.callocLong(createInfo.uniformLayouts().size());
            for (int i = 0; i < createInfo.uniformLayouts().size(); i++) {
                descriptorSetLayoutHandles.put(i, ((MercuryUniformSetLayout) createInfo.uniformLayouts().get(i)).vkDescriptorSetLayout());
            }
            if (createInfo.pushConstantSize() != 0) {
                // TODO: shader stages for push constant ranges?
                pipelineLayoutCreateInfo.pPushConstantRanges(VkPushConstantRange.calloc(1, stack).offset(0).size(createInfo.pushConstantSize()).stageFlags(VK_SHADER_STAGE_ALL));
            }
            pipelineLayoutCreateInfo.pSetLayouts(descriptorSetLayoutHandles);
            
            final var longPtr = stack.callocLong(1);
            checkVkCode(vkCreatePipelineLayout(device.vkDevice(), pipelineLayoutCreateInfo, null, longPtr));
            handle = longPtr.get(0);
        }
        
    }
    
    @Override
    public void destroy() {
        vkDestroyPipelineLayout(device.vkDevice(), handle, null);
    }
    
    public long vkPipelineLayout() {
        return handle;
    }
    
    @Override
    public int maximumUniformSetIndex() {
        return uniformSetLayouts.size();
    }
    
    @Override
    @Nullable
    public HgUniformSet.Layout uniformSetLayout(int setIndex) {
        return uniformSetLayouts.get(setIndex);
    }
    
    @Override
    protected LongIntImmutablePair handleAndType() {
        return new LongIntImmutablePair(handle, VK_OBJECT_TYPE_PIPELINE_LAYOUT);
    }
}
