package graphics.cinnabar.core.b3d.texture;

import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.api.b3dext.textures.ExtGpuTexture;
import graphics.cinnabar.api.cvk.textures.CVKGpuTextureView;
import graphics.cinnabar.api.vk.VulkanObject;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static graphics.cinnabar.core.b3d.texture.CinnabarGpuTexture.aspects;
import static graphics.cinnabar.core.b3d.texture.CinnabarGpuTexture.toVk;
import static org.lwjgl.vulkan.VK10.*;

public class CinnabarGpuTextureView extends CVKGpuTextureView implements VulkanObject {
    private final CinnabarDevice device;
    
    private final CinnabarGpuTexture texture;
    public final long imageViewHandle;
    private boolean closed = false;
    private final Long2ReferenceMap<Reference2LongMap<CinnabarGpuTextureView>> framebuffers = new Long2ReferenceOpenHashMap<>();
    
    public CinnabarGpuTextureView(CinnabarDevice device, CinnabarGpuTexture texture, ExtGpuTexture.Type type, TextureFormat format, int baseMipLevel, int levelCount, int baseArrayLayer, int layerCount) {
        super(texture, type, format, baseMipLevel, levelCount, baseArrayLayer, layerCount);
        this.device = device;
        this.texture = texture;
        try (final var stack = MemoryStack.stackPush()) {
            final var imageViewCreateInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
            imageViewCreateInfo.image(texture.imageHandle);
            imageViewCreateInfo.viewType(type.ordinal()); // they "happen" to be done in ordinal order to match VK 
            imageViewCreateInfo.format(toVk(format));
            imageViewCreateInfo.components().set(VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY);
            final var subresourceRange = imageViewCreateInfo.subresourceRange();
            subresourceRange.aspectMask(aspects(format));
            subresourceRange.baseMipLevel(baseMipLevel);
            subresourceRange.levelCount(levelCount);
            subresourceRange.baseArrayLayer(baseArrayLayer);
            subresourceRange.layerCount(layerCount);
            
            final var longPtr = stack.callocLong(1);
            checkVkCode(vkCreateImageView(device.vkDevice, imageViewCreateInfo, null, longPtr));
            imageViewHandle = longPtr.get(0);
        }
        texture.addView();
    }
    
    @Override
    public void destroy() {
        for (Reference2LongMap<CinnabarGpuTextureView> value : framebuffers.values()) {
            for (Long framebufferHandle : value.values()) {
                vkDestroyFramebuffer(device.vkDevice, framebufferHandle, null);
            }
        }
        vkDestroyImageView(device.vkDevice, imageViewHandle, null);
    }
    
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        device.destroyEndOfFrame(this);
        texture.removeView();
    }
    
    @Override
    public boolean isClosed() {
        return closed;
    }
    
    @Override
    public long handle() {
        return imageViewHandle;
    }
    
    @Override
    public int objectType() {
        return VK_OBJECT_TYPE_IMAGE_VIEW;
    }
    
    public long getFramebuffer(long renderPass, @Nullable CinnabarGpuTextureView depthAttachment) {
        final var passFramebuffers = framebuffers.computeIfAbsent(renderPass, i -> new Reference2LongArrayMap<>());
        final var framebuffer = passFramebuffers.getLong(depthAttachment);
        if (framebuffer != VK_NULL_HANDLE) {
            return framebuffer;
        }
        try (final var stack = MemoryStack.stackPush()) {
            final var createInfo = VkFramebufferCreateInfo.calloc(stack).sType$Default();
            createInfo.renderPass(renderPass);
            if (depthAttachment == null) {
                createInfo.attachmentCount(1);
                createInfo.pAttachments(stack.longs(imageViewHandle));
            } else {
                createInfo.attachmentCount(2);
                createInfo.pAttachments(stack.longs(imageViewHandle, depthAttachment.imageViewHandle));
            }
            createInfo.width(texture.getWidth(0));
            createInfo.height(texture.getHeight(0));
            createInfo.layers(1);
            final var handlePtr = stack.longs(0);
            vkCreateFramebuffer(device.vkDevice, createInfo, null, handlePtr);
            passFramebuffers.put(depthAttachment, handlePtr.get(0));
            return handlePtr.get(0);
        }
    }
}
