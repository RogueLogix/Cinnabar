package graphics.cinnabar.internal.extensions.minecraft.renderer.texture;

import com.mojang.blaze3d.platform.NativeImage;
import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.exceptions.NotImplemented;
import graphics.cinnabar.internal.vulkan.memory.CPUMemoryVkBuffer;
import graphics.cinnabar.internal.vulkan.memory.VulkanMemoryAllocation;
import graphics.cinnabar.internal.vulkan.util.VulkanQueueHelper;
import graphics.cinnabar.internal.vulkan.util.VulkanSampler;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static graphics.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL30C.GL_RG;
import static org.lwjgl.vulkan.VK10.*;

@NonnullDefault
public abstract class CinnabarAbstractTexture extends AbstractTexture {
    private static int activeBindPoint = 0;
    
    private static CinnabarAbstractTexture[] currentBound = new CinnabarAbstractTexture[12];
    
    
    public static int activeBindPoint() {
        return activeBindPoint;
    }
    
    public static void active(int id) {
        activeBindPoint = id;
    }
    
    public static void bind(AbstractTexture texture, int index) {
        currentBound[index] = (CinnabarAbstractTexture) texture;
    }
    
    @Nullable
    public static CinnabarAbstractTexture currentActiveBound() {
        return currentBound[activeBindPoint];
    }
    
    @Nullable
    public static CinnabarAbstractTexture bound(int index) {
        return currentBound[index];
    }
    
    private final VkDevice device = CinnabarRenderer.device();
    private long imageHandle;
    private long imageViewHandle;
    private int imageFormat;
    
    @Nullable
    private VulkanMemoryAllocation memoryAllocation;
    private int width, height;
    
    private int mipLevels = -1;
    
    private VkBufferImageCopy.Buffer vkBufferImageCopy = VkBufferImageCopy.calloc(1);
    
    @Override
    public int getId() {
        // easier to return -1 than to rebind everything that calls this
        return -1;
    }
    
    @Override
    public void releaseId() {
        destroy();
    }
    
    @Override
    public void bind() {
        currentBound[activeBindPoint] = this;
    }
    
    public void prepareImage(int vkFormat, int maxMipLevel, int width, int height) {
        
        imageFormat = vkFormat;
        mipLevels = maxMipLevel + 1;
        this.width = width;
        this.height = height;
        
        bind();
        try (final var stack = MemoryStack.stackPush()) {
            final var imageCreateInfo = VkImageCreateInfo.calloc(stack).sType$Default();
            imageCreateInfo.imageType(VK_IMAGE_TYPE_2D);
            imageCreateInfo.extent().set(width, height, 1);
            imageCreateInfo.mipLevels(mipLevels);
            imageCreateInfo.arrayLayers(1);
            imageCreateInfo.format(imageFormat);
            imageCreateInfo.tiling(VK_IMAGE_TILING_OPTIMAL);
            imageCreateInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageCreateInfo.usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
            imageCreateInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            imageCreateInfo.samples(VK_SAMPLE_COUNT_1_BIT);
            imageCreateInfo.flags(0);
            
            final var longPtr = stack.callocLong(1);
            throwFromCode(vkCreateImage(device, imageCreateInfo, null, longPtr));
            imageHandle = longPtr.get(0);
            
            final var memoryRequirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, imageHandle, memoryRequirements);
            
            memoryAllocation = CinnabarRenderer.GPUMemoryAllocator.alloc(memoryRequirements);
            vkBindImageMemory(device, imageHandle, memoryAllocation.memoryHandle(), memoryAllocation.range().offset());
        }
        
        try (final var stack = MemoryStack.stackPush()) {
            final var imageViewCreateInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
            imageViewCreateInfo.image(imageHandle);
            imageViewCreateInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            imageViewCreateInfo.format(imageFormat);
            imageViewCreateInfo.components().set(VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_G, VK_COMPONENT_SWIZZLE_B, VK_COMPONENT_SWIZZLE_A);
            final var subresourceRange = imageViewCreateInfo.subresourceRange();
            subresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            subresourceRange.baseMipLevel(0);
            subresourceRange.levelCount(mipLevels);
            subresourceRange.baseArrayLayer(0);
            subresourceRange.layerCount(1);
            
            final var longPtr = stack.callocLong(1);
            
            vkCreateImageView(device, imageViewCreateInfo, null, longPtr);
            imageViewHandle = longPtr.get(0);
        }
        // no need to do a transition yet, upload will take care of that
    }
    
    private void destroy() {
        if (imageHandle != VK_NULL_HANDLE) {
            vkDestroyImage(device, imageHandle, null);
        }
        if (memoryAllocation != null) {
            CinnabarRenderer.GPUMemoryAllocator.free(memoryAllocation);
        }
        vkBufferImageCopy.free();
    }
    
    public long handle() {
        return imageHandle;
    }
    
    public long viewHandle() {
        return imageViewHandle;
    }
    
    public void recordUpload(NativeImage nativeImage, CPUMemoryVkBuffer cpuMemoryVkBuffer, int level, int xOffset, int yOffset, int unpackSkipPixels, int unpackSkipRows, int width, int height, boolean blur, boolean clamp, boolean mipmap) {
        
        // copy is directly from host NativeImage allocation to GPU memory
        // no stage for a format remap to be done
        if (glToVkFormatRemap(nativeImage.format().glFormat()) != imageFormat) {
            throw new NotImplemented("Implicit format conversion not supported");
        }
        
        final var texelSize = byteSizePerTexel(imageFormat);
        final int skipTexels = unpackSkipPixels + unpackSkipRows * nativeImage.getWidth();
        final long skipBytes = (long) skipTexels * texelSize;
        
        vkBufferImageCopy.bufferOffset(skipBytes);
        vkBufferImageCopy.bufferRowLength(nativeImage.getWidth());
        vkBufferImageCopy.bufferImageHeight(nativeImage.getHeight());
        vkBufferImageCopy.imageOffset().set(xOffset, yOffset, 0);
        vkBufferImageCopy.imageExtent().set(width, height, 1);
        final var subResource = vkBufferImageCopy.imageSubresource();
        subResource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        subResource.mipLevel(level);
        subResource.baseArrayLayer(0);
        subResource.layerCount(1);
        
        // this is done on the main graphics queue as can be called nearly every frame, as a non-async operation
        final var commandBuffer = CinnabarRenderer.queueHelper.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS);
        // TOP_OF_PIPE is fine because there will be frame to frame queue drains
        recordTransition(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, level, 1);
        vkCmdCopyBufferToImage(commandBuffer, cpuMemoryVkBuffer.bufferHandle(), imageHandle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, vkBufferImageCopy);
        recordTransition(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, level, 1);
    }
    
    
    private void recordTransition(VkCommandBuffer commandBuffer, int srcStage, int dstStage, int oldLayout, int newLayout, int srcAccessMask, int dstAccessMask, int baseMipLevel, int mipLevels) {
        try (final var stack = MemoryStack.stackPush()) {
            final var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.oldLayout(oldLayout);
            barrier.newLayout(newLayout);
            barrier.srcAccessMask(srcAccessMask);
            barrier.dstAccessMask(dstAccessMask);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(imageHandle);
            final var subresourceRange = barrier.subresourceRange();
            subresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            subresourceRange.baseMipLevel(baseMipLevel);
            subresourceRange.levelCount(mipLevels);
            subresourceRange.baseArrayLayer(0);
            subresourceRange.layerCount(1);
            
            vkCmdPipelineBarrier(commandBuffer, srcStage, dstStage, 0, null, null, barrier);
        }
    }
    
    private static int glToVkFormatRemap(int format) {
        return switch (format) {
            case GL_RED -> VK_FORMAT_R8_UNORM;
            case GL_RG -> VK_FORMAT_R8G8_UNORM;
            case GL_RGB -> VK_FORMAT_R8G8B8_UNORM;
            case GL_RGBA -> VK_FORMAT_R8G8B8A8_UNORM;
            default -> -1;
        };
    }
    
    private static int byteSizePerTexel(int format) {
        return switch (format) {
            case GL_RED, VK_FORMAT_R8_UNORM -> 1;
            case GL_RG, VK_FORMAT_R8G8_UNORM -> 2;
            case GL_RGB, VK_FORMAT_R8G8B8_UNORM -> 3;
            case GL_RGBA, VK_FORMAT_R8G8B8A8_UNORM -> 4;
            default -> -1;
        };
    }
}
