package graphics.cinnabar.core.vk.descriptors;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.core.vk.VulkanObject;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.util.List;

import static org.lwjgl.vulkan.EXTDebugReport.VK_DEBUG_REPORT_OBJECT_TYPE_DESCRIPTOR_SET_EXT;
import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_DESCRIPTOR_TYPE_INLINE_UNIFORM_BLOCK;

@API
public class DescriptorSet implements VulkanObject {
    
    private final DescriptorSetLayout layout;
    @Nullable
    private final IDescriptorPool pool;
    
    private final long handle;
    
    public final List<UBOBinding> ubos;
    public final List<SamplerBinding> samplers;
    private final VkWriteDescriptorSetInlineUniformBlock.Buffer uboInfo;
    private final VkDescriptorImageInfo.Buffer imageInfo;
    private final VkWriteDescriptorSet.Buffer setWrite;
    
    
    public DescriptorSet(DescriptorSetLayout layout, @Nullable IDescriptorPool pool) {
        this.layout = layout;
        this.pool = pool;
        handle = pool != null ? pool.allocSet(layout.handle()) : VK_NULL_HANDLE;
        ubos = layout.bindings.stream().filter(binding -> binding instanceof UBOBinding).map(a -> (UBOBinding) a).toList();
        samplers = layout.bindings.stream().filter(binding -> binding instanceof SamplerBinding).map(a -> (SamplerBinding) a).toList();
        uboInfo = VkWriteDescriptorSetInlineUniformBlock.calloc(ubos.size());
        imageInfo = VkDescriptorImageInfo.calloc(samplers.size());
        setWrite = VkWriteDescriptorSet.calloc(ubos.size() + samplers.size()).sType$Default();
        initSetWrite();
    }
    
    DescriptorSet(DescriptorSetLayout layout, IDescriptorPool pool, List<UBOBinding> ubos, List<SamplerBinding> samplers) {
        this.layout = layout;
        this.pool = pool;
        handle = pool.allocSet(layout.handle());
        this.ubos = ubos;
        this.samplers = samplers;
        uboInfo = VkWriteDescriptorSetInlineUniformBlock.calloc(ubos.size());
        imageInfo = VkDescriptorImageInfo.calloc(samplers.size());
        setWrite = VkWriteDescriptorSet.calloc(ubos.size() + samplers.size()).sType$Default();
        initSetWrite();
    }
    
    private void initSetWrite() {
        for (UBOBinding ubo : ubos) {
            setWrite.dstSet(handle);
            setWrite.pNext(uboInfo.address());
            setWrite.dstBinding(ubo.bindingPoint());
            setWrite.dstArrayElement(0);
            setWrite.descriptorType(VK_DESCRIPTOR_TYPE_INLINE_UNIFORM_BLOCK);
            setWrite.descriptorCount(1);
            setWrite.pBufferInfo(null);
            setWrite.pImageInfo(null);
            setWrite.position(setWrite.position() + 1);
            uboInfo.position(uboInfo.position() + 1);
        }
        uboInfo.position(0);
        for (SamplerBinding sampler : samplers) {
            setWrite.dstSet(handle);
            setWrite.dstBinding(sampler.bindingPoint());
            setWrite.dstArrayElement(0);
            setWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            setWrite.descriptorCount(1);
            setWrite.pBufferInfo(null);
            setWrite.pImageInfo(imageInfo);
            setWrite.position(setWrite.position() + 1);
        }
        setWrite.position(0);
    }
    
    @Override
    public void destroy() {
        setWrite.free();
    }
    
    @Override
    public long handle() {
        return handle;
    }
    
    @Override
    public int objectType() {
        return VK_DEBUG_REPORT_OBJECT_TYPE_DESCRIPTOR_SET_EXT;
    }
    
    public DescriptorSet createCopy() {
        if (pool == null || handle == VK_NULL_HANDLE) {
            throw new IllegalStateException("Cannot create copy of descriptor set created without pool (only usable as push set)");
        }
        final var other = new DescriptorSet(layout, pool, ubos, samplers);
        copyTo(other);
        return other;
    }
    
    public void copyTo(DescriptorSet other) {
        if (other.layout != layout) {
            throw new IllegalArgumentException("Cannot copy to descriptor set with different layout");
        }
        if (handle == VK_NULL_HANDLE) {
            throw new IllegalStateException("Cannot copy from descriptor set created without pool (only usable as push set)");
        }
        try (final var stack = MemoryStack.stackPush()) {
            final var copies = VkCopyDescriptorSet.calloc(ubos.size() + samplers.size(), stack);
            for (UBOBinding ubo : ubos) {
                copies.sType$Default();
                copies.srcSet(handle);
                copies.srcBinding(ubo.bindingPoint());
                copies.srcArrayElement(0);
                copies.dstSet(other.handle);
                copies.dstBinding(ubo.bindingPoint());
                copies.dstArrayElement(0);
                copies.descriptorCount(1);
                copies.position(copies.position() + 1);
            }
            for (SamplerBinding sampler : samplers) {
                copies.sType$Default();
                copies.srcSet(handle);
                copies.srcBinding(sampler.bindingPoint());
                copies.srcArrayElement(0);
                copies.dstSet(other.handle);
                copies.dstBinding(sampler.bindingPoint());
                copies.dstArrayElement(0);
                copies.descriptorCount(1);
                copies.position(copies.position() + 1);
            }
            copies.position(0);
            vkUpdateDescriptorSets(layout.device.vkDevice, null, copies);
        }
    }
    
    @ThreadSafety.Any
    @ThreadSafety.VulkanObjectHandle
    public void updateUBO(UBOBinding.Staging buffer) {
        uboInfo.position(ubos.indexOf(buffer.binding()));
        uboInfo.pData(MemoryUtil.memByteBuffer(buffer.stagingData().pointer(), (int) buffer.stagingData().size()));
    }
    
    @ThreadSafety.Any
    @ThreadSafety.VulkanObjectHandle
    public void updateImage(SamplerBinding binding, long samplerHandle, long imageViewHandle) {
        imageInfo.position(samplers.indexOf(binding));
        imageInfo.sampler(samplerHandle);
        imageInfo.imageView(imageViewHandle);
        imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }
    
    @ThreadSafety.Any
    @ThreadSafety.VulkanObjectHandle
    public void writeUpdates() {
        if (handle == VK_NULL_HANDLE) {
            throw new IllegalStateException("Cannot write update to descriptor set created without pool (only usable as push set)");
        }
        vkUpdateDescriptorSets(layout.device.vkDevice, setWrite, null);
    }
    
    @ThreadSafety.Many
    public void bind(VkCommandBuffer commandBuffer, long pipelineLayout, int index) {
        if (handle == VK_NULL_HANDLE) {
            throw new IllegalStateException("Cannot bind descriptor set created without pool (only usable as push set)");
        }
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, index, new long[]{handle}, new int[]{0});
    }
    
    @ThreadSafety.Many
    public void push(VkCommandBuffer commandBuffer, long pipelineLayout, int index) {
        // TODO: incremental updates?
        vkCmdPushDescriptorSetKHR(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, index, setWrite);
    }
}