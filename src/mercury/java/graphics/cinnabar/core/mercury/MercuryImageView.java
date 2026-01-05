package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgImage;
import graphics.cinnabar.api.hg.enums.HgFormat;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.VK10.*;

public class MercuryImageView extends MercuryObject implements HgImage.View {
    
    private final long imageViewHandle;
    private final MercuryImage image;
    private final Type viewType;
    private final HgFormat format;
    private final int baseMipLevel;
    private final int levelCount;
    private final int baseArrayLayer;
    private final int layerCount;
    
    public MercuryImageView(MercuryImage image, Type viewType, HgFormat format, int baseMipLevel, int levelCount, int baseArrayLayer, int layerCount) {
        super(image.device);
        this.image = image;
        this.viewType = viewType;
        this.format = format;
        this.baseMipLevel = baseMipLevel;
        this.levelCount = levelCount;
        this.baseArrayLayer = baseArrayLayer;
        this.layerCount = layerCount;
        try (final var stack = memoryStack().push()) {
            final var imageViewCreateInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
            imageViewCreateInfo.image(image.vkImage());
            imageViewCreateInfo.viewType(viewType.ordinal());
            imageViewCreateInfo.format(MercuryConst.vkFormat(format));
            // TODO: swizzle
            imageViewCreateInfo.components().set(VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY);
            final var subresourceRange = imageViewCreateInfo.subresourceRange();
            subresourceRange.aspectMask(format.aspects());
            subresourceRange.baseMipLevel(baseMipLevel);
            subresourceRange.levelCount(levelCount);
            subresourceRange.baseArrayLayer(baseArrayLayer);
            subresourceRange.layerCount(layerCount);
            
            final var longPtr = stack.callocLong(1);
            checkVkCode(vkCreateImageView(device.vkDevice(), imageViewCreateInfo, null, longPtr));
            imageViewHandle = longPtr.get(0);
        }
    }
    
    @Override
    public void destroy() {
        vkDestroyImageView(device.vkDevice(), imageViewHandle, null);
    }
    
    public long vkImageView() {
        return imageViewHandle;
    }
    
    @Override
    public MercuryImage image() {
        return image;
    }
    
    @Override
    public Type type() {
        return viewType;
    }
    
    @Override
    public HgFormat format() {
        return format;
    }
    
    @Override
    public int baseArrayLayer() {
        return baseArrayLayer;
    }
    
    @Override
    public int layerCount() {
        return layerCount;
    }
    
    @Override
    public int baseMipLevel() {
        return baseMipLevel;
    }
    
    @Override
    public int levelCount() {
        return levelCount;
    }
}
