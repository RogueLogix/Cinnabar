package graphics.cinnabar.core.b3d.texture;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.VulkanObject;
import graphics.cinnabar.core.vk.VulkanSampler;
import graphics.cinnabar.core.vk.memory.VkMemoryAllocation;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import static com.mojang.blaze3d.textures.FilterMode.LINEAR;
import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.VK10.*;

public class CinnabarGpuTexture extends GpuTexture implements VulkanObject {
    private final CinnabarDevice device;
    
    private boolean closed = false;
    public final long imageHandle;
    private final VkMemoryAllocation memoryAllocation;
    private int liveViews = 0;
    
    public CinnabarGpuTexture(CinnabarDevice device, int usage, String label, TextureFormat format, int width, int height, int depth, int mips) {
        super(usage, label, format, width, height, depth, mips);
        this.device = device;
        final var cubemap = (usage & USAGE_CUBEMAP_COMPATIBLE) != 0;
        try (final var stack = MemoryStack.stackPush()) {
            final var imageCreateInfo = VkImageCreateInfo.calloc(stack).sType$Default();
            imageCreateInfo.imageType(VK_IMAGE_TYPE_2D);
            imageCreateInfo.extent().set(width, height, cubemap ? 1 : depth);
            imageCreateInfo.mipLevels(mips);
            imageCreateInfo.arrayLayers(cubemap ? depth : 1);
            imageCreateInfo.format(toVk(format));
            imageCreateInfo.tiling(VK_IMAGE_TILING_OPTIMAL);
            imageCreateInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageCreateInfo.usage(vkUsageBits(usage, format.hasColorAspect()));
            imageCreateInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            imageCreateInfo.samples(VK_SAMPLE_COUNT_1_BIT); // TODO: MSAA would be cool
            imageCreateInfo.flags(cubemap ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0);
            
            final var longPtr = stack.callocLong(1);
            checkVkCode(vkCreateImage(device.vkDevice, imageCreateInfo, null, longPtr));
            imageHandle = longPtr.get(0);
            
            final var memoryRequirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device.vkDevice, imageHandle, memoryRequirements);
            
            memoryAllocation = device.devicePersistentMemoryPool.alloc(memoryRequirements);
            vkBindImageMemory(device.vkDevice, imageHandle, memoryAllocation.memoryHandle, memoryAllocation.range.offset());
            
            
        }
        setVulkanName(label);
    }
    
    @Override
    public boolean isClosed() {
        return closed;
    }
    
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (liveViews == 0) {
            device.destroyEndOfFrame(this);
        }
    }
    
    @Override
    public void destroy() {
        vkDestroyImage(device.vkDevice, imageHandle, null);
        memoryAllocation.destroy();
    }
    
    public VulkanSampler sampler() {
        // TODO: split U/V edge mode
        return VulkanSampler.DEFAULT.withEdgeMode(switch (this.addressModeU) {
            case REPEAT -> VK_SAMPLER_ADDRESS_MODE_REPEAT;
            case CLAMP_TO_EDGE -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        }).withMipmap(this.useMipmaps).withMinMagLinear(this.minFilter == LINEAR);
    }
    
    public static int toVk(TextureFormat format) {
        return switch (format) {
            case RGBA8 -> VK_FORMAT_R8G8B8A8_UNORM;
            case RED8 -> VK_FORMAT_R8_UNORM;
            case RED8I -> VK_FORMAT_R8_SINT;
            case DEPTH32 -> VK_FORMAT_D32_SFLOAT;
            case DEPTH24_STENCIL8 -> VK_FORMAT_D24_UNORM_S8_UINT;
            case DEPTH32_STENCIL8 -> VK_FORMAT_D32_SFLOAT_S8_UINT;
        };
    }
    
    public static int aspects(TextureFormat format) {
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
    public long handle() {
        return imageHandle;
    }
    
    @Override
    public int objectType() {
        return VK_OBJECT_TYPE_IMAGE;
    }
    
    private static int vkUsageBits(int b3dUsage, boolean color) {
        int bits = 0;
        // must always have TRANSFER_DST for uploads
        bits |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if ((b3dUsage & USAGE_COPY_SRC) != 0) {
            bits |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        }
        if ((b3dUsage & USAGE_TEXTURE_BINDING) != 0) {
            bits |= VK_IMAGE_USAGE_SAMPLED_BIT;
        }
        if ((b3dUsage & USAGE_RENDER_ATTACHMENT) != 0) {
            bits |= color ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
        }
        return bits;
    }
    
    public void addView() {
        liveViews++;
    }
    
    public void removeView() {
        liveViews--;
        if (closed && liveViews == 0) {
            device.destroyEndOfFrame(this);
        }
    }
}
