package graphics.cinnabar.core.b3d.texture;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.api.CinnabarAPI;
import graphics.cinnabar.api.CinnabarGpuDevice;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.VulkanNameable;
import graphics.cinnabar.core.vk.VulkanObject;
import graphics.cinnabar.core.vk.VulkanSampler;
import graphics.cinnabar.core.vk.memory.VkMemoryAllocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDebugMarkerObjectNameInfoEXT;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import static com.mojang.blaze3d.textures.FilterMode.LINEAR;
import static com.mojang.blaze3d.textures.FilterMode.NEAREST;
import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.EXTDebugMarker.vkDebugMarkerSetObjectNameEXT;
import static org.lwjgl.vulkan.VK10.*;

public class CinnabarGpuTexture extends GpuTexture implements Destroyable, VulkanNameable {
    private final CinnabarDevice device;
    
    private boolean isClosed = false;
    public final long imageHandle;
    public final long imageViewHandle;
    private final VkMemoryAllocation memoryAllocation;
    
    public CinnabarGpuTexture(CinnabarDevice device, String label, TextureFormat format, int width, int height, int mips) {
        super(label, format, width, height, mips);
        this.device = device;
        try (final var stack = MemoryStack.stackPush()) {
            final var imageCreateInfo = VkImageCreateInfo.calloc(stack).sType$Default();
            imageCreateInfo.imageType(VK_IMAGE_TYPE_2D);
            imageCreateInfo.extent().set(width, height, 1);
            imageCreateInfo.mipLevels(mips);
            imageCreateInfo.arrayLayers(1);
            imageCreateInfo.format(toVk(format));
            imageCreateInfo.tiling(VK_IMAGE_TILING_OPTIMAL);
            imageCreateInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            final int attachmentBit = format.hasColorAspect() ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
            imageCreateInfo.usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | attachmentBit);
            imageCreateInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            imageCreateInfo.samples(VK_SAMPLE_COUNT_1_BIT); // TODO: MSAA would be cool
            imageCreateInfo.flags(0);
            
            final var longPtr = stack.callocLong(1);
            checkVkCode(vkCreateImage(device.vkDevice, imageCreateInfo, null, longPtr));
            imageHandle = longPtr.get(0);
            
            final var memoryRequirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device.vkDevice, imageHandle, memoryRequirements);
            
            memoryAllocation = device.devicePersistentMemoryPool.alloc(memoryRequirements);
            vkBindImageMemory(device.vkDevice, imageHandle, memoryAllocation.memoryHandle, memoryAllocation.range.offset());
            
            final var imageViewCreateInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
            imageViewCreateInfo.image(imageHandle);
            imageViewCreateInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            imageViewCreateInfo.format(toVk(format));
            imageViewCreateInfo.components().set(VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_G, VK_COMPONENT_SWIZZLE_B, VK_COMPONENT_SWIZZLE_A);
            final var subresourceRange = imageViewCreateInfo.subresourceRange();
            subresourceRange.aspectMask(aspects(format));
            subresourceRange.baseMipLevel(0);
            subresourceRange.levelCount(mips);
            subresourceRange.baseArrayLayer(0);
            subresourceRange.layerCount(1);
            
            checkVkCode(vkCreateImageView(device.vkDevice, imageViewCreateInfo, null, longPtr));
            imageViewHandle = longPtr.get(0);
        }
        setVulkanName(label);
    }
    
    @Override
    public boolean isClosed() {
        return isClosed;
    }
    
    @Override
    public void close() {
        if (isClosed) {
            return;
        }
        isClosed = true;
        device.destroyEndOfFrame(this);
    }
    
    @Override
    public void destroy() {
        vkDestroyImage(device.vkDevice, imageHandle, null);
        vkDestroyImageView(device.vkDevice, imageViewHandle, null);
        memoryAllocation.destroy();
    }
    
    public VulkanSampler sampler() {
        // TODO: split U/V edge mode
        return VulkanSampler.DEFAULT.withEdgeMode(switch (this.addressModeU) {
            case REPEAT -> VK_SAMPLER_ADDRESS_MODE_REPEAT;
            case CLAMP_TO_EDGE -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        }).withMipmap(this.useMipmaps).withMinMagLinear(this.minFilter == LINEAR);
    }
    
    private static int toVk(TextureFormat format) {
        return switch (format) {
            case RGBA8 -> VK_FORMAT_R8G8B8A8_UNORM;
            case RED8 -> VK_FORMAT_R8_UNORM;
            case DEPTH32 -> VK_FORMAT_D32_SFLOAT;
            case DEPTH24_STENCIL8 -> VK_FORMAT_D24_UNORM_S8_UINT;
            case DEPTH32_STENCIL8 -> VK_FORMAT_D32_SFLOAT_S8_UINT;
        };
    }
    
    private static int aspects(TextureFormat format) {
        int aspects = 0;
        if (format.hasColorAspect()) {
            aspects |= VK_IMAGE_ASPECT_COLOR_BIT;
        }
        if (format.hasDepthAspect()) {
            aspects |= VK_IMAGE_ASPECT_DEPTH_BIT;
        }
        if (format.hasStencilAspect()) {
            aspects |= VK_IMAGE_ASPECT_STENCIL_BIT;
        }
        return aspects;
    }
    
    public int aspectMask() {
        return aspects(getFormat());
    }
    
    @Override
    public void setVulkanName(@Nullable String name) {
        if (!CinnabarAPI.Internals.DEBUG_MARKER_ENABLED || imageHandle == VK_NULL_HANDLE || imageViewHandle == VK_NULL_HANDLE || name == null) {
            return;
        }
        try (final var stack = MemoryStack.stackPush()) {
            final var nameInfo = VkDebugMarkerObjectNameInfoEXT.calloc(stack).sType$Default();
            nameInfo.objectType(VK_OBJECT_TYPE_IMAGE);
            nameInfo.object(imageHandle);
            nameInfo.pObjectName(stack.UTF8(name));
            vkDebugMarkerSetObjectNameEXT(CinnabarGpuDevice.get().vkDevice(), nameInfo);
            nameInfo.objectType(VK_OBJECT_TYPE_IMAGE_VIEW);
            nameInfo.object(imageViewHandle);
            nameInfo.pObjectName(stack.UTF8(name));
            vkDebugMarkerSetObjectNameEXT(CinnabarGpuDevice.get().vkDevice(), nameInfo);
        }
    }
}
