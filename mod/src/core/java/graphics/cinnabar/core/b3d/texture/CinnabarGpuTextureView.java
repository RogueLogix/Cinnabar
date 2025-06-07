package graphics.cinnabar.core.b3d.texture;

import graphics.cinnabar.api.cvk.textures.CVKTextureView;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.VulkanObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import static com.mojang.blaze3d.textures.GpuTexture.USAGE_CUBEMAP_COMPATIBLE;
import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static graphics.cinnabar.core.b3d.texture.CinnabarGpuTexture.aspects;
import static graphics.cinnabar.core.b3d.texture.CinnabarGpuTexture.toVk;
import static org.lwjgl.vulkan.VK10.*;

public class CinnabarGpuTextureView extends CVKTextureView implements VulkanObject {
    private final CinnabarDevice device;
    
    private final CinnabarGpuTexture texture;
    public final long imageViewHandle;
    private boolean closed = false;
    
    public CinnabarGpuTextureView(CinnabarDevice device, CinnabarGpuTexture texture, int baseMipLevel, int levelCount) {
        super(texture, baseMipLevel, levelCount);
        this.device = device;
        this.texture = texture;
        final var cubemap = (texture.usage() & USAGE_CUBEMAP_COMPATIBLE) != 0;
        try (final var stack = MemoryStack.stackPush()) {
            final var imageViewCreateInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
            imageViewCreateInfo.image(texture.imageHandle);
            imageViewCreateInfo.viewType(cubemap ? VK_IMAGE_VIEW_TYPE_CUBE : VK_IMAGE_VIEW_TYPE_2D);
            imageViewCreateInfo.format(toVk(texture.getFormat()));
            imageViewCreateInfo.components().set(VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_G, VK_COMPONENT_SWIZZLE_B, VK_COMPONENT_SWIZZLE_A);
            final var subresourceRange = imageViewCreateInfo.subresourceRange();
            subresourceRange.aspectMask(aspects(texture.getFormat()));
            subresourceRange.baseMipLevel(baseMipLevel);
            subresourceRange.levelCount(levelCount);
            subresourceRange.baseArrayLayer(0);
            subresourceRange.layerCount(cubemap ? texture.getDepthOrLayers() : 1);
            
            final var longPtr = stack.callocLong(1);
            checkVkCode(vkCreateImageView(device.vkDevice, imageViewCreateInfo, null, longPtr));
            imageViewHandle = longPtr.get(0);
        }
        texture.addView();
    }
    
    @Override
    public void destroy() {
        vkDestroyImageView(device.vkDevice, imageViewHandle, null);
    }
    
    @Override
    public void close() {
        if (closed){
           return; 
        }
        closed = true;
        texture.removeView();
        device.destroyEndOfFrame(this);
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
}
