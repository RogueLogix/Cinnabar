package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgImage;
import graphics.cinnabar.api.hg.enums.HgFormat;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class MercuryImage extends MercuryObject implements HgImage {
    private final HgImage.Type type;
    private final HgFormat format;
    private final int width;
    private final int height;
    private final int depth;
    private final int layers;
    private final int levelCount;
    
    private final long imageHandle;
    private final long vmaAllocation;
    
    public MercuryImage(MercuryDevice device, Type type, HgFormat format, int width, int height, int depth, int layers, int levelCount, long usage, int flags, boolean hostMemory) {
        super(device);
        this.type = type;
        this.format = format;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.layers = layers;
        this.levelCount = levelCount;
        
        try (final var stack = MemoryStack.stackPush()) {
            final var imageCreateInfo = VkImageCreateInfo.calloc(stack).sType$Default();
            imageCreateInfo.imageType(type.ordinal());
            imageCreateInfo.extent().set(width, height, depth);
            imageCreateInfo.mipLevels(levelCount);
            imageCreateInfo.arrayLayers(layers);
            imageCreateInfo.format(MercuryConst.vkFormat(format));
            imageCreateInfo.tiling(VK_IMAGE_TILING_OPTIMAL);
            imageCreateInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageCreateInfo.usage(Math.toIntExact(usage));
            imageCreateInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            imageCreateInfo.samples(VK_SAMPLE_COUNT_1_BIT); // TODO: MSAA would be cool
            imageCreateInfo.flags(flags);
            
            final var allocCreateInfo = VmaAllocationCreateInfo.calloc(stack);
            allocCreateInfo.usage(hostMemory ? VMA_MEMORY_USAGE_AUTO_PREFER_HOST : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            final var imagePtr = stack.callocLong(1);
            final var allocationPtr = stack.callocPointer(1);
            checkVkCode(vmaCreateImage(device.vmaAllocator(), imageCreateInfo, allocCreateInfo, imagePtr, allocationPtr, null));
            imageHandle = imagePtr.get(0);
            vmaAllocation = allocationPtr.get(0);
        }
    }
    
    @Override
    public void destroy() {
        vmaDestroyImage(device.vmaAllocator(), imageHandle, vmaAllocation);
    }
    
    public Type type() {
        return type;
    }
    
    public HgFormat format() {
        return format;
    }
    
    public long vkImage() {
        return imageHandle;
    }
    
    @Override
    public int width() {
        return width;
    }
    
    @Override
    public int height() {
        return height;
    }
    
    @Override
    public int depth() {
        return depth;
    }
    
    @Override
    public int layerCount() {
        return layers;
    }
    
    @Override
    public int levelCount() {
        return levelCount;
    }
    
    @Override
    public View createView(View.Type viewType, HgFormat format, int baseMipLevel, int levelCount, int baseArrayLayer, int layerCount) {
        return new MercuryImageView(this, viewType, format, baseMipLevel, levelCount, baseArrayLayer, layerCount);
    }
}
