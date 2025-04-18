package graphics.cinnabar.core.b3d.command;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTexture;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.b3d.buffers.CinnabarGpuBuffer;
import graphics.cinnabar.core.b3d.buffers.PersistentWriteBuffer;
import graphics.cinnabar.core.b3d.buffers.ReadBuffer;
import graphics.cinnabar.core.b3d.buffers.TransientWriteBuffer;
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
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK13.*;

public class CinnabarCommandEncoder implements CommandEncoder, Destroyable {
    
    private final CinnabarDevice device;
    
    private final MemoryStack memoryStack = MemoryStack.create();
    
    private final ReferenceArrayList<VulkanTransientCommandBufferPool> commandPools = new ReferenceArrayList<>();
    
    private VkCommandBuffer beginFrameTransferCommandBuffer;
    private VkCommandBuffer mainDrawCommandBuffer;
    private VkCommandBuffer blitCommandBuffer;
    
    private final long interFrameSemaphore;
    private long currentFrameNumber = MagicNumbers.MaximumFramesInFlight;
    
    public CinnabarCommandEncoder(CinnabarDevice device) {
        this.device = device;
        for (int i = 0; i < MagicNumbers.MaximumFramesInFlight; i++) {
            commandPools.add(new VulkanTransientCommandBufferPool(device, device.graphicsQueueFamily));
        }
        beginCommandBuffers();
        
        try (final var stack = memoryStack.push()) {
            final var typeCreateInfo = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default();
            typeCreateInfo.semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE);
            typeCreateInfo.initialValue(MagicNumbers.MaximumFramesInFlight - 1);
            final var createInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(typeCreateInfo);
            final var semaphoreVal = stack.longs(0);
            checkVkCode(vkCreateSemaphore(device.vkDevice, createInfo, null, semaphoreVal));
            interFrameSemaphore = semaphoreVal.get(0);
        }
    }
    
    @Override
    public void destroy() {
        commandPools.forEach(VulkanTransientCommandBufferPool::destroy);
        vkDestroySemaphore(device.vkDevice, interFrameSemaphore, null);
    }
    
    private VulkanTransientCommandBufferPool currentCommandPool() {
        return commandPools.get(device.currentFrameIndex());
    }
    
    private void beginCommandBuffers() {
        currentCommandPool().reset(true, true);
        beginFrameTransferCommandBuffer = currentCommandPool().alloc("beginFrameTransfer" + currentFrameNumber);
        mainDrawCommandBuffer = currentCommandPool().alloc("mainDraw" + currentFrameNumber);
        blitCommandBuffer = currentCommandPool().alloc("blit" + currentFrameNumber);
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
        writeToBuffer((CinnabarGpuBuffer) buffer, new PointerWrapper(MemoryUtil.memAddress(data), data.remaining()), offset);
    }
    
    public void writeToBuffer(CinnabarGpuBuffer buffer, PointerWrapper data, int offset) {
        
        try (final var stack = this.memoryStack.push()) {
            final var uploadBeginningOfFrame = buffer.uploadBeginningOfFrame();
            final var commandBuffer = uploadBeginningOfFrame ? beginFrameTransferCommandBuffer : mainDrawCommandBuffer;
            
            // TODO: sync last buffer write, if a persistent buffer
            //       generally those aren't used for multi-write things, so that should be rare enough
            final var targetBuffer = buffer.getBufferForWrite();
            final var uploadBuffer = new VkBuffer(device, data.size() - offset, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, device.hostTransientMemoryPool());
            device.destroyEndOfFrame(uploadBuffer);
            
            // TODO: memory checking? requires registering the data ByteBuffer
            LibCString.nmemcpy(uploadBuffer.allocation.cpu().hostPointer.pointer(), data.pointer() + offset, data.size() - offset);
            
            final var copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.srcOffset(0);
            copyRegion.dstOffset(targetBuffer.second().offset());
            copyRegion.size(data.size() - offset);
            copyRegion.limit(1);
            
            if (!uploadBeginningOfFrame) {
                fullBarrier(commandBuffer);
            }
            
            vkCmdCopyBuffer(commandBuffer, uploadBuffer.handle, targetBuffer.first().handle, copyRegion);
            
            if (buffer instanceof TransientWriteBuffer transientWriteBuffer) {
                transientWriteBuffer.write(uploadBuffer.allocation.cpu());
            } else if (!uploadBeginningOfFrame) {
                fullBarrier(commandBuffer);
            }
            
            // because of limitations/guarantees from B3D, I can barrier only at renderpass start (or command buffer end for the begin frame transfer
            // there is no buffer to buffer copy (yet), nor buffer to texture copy
        }
    }
    
    @Override
    public GpuBuffer.ReadView readBuffer(GpuBuffer buffer) {
        return readBuffer(buffer, 0, buffer.size());
    }
    
    @Override
    public GpuBuffer.ReadView readBuffer(GpuBuffer buffer, int offset, int size) {
        vkDeviceWaitIdle(device.vkDevice);
        assert buffer instanceof ReadBuffer;
        final var readBuffer = (ReadBuffer) buffer;
        return new GpuBuffer.ReadView() {
            @Override
            public ByteBuffer data() {
                final var hostPtr = readBuffer.getBufferForRead().first().allocation.cpu().hostPointer;
                return MemoryUtil.memByteBuffer(hostPtr.pointer(), (int) hostPtr.size());
            }
            
            @Override
            public void close() {
                // N/A, VK uses persistent mappings
            }
        };
    }
    
    public void copyBufferToBuffer(VkBuffer src, VkBuffer dst) {
        assert src.size == dst.size;
        try (final var stack = memoryStack.push()) {
            final var copyRange = VkBufferCopy.calloc(1, stack);
            copyRange.srcOffset(0);
            copyRange.dstOffset(0);
            copyRange.size(src.size);
            vkCmdCopyBuffer(beginFrameTransferCommandBuffer, src.handle, dst.handle, copyRange);
        }
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
    public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, int offset, Runnable callback, int mip) {
        this.copyTextureToBuffer(texture, buffer, offset, callback, mip, 0, 0, texture.getWidth(mip), texture.getHeight(mip));
    }
    
    @Override
    public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, int offset, Runnable callback, int mip, int xOffset, int yOffset, int width, int height) {
        
        final var cinnabarTexture = (CinnabarGpuTexture) texture;
        final var readBuffer = (ReadBuffer) buffer;
        
        try (final var stack = memoryStack.push()) {
            final var copy = VkBufferImageCopy.calloc(1, stack);
            copy.bufferOffset(offset);
            final var subresource = copy.imageSubresource();
            subresource.aspectMask(cinnabarTexture.aspectMask());
            subresource.mipLevel(mip);
            subresource.baseArrayLayer(0);
            subresource.layerCount(1);
            copy.imageOffset().set(xOffset, yOffset, 0);
            copy.imageExtent().set(width, height, 1);
            
            fullBarrier(mainDrawCommandBuffer);
            vkCmdCopyImageToBuffer(mainDrawCommandBuffer, cinnabarTexture.imageHandle, VK_IMAGE_LAYOUT_GENERAL, readBuffer.getBufferForWrite().first().handle, copy);
            fullBarrier(mainDrawCommandBuffer);
            device.destroyEndOfFrame(callback::run);
        }
    }
    
    @Override
    public void copyTextureToTexture(GpuTexture src, GpuTexture dst, int mip, int srcX, int srcY, int dstX, int dstY, int width, int height) {
        final var cinnabarSrc = (CinnabarGpuTexture) src;
        final var cinnabarDst = (CinnabarGpuTexture) dst;
        try (final var stack = memoryStack.push()) {
            
            final var blits = VkImageBlit.calloc(1, stack);
            
            final var subresource = blits.srcSubresource();
            subresource.aspectMask(cinnabarSrc.aspectMask());
            subresource.mipLevel(mip);
            subresource.baseArrayLayer(0);
            subresource.layerCount(1);
            blits.dstSubresource(subresource);
            
            blits.srcOffsets(0).set(srcX, srcY, 0);
            blits.srcOffsets(1).set(width, height, 1);
            blits.dstOffsets(0).set(dstX, dstY, 0);
            blits.dstOffsets(1).set(width, height, 1);
            
            fullBarrier(mainDrawCommandBuffer);
            vkCmdBlitImage(mainDrawCommandBuffer, cinnabarSrc.imageHandle, VK_IMAGE_LAYOUT_GENERAL, cinnabarDst.imageHandle, VK_IMAGE_LAYOUT_GENERAL, blits, VK_FILTER_NEAREST);
            fullBarrier(mainDrawCommandBuffer);
        }
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
            dstOffsets.x(0).y(swapchain.height).z(0);
            dstOffsets.position(1);
            dstOffsets.x(swapchain.width).y(0).z(1);
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
            final var timelineSubmitInfo = VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default();
            submitInfo.pNext(timelineSubmitInfo);
            
            submitInfo.pWaitSemaphores(stack.longs(swapchain.semaphore()));
            submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT));
            submitInfo.waitSemaphoreCount(1);
            
            submitInfo.pCommandBuffers(stack.pointers(blitCommandBuffer));
            
            timelineSubmitInfo.pSignalSemaphoreValues(stack.longs(currentFrameNumber, 0));
            submitInfo.pSignalSemaphores(stack.longs(interFrameSemaphore, swapchain.semaphore()));
            
            submitInfo.position(0);
            vkQueueSubmit(device.graphicsQueue, submitInfo, VK_NULL_HANDLE);
            
            final var waitInfo = VkSemaphoreWaitInfo.calloc(stack).sType$Default();
            waitInfo.semaphoreCount(1);
            waitInfo.pSemaphores(stack.longs(interFrameSemaphore));
            // wait for the last time this frame index was submitted
            // the semaphore starts at MaximumFramesInFlight, so this returns immediately for the first few frames
            waitInfo.pValues(stack.longs(currentFrameNumber - (MagicNumbers.MaximumFramesInFlight - 1)));
            if (checkVkCode(vkWaitSemaphores(device.vkDevice, waitInfo, -1)) != VK_SUCCESS) {
                throw new IllegalStateException();
            }
        }
        
        currentFrameNumber++;
        device.newFrame();
        beginCommandBuffers();
        device.startFrame();
        fullBarrier(beginFrameTransferCommandBuffer);
    }
    
    public List<String> debugStrings() {
        int allocatedBuffers = 0;
        int usedBuffers = 0;
        for (VulkanTransientCommandBufferPool commandPool : commandPools) {
            allocatedBuffers += commandPool.allocatedBuffers();
            usedBuffers += commandPool.usedBuffers();
        }
        return List.of(String.format("Command Buffers %s/%s", usedBuffers, allocatedBuffers));
    }
}
