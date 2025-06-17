package graphics.cinnabar.core.b3d.texture;

import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.api.cvk.textures.CVKGpuTexture;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.api.vk.VulkanObject;
import graphics.cinnabar.core.vk.VulkanSampler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.*;

public class CinnabarGpuTexture extends CVKGpuTexture implements VulkanObject {
    private final CinnabarDevice device;
    
    private boolean closed = false;
    public final long imageHandle;
    public final long vmaAllocation;
    private int liveViews = 0;
    
    public CinnabarGpuTexture(CinnabarDevice device, int usage, String label, Type type, TextureFormat format, int width, int height, int depth, int layers, int mips) {
        super(usage, label, type, format, width, height, depth, layers, mips);
        this.device = device;
        final var cubemap = (usage & USAGE_CUBEMAP_COMPATIBLE) != 0;
        try (final var stack = MemoryStack.stackPush()) {
            final var imageCreateInfo = VkImageCreateInfo.calloc(stack).sType$Default();
            imageCreateInfo.imageType(switch (type) {
                case TYPE_1D, TYPE_1D_ARRAY -> VK_IMAGE_TYPE_1D;
                case TYPE_2D, TYPE_2D_ARRAY, TYPE_CUBE, TYPE_CUBE_ARRAY -> VK_IMAGE_TYPE_2D;
                case TYPE_3D -> VK_IMAGE_TYPE_3D;
            });
            imageCreateInfo.extent().set(width, height, depth);
            imageCreateInfo.mipLevels(mips);
            imageCreateInfo.arrayLayers(layers);
            imageCreateInfo.format(toVk(format));
            imageCreateInfo.tiling(VK_IMAGE_TILING_OPTIMAL);
            imageCreateInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageCreateInfo.usage(vkUsageBits(usage, format.hasColorAspect()));
            imageCreateInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            imageCreateInfo.samples(VK_SAMPLE_COUNT_1_BIT); // TODO: MSAA would be cool
            imageCreateInfo.flags(cubemap ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0);
            
            final var allocCreateInfo = VmaAllocationCreateInfo.calloc(stack);
            allocCreateInfo.memoryTypeBits(1 << device.deviceMemoryType.leftInt());
            final var imagePtr = stack.callocLong(1);
            final var allocationPtr = stack.callocPointer(1);
            checkVkCode(vmaCreateImage(device.vmaAllocator, imageCreateInfo, allocCreateInfo, imagePtr, allocationPtr, null));
            imageHandle = imagePtr.get(0);
            vmaAllocation = allocationPtr.get(0);
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
        vmaDestroyImage(device.vmaAllocator, imageHandle, vmaAllocation);
    }
    
    public VulkanSampler sampler() {
        return new VulkanSampler.State(minFilter.ordinal(), magFilter.ordinal(), useMipmaps, addressModeU.ordinal() * 2, addressModeV.ordinal() * 2, 0).sampler();
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
