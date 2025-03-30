package graphics.cinnabar.core.b3d.command;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTexture;
import graphics.cinnabar.api.exceptions.NotImplemented;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.b3d.buffers.CinnabarGpuBuffer;
import graphics.cinnabar.core.b3d.buffers.PersistentWriteBuffer;
import graphics.cinnabar.core.b3d.renderpass.CinnabarRenderPass;
import graphics.cinnabar.core.b3d.texture.CinnabarGpuTexture;
import graphics.cinnabar.core.b3d.window.CinnabarWindow;
import graphics.cinnabar.core.util.MagicNumbers;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCString;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK13.*;

public class CinnabarCommandEncoder implements CommandEncoder, Destroyable {
    
    private final CinnabarDevice device;
    
    private final MemoryStack memoryStack = MemoryStack.create();
    
    private final ReferenceArrayList<VulkanTransientCommandBufferPool> commandPools = new ReferenceArrayList<>();
    
    private VkCommandBuffer beginFrameTransferCommandBuffer;
    private VkCommandBuffer mainDrawCommandBuffer;
    private VkCommandBuffer blitCommandBuffer;
    
    public CinnabarCommandEncoder(CinnabarDevice device) {
        this.device = device;
        for (int i = 0; i < MagicNumbers.MaximumFramesInFlight; i++) {
            commandPools.add(new VulkanTransientCommandBufferPool(device, device.graphicsQueueFamily));
        }
        beginCommandBuffers();
    }
    
    @Override
    public void destroy() {
        commandPools.forEach(VulkanTransientCommandBufferPool::destroy);
    }
    
    private VulkanTransientCommandBufferPool currentCommandPool() {
        return commandPools.get(device.currentFrameIndex());
    }
    
    private void beginCommandBuffers() {
        currentCommandPool().reset(true, false);
        beginFrameTransferCommandBuffer = currentCommandPool().alloc();
        mainDrawCommandBuffer = currentCommandPool().alloc();
        blitCommandBuffer = currentCommandPool().alloc();
        try (final var stack = this.memoryStack.push()) {
            final var beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            vkBeginCommandBuffer(beginFrameTransferCommandBuffer, beginInfo);
            vkBeginCommandBuffer(mainDrawCommandBuffer, beginInfo);
            vkBeginCommandBuffer(blitCommandBuffer, beginInfo);
        }
    }
    
    private void endCommandBuffers() {
        vkEndCommandBuffer(beginFrameTransferCommandBuffer);
        vkEndCommandBuffer(mainDrawCommandBuffer);
        vkEndCommandBuffer(blitCommandBuffer);
    }
    
    private void fullBarrier(VkCommandBuffer commandBuffer) {
        try (final var stack = this.memoryStack.push()) {
            final var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
        }
    }
    
    @Override
    public RenderPass createRenderPass(GpuTexture colorAttachment, OptionalInt colorClear) {
        return this.createRenderPass(colorAttachment, colorClear, null, OptionalDouble.empty());
    }
    
    @Override
    public RenderPass createRenderPass(GpuTexture colorAttachment, OptionalInt colorClear, @Nullable GpuTexture depthAttachment, OptionalDouble depthClear) {
        fullBarrier(mainDrawCommandBuffer);
        return new CinnabarRenderPass(device, mainDrawCommandBuffer, memoryStack, (CinnabarGpuTexture) colorAttachment, colorClear, (CinnabarGpuTexture) depthAttachment, depthClear);
    }
    
    @Override
    public void clearColorTexture(GpuTexture texture, int clearRGBA) {
        assert texture instanceof CinnabarGpuTexture;
        try (final var stack = this.memoryStack.push()) {
            final var vkClearColor = VkClearColorValue.calloc(stack);
            vkClearColor.float32(0, ARGB.redFloat(clearRGBA));
            vkClearColor.float32(1, ARGB.greenFloat(clearRGBA));
            vkClearColor.float32(2, ARGB.blueFloat(clearRGBA));
            vkClearColor.float32(3, ARGB.alphaFloat(clearRGBA));
            
            final var subresourceRange = VkImageSubresourceRange.calloc(stack);
            subresourceRange.baseMipLevel(0);
            subresourceRange.levelCount(texture.getMipLevels());
            subresourceRange.baseArrayLayer(0);
            subresourceRange.layerCount(1);
            subresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            vkCmdClearColorImage(mainDrawCommandBuffer, ((CinnabarGpuTexture) texture).imageHandle, VK_IMAGE_LAYOUT_GENERAL, vkClearColor, subresourceRange);
        }
        fullBarrier(mainDrawCommandBuffer);
    }
    
    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth) {
        clearColorTexture(colorTexture, clearColor);
        clearDepthTexture(depthTexture, clearDepth);
    }
    
    @Override
    public void clearDepthTexture(GpuTexture texture, double clearDepth) {
        assert texture instanceof CinnabarGpuTexture;
        try (final var stack = this.memoryStack.push()) {
            final var clearValue = VkClearDepthStencilValue.calloc(stack);
            clearValue.depth((float) clearDepth);
            
            final var subresourceRange = VkImageSubresourceRange.calloc(stack);
            subresourceRange.baseMipLevel(0);
            subresourceRange.levelCount(texture.getMipLevels());
            subresourceRange.baseArrayLayer(0);
            subresourceRange.layerCount(1);
            subresourceRange.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
            vkCmdClearDepthStencilImage(mainDrawCommandBuffer, ((CinnabarGpuTexture) texture).imageHandle, VK_IMAGE_LAYOUT_GENERAL, clearValue, subresourceRange);
        }
        fullBarrier(mainDrawCommandBuffer);
    }
    
    @Override
    public void clearStencilTexture(GpuTexture texture, int clearStencil) {
        assert texture instanceof CinnabarGpuTexture;
        try (final var stack = this.memoryStack.push()) {
            final var clearValue = VkClearDepthStencilValue.calloc(stack);
            clearValue.stencil(clearStencil);
            
            final var subresourceRange = VkImageSubresourceRange.calloc(stack);
            subresourceRange.baseMipLevel(0);
            subresourceRange.levelCount(texture.getMipLevels());
            subresourceRange.baseArrayLayer(0);
            subresourceRange.layerCount(1);
            subresourceRange.aspectMask(VK_IMAGE_ASPECT_STENCIL_BIT);
            vkCmdClearDepthStencilImage(mainDrawCommandBuffer, ((CinnabarGpuTexture) texture).imageHandle, VK_IMAGE_LAYOUT_GENERAL, clearValue, subresourceRange);
        }
        fullBarrier(mainDrawCommandBuffer);
    }
    
    @Override
    public void writeToBuffer(GpuBuffer buffer, ByteBuffer data, int offset) {
        // the transient write buffer will create a new GPU-side buffer for every upload.
        // those can all always be done at the beginning of the frame
        writeToBuffer((CinnabarGpuBuffer) buffer, data, offset, buffer instanceof PersistentWriteBuffer);
    }
    
    public void writeToBuffer(CinnabarGpuBuffer buffer, ByteBuffer data, int offset, boolean inlineUpload) {
        
        try (final var stack = this.memoryStack.push()) {
            final var commandBuffer = inlineUpload ? mainDrawCommandBuffer : beginFrameTransferCommandBuffer;
            
            // TODO: sync last buffer write, if a persistent buffer
            //       generally those aren't used for multi-write things, so that should be rare enough
            final var targetBuffer = buffer.getBufferForWrite();
            final var uploadBuffer = new VkBuffer(device, data.remaining() - offset, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, device.hostTransientMemoryPool());
            device.destroyEndOfFrame(uploadBuffer);
            
            // TODO: memory checking? requires registering the data ByteBuffer
            LibCString.nmemcpy(uploadBuffer.allocation.cpu().hostPointer.pointer(), MemoryUtil.memAddress(data) + offset, data.remaining() - offset);
            
            final var copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.srcOffset(0);
            copyRegion.dstOffset(0);
            copyRegion.size(data.remaining() - offset);
            copyRegion.limit(1);
            vkCmdCopyBuffer(commandBuffer, uploadBuffer.handle, targetBuffer.handle, copyRegion);
            fullBarrier(commandBuffer);
            
            // because of limitations/guarantees from B3D, I can barrier only at renderpass start (or command buffer end for the begin frame transfer
            // there is no buffer to buffer copy (yet), nor buffer to texture copy
        }
    }
    
    @Override
    public GpuBuffer.ReadView readBuffer(GpuBuffer p_410459_) {
        throw new NotImplemented();
    }
    
    @Override
    public GpuBuffer.ReadView readBuffer(GpuBuffer p_410280_, int p_410832_, int p_410411_) {
        throw new NotImplemented();
    }
    
    public void setupTexture(CinnabarGpuTexture texture) {
        try (final var stack = memoryStack.push()) {
            final var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            barrier.newLayout(VK_IMAGE_LAYOUT_GENERAL);
            barrier.srcAccessMask(0);
            barrier.dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(texture.imageHandle);
            final var subresourceRange = barrier.subresourceRange();
            subresourceRange.aspectMask(texture.aspectMask());
            subresourceRange.baseMipLevel(0);
            subresourceRange.levelCount(texture.getMipLevels());
            subresourceRange.baseArrayLayer(0);
            subresourceRange.layerCount(1);
            
            vkCmdPipelineBarrier(beginFrameTransferCommandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barrier);
        }
        
    }
    
    @Override
    public void writeToTexture(GpuTexture texture, NativeImage image) {
        int width = texture.getWidth(0);
        int height = texture.getHeight(0);
        if (image.getWidth() != width || image.getHeight() != height) {
            throw new IllegalArgumentException("Cannot replace texture of size " + width + "x" + height + " with image of size " + image.getWidth() + "x" + image.getHeight());
        } else if (texture.isClosed()) {
            throw new IllegalStateException("Destination texture is closed");
        } else {
            this.writeToTexture(texture, image, 0, 0, 0, width, height, 0, 0);
        }
    }
    
    @Override
    public void writeToTexture(GpuTexture texture, NativeImage nativeImage, int mipLevel, int dstXOffset, int dstYOffset, int width, int height, int srcXOffset, int srcYOffset) {
        
        final var cinnabarTexture = (CinnabarGpuTexture) texture;
        final var bufferSize = nativeImage.getWidth() * nativeImage.getHeight() * texture.getFormat().pixelSize();
        
        final var uploadBuffer = new VkBuffer(device, bufferSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, device.hostTransientMemoryPool());
        device.destroyEndOfFrame(uploadBuffer);
        final var uploadPtr = uploadBuffer.allocation.cpu().hostPointer;
        LibCString.nmemcpy(uploadPtr.pointer(), nativeImage.getPointer(), uploadPtr.size());
        
        final var texelSize = texture.getFormat().pixelSize();
        final int skipTexels = srcXOffset + srcYOffset * nativeImage.getWidth();
        final long skipBytes = (long) skipTexels * texelSize;
        
        try (final var stack = this.memoryStack.push()) {
            final var bufferImageCopy = VkBufferImageCopy.calloc(1, stack);
            bufferImageCopy.bufferOffset(skipBytes);
            bufferImageCopy.bufferRowLength(nativeImage.getWidth());
            bufferImageCopy.bufferImageHeight(nativeImage.getHeight());
            bufferImageCopy.imageOffset().set(dstXOffset, dstYOffset, 0);
            bufferImageCopy.imageExtent().set(width, height, 1);
            final var subResource = bufferImageCopy.imageSubresource();
            subResource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            subResource.mipLevel(mipLevel);
            subResource.baseArrayLayer(0);
            subResource.layerCount(1);
            
            vkCmdCopyBufferToImage(beginFrameTransferCommandBuffer, uploadBuffer.handle(), cinnabarTexture.imageHandle, VK_IMAGE_LAYOUT_GENERAL, bufferImageCopy);
        }
    }
    
    @Override
    public void writeToTexture(GpuTexture texture, IntBuffer intBuffer, NativeImage.Format bufferFormat, int mipLevel, int x, int y, int width, int height) {
        final var cinnabarTexture = (CinnabarGpuTexture) texture;
        final var bufferSize = width * height * texture.getFormat().pixelSize();
        
        final var uploadBuffer = new VkBuffer(device, bufferSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, device.hostTransientMemoryPool());
        device.destroyEndOfFrame(uploadBuffer);
        final var uploadPtr = uploadBuffer.allocation.cpu().hostPointer;
        LibCString.nmemcpy(uploadPtr.pointer(), MemoryUtil.memAddress(intBuffer), uploadPtr.size());
        
        try (final var stack = this.memoryStack.push()) {
            final var bufferImageCopy = VkBufferImageCopy.calloc(1, stack);
            bufferImageCopy.bufferOffset(0);
            bufferImageCopy.bufferRowLength(width);
            bufferImageCopy.bufferImageHeight(height);
            bufferImageCopy.imageOffset().set(x, y, 0);
            bufferImageCopy.imageExtent().set(width, height, 1);
            final var subResource = bufferImageCopy.imageSubresource();
            subResource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            subResource.mipLevel(mipLevel);
            subResource.baseArrayLayer(0);
            subResource.layerCount(1);
            
            vkCmdCopyBufferToImage(beginFrameTransferCommandBuffer, uploadBuffer.handle(), cinnabarTexture.imageHandle, VK_IMAGE_LAYOUT_GENERAL, bufferImageCopy);
        }
    }
    
    @Override
    public void copyTextureToBuffer(GpuTexture p_409709_, GpuBuffer p_409653_, int p_409654_, Runnable p_409606_, int p_409664_) {
        throw new NotImplemented();
    }
    
    @Override
    public void copyTextureToBuffer(GpuTexture p_409732_, GpuBuffer p_410694_, int p_409794_, Runnable p_410116_, int p_410787_, int p_410381_, int p_409938_, int p_410237_, int p_410626_) {
        throw new NotImplemented();
    }
    
    @Override
    public void copyTextureToTexture(GpuTexture p_410347_, GpuTexture p_410302_, int p_410741_, int p_409745_, int p_409805_, int p_409992_, int p_409918_, int p_409592_, int p_410300_) {
        throw new NotImplemented();
    }
    
    @Override
    public void presentTexture(GpuTexture image) {
        
        final var window = (CinnabarWindow) Minecraft.getInstance().getWindow();
        final var swapchain = window.swapchain();
        assert swapchain.hasImageAcquired();
        
        try (final var stack = memoryStack.push()) {
            final var imageBarrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            imageBarrier.srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            imageBarrier.dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            imageBarrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            imageBarrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            final var imageSubresourceRange = VkImageSubresourceRange.calloc();
            imageSubresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            imageSubresourceRange.baseMipLevel(0);
            imageSubresourceRange.levelCount(1);
            imageSubresourceRange.baseArrayLayer(0);
            imageSubresourceRange.layerCount(1);
            imageBarrier.subresourceRange(imageSubresourceRange);
            
            imageBarrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            imageBarrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageBarrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            imageBarrier.image(swapchain.acquiredImage());
            // wait for nothing, this is just the swapchain image
            vkCmdPipelineBarrier(blitCommandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, imageBarrier);
            
            final var srcOffsets = VkOffset3D.calloc(2, stack);
            srcOffsets.x(0).y(0).z(0);
            srcOffsets.position(1);
            srcOffsets.x(image.getWidth(0)).y(image.getHeight(0)).z(1);
            srcOffsets.position(0);
            
            final var dstOffsets = VkOffset3D.calloc(2, stack);
            dstOffsets.x(0).y(0).z(0);
            dstOffsets.position(1);
            dstOffsets.x(swapchain.width).y(swapchain.height).z(1);
            dstOffsets.position(0);
            
            final var imageSubresource = VkImageSubresourceLayers.calloc();
            imageSubresource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            imageSubresource.mipLevel(0);
            imageSubresource.baseArrayLayer(0);
            imageSubresource.layerCount(1);
            
            final var blitRegion = VkImageBlit.calloc(1, stack);
            blitRegion.srcSubresource(imageSubresource);
            blitRegion.srcOffsets(srcOffsets);
            blitRegion.dstSubresource(imageSubresource);
            blitRegion.dstOffsets(dstOffsets);
            
            // these barriers are for the src texture, they should be relaxed, but thats a later problem
            fullBarrier(blitCommandBuffer);
            vkCmdBlitImage(blitCommandBuffer, ((CinnabarGpuTexture) image).imageHandle, VK_IMAGE_LAYOUT_GENERAL, swapchain.acquiredImage(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blitRegion, VK_FILTER_NEAREST);
            fullBarrier(blitCommandBuffer);
            
            imageBarrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            imageBarrier.newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            imageBarrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            imageBarrier.dstAccessMask(0);
            // nothing needs to wait on this, the semaphore signals the queue present
            vkCmdPipelineBarrier(blitCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, imageBarrier);
            
            // barrier at the end of this command buffer for all implicit transfers
            fullBarrier(beginFrameTransferCommandBuffer);
            endCommandBuffers();
        }
        
        // only the blit needs to wait
        try (final var stack = memoryStack.push()) {
            final var submitInfo = VkSubmitInfo.calloc(2, stack).sType$Default();
            submitInfo.pCommandBuffers(stack.pointers(beginFrameTransferCommandBuffer, mainDrawCommandBuffer));
            submitInfo.position(1).sType$Default();
            submitInfo.pWaitSemaphores(stack.longs(swapchain.semaphore()));
            submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT));
            submitInfo.waitSemaphoreCount(1);
            submitInfo.pCommandBuffers(stack.pointers(blitCommandBuffer));
            submitInfo.pSignalSemaphores(stack.longs(swapchain.semaphore()));
            submitInfo.position(0);
            vkQueueSubmit(device.graphicsQueue, submitInfo, VK_NULL_HANDLE);
        }
        
        device.newFrame();
        beginCommandBuffers();
        fullBarrier(beginFrameTransferCommandBuffer);
    }
}
