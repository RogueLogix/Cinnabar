package graphics.cinnabar.core.b3d.command;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import graphics.cinnabar.api.cvk.systems.CVKCommandEncoder;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.b3d.buffers.BufferPool;
import graphics.cinnabar.core.b3d.buffers.CinnabarGpuBuffer;
import graphics.cinnabar.core.b3d.renderpass.CinnabarRenderPass;
import graphics.cinnabar.core.b3d.texture.CinnabarGpuTexture;
import graphics.cinnabar.core.b3d.texture.CinnabarGpuTextureView;
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
import java.util.function.Supplier;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK13.*;

public class CinnabarCommandEncoder implements CVKCommandEncoder, Destroyable {
    
    private final CinnabarDevice device;
    
    private final MemoryStack memoryStack = MemoryStack.create();
    
    private final ReferenceArrayList<VulkanTransientCommandBufferPool> commandPools = new ReferenceArrayList<>();
    
    @Nullable
    private VkCommandBuffer beginFrameTransferCommandBuffer;
    @Nullable
    private VkCommandBuffer mainDrawCommandBuffer;
    @Nullable
    private VkCommandBuffer blitCommandBuffer;
    
    private final ReferenceArrayList<@Nullable VkCommandBuffer> commandBuffers = new ReferenceArrayList<>();
    private final VkCommandBufferBeginInfo commandBufferBeginInfo = VkCommandBufferBeginInfo.calloc(memoryStack).sType$Default();
    
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
    
    public VulkanTransientCommandBufferPool currentCommandPool() {
        return commandPools.get(device.currentFrameIndex());
    }
    
    private void beginCommandBuffers() {
        currentCommandPool().reset(true, true);
        beginFrameTransferCommandBuffer = currentCommandPool().alloc("beginFrameTransfer");
        vkBeginCommandBuffer(beginFrameTransferCommandBuffer, commandBufferBeginInfo);
        commandBuffers.add(beginFrameTransferCommandBuffer);
    }
    
    private VkCommandBuffer allocAndInsertCommandBuffer(String name) {
        final var commandBuffer = currentCommandPool().alloc(name);
        if (mainDrawCommandBuffer != null) {
            vkEndCommandBuffer(mainDrawCommandBuffer);
            mainDrawCommandBuffer = null;
        }
        vkBeginCommandBuffer(commandBuffer, commandBufferBeginInfo);
        commandBuffers.add(commandBuffer);
        return commandBuffer;
    }
    
    private void endCommandBuffers() {
        assert beginFrameTransferCommandBuffer != null;
        vkEndCommandBuffer(beginFrameTransferCommandBuffer);
        beginFrameTransferCommandBuffer = null;
        if (mainDrawCommandBuffer != null) {
            vkEndCommandBuffer(mainDrawCommandBuffer);
            mainDrawCommandBuffer = null;
        }
    }
    
    private VkCommandBuffer getMainDrawCommandBuffer() {
        if (mainDrawCommandBuffer == null) {
            mainDrawCommandBuffer = currentCommandPool().alloc("mainDraw");
            vkBeginCommandBuffer(mainDrawCommandBuffer, commandBufferBeginInfo);
            commandBuffers.add(mainDrawCommandBuffer);
        }
        return mainDrawCommandBuffer;
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
    public CinnabarRenderPass createRenderPass(Supplier<String> debugGroup, GpuTextureView colorAttachment, OptionalInt colorClear) {
        return this.createRenderPass(debugGroup, colorAttachment, colorClear, null, OptionalDouble.empty());
    }
    
    @Override
    public CinnabarRenderPass createRenderPass(Supplier<String> debugGroup, GpuTextureView colorAttachment, OptionalInt colorClear, @Nullable GpuTextureView depthAttachment, OptionalDouble depthClear) {
        final var commandBuffer = allocAndInsertCommandBuffer(debugGroup.get());
        fullBarrier(commandBuffer);
        return new CinnabarRenderPass(device, commandBuffer, memoryStack, debugGroup, (CinnabarGpuTextureView) colorAttachment, colorClear, (CinnabarGpuTextureView) depthAttachment, depthClear);
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
            vkCmdClearColorImage(getMainDrawCommandBuffer(), ((CinnabarGpuTexture) texture).imageHandle, VK_IMAGE_LAYOUT_GENERAL, vkClearColor, subresourceRange);
        }
        fullBarrier(getMainDrawCommandBuffer());
    }
    
    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth) {
        clearColorTexture(colorTexture, clearColor);
        clearDepthTexture(depthTexture, clearDepth);
    }
    
    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth, int scissorX, int scissorY, int scissorWidth, int scissorHeight) {
        try (
                // creating a renderpass needs texture views, but im only passed textures... amazing
                final var colorTextureView = device.createTextureView(colorTexture);
                final var depthTextureView = device.createTextureView(depthTexture);
                final var renderpass = createRenderPass(() -> "ClearColorDepthTextures", colorTextureView, OptionalInt.empty(), depthTextureView, OptionalDouble.empty());
        ) {
            renderpass.enableScissor(scissorX, scissorY, scissorWidth, scissorHeight);
            renderpass.clearAttachments(clearColor, clearDepth);
        }
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
            vkCmdClearDepthStencilImage(getMainDrawCommandBuffer(), ((CinnabarGpuTexture) texture).imageHandle, VK_IMAGE_LAYOUT_GENERAL, clearValue, subresourceRange);
        }
        fullBarrier(getMainDrawCommandBuffer());
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
            vkCmdClearDepthStencilImage(getMainDrawCommandBuffer(), ((CinnabarGpuTexture) texture).imageHandle, VK_IMAGE_LAYOUT_GENERAL, clearValue, subresourceRange);
        }
        fullBarrier(getMainDrawCommandBuffer());
    }
    
    @Override
    public void writeToBuffer(GpuBufferSlice gpuBufferSlice, ByteBuffer data) {
        final var buffer = (CinnabarGpuBuffer) gpuBufferSlice.buffer();
        if (buffer.canAccessDirectly()) {
            final var backingSlice = buffer.backingSliceDirectAccess();
            final var hostPtr = backingSlice.buffer().allocationInfo.pMappedData() + backingSlice.range.offset();
            LibCString.nmemcpy(hostPtr + gpuBufferSlice.offset(), MemoryUtil.memAddress(data), data.remaining());
        } else {
            final var backingSlice = buffer.backingSlice();
            try (final var stack = this.memoryStack.push()) {
                final var uploadBeginningOfFrame = !buffer.accessedThisFrame();
                assert beginFrameTransferCommandBuffer != null;
                final var commandBuffer = uploadBeginningOfFrame ? beginFrameTransferCommandBuffer : getMainDrawCommandBuffer();
                
                final var stagingBuffer = device.uploadPools.get(device.currentFrameIndex()).alloc(0, data.remaining(), null);
                device.destroyEndOfFrame(stagingBuffer);
                final var stagingSlice = stagingBuffer.backingSlice();
                LibCString.nmemcpy(stagingSlice.buffer().allocationInfo.pMappedData() + stagingSlice.range.offset(), MemoryUtil.memAddress(data), data.remaining());
                
                final var copyRegion = VkBufferCopy.calloc(1, stack);
                copyRegion.srcOffset(stagingSlice.range.offset());
                // yes, a sliced slice...
                copyRegion.dstOffset(backingSlice.range.offset() + gpuBufferSlice.offset());
                copyRegion.size(data.remaining());
                copyRegion.limit(1);
                
                // must barrier before the write, because the buffer may still be in use by the ending renderpass, or a previous copy (write-write is valid behavior, though not recommended)
                if (!uploadBeginningOfFrame) {
                    fullBarrier(commandBuffer);
                }
                
                vkCmdCopyBuffer(commandBuffer, stagingSlice.buffer().handle, backingSlice.buffer().handle, copyRegion);
                
                // because of limitations/guarantees from B3D, I can barrier only at renderpass start (or command buffer end for the begin frame transfer)
                // there is no buffer to buffer copy (yet), nor buffer to texture copy
            }
        }
    }
    
    
    public void copyBufferToBuffer(VkBuffer src, VkBuffer dst) {
        assert src.size == dst.size;
        try (final var stack = memoryStack.push()) {
            final var copyRange = VkBufferCopy.calloc(1, stack);
            copyRange.srcOffset(0);
            copyRange.dstOffset(0);
            copyRange.size(src.size);
            assert beginFrameTransferCommandBuffer != null;
            vkCmdCopyBuffer(beginFrameTransferCommandBuffer, src.handle, dst.handle, copyRange);
        }
    }
    
    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBuffer gpuBuffer, boolean read, boolean write) {
        return mapBuffer(gpuBuffer.slice(), read, write);
    }
    
    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBufferSlice gpuBufferSlice, boolean read, boolean write) {
        if (gpuBufferSlice.buffer() instanceof BufferPool.Buffer) {
            // can't (shouldn't) map these, because their backing buffer changes
            throw new IllegalStateException();
        }
        final var cinnabarBuffer = (CinnabarGpuBuffer) gpuBufferSlice.buffer();
        // this must be externally synced, so im not using the DirectAccess one
        final var backingSlice = cinnabarBuffer.backingSlice();
        final var mappedByteBuffer = MemoryUtil.memByteBuffer(backingSlice.buffer().allocationInfo.pMappedData() + backingSlice.range.offset() + gpuBufferSlice.offset(), (int) backingSlice.range.size());
        cinnabarBuffer.accessed();
        return new GpuBuffer.MappedView() {
            @Override
            public ByteBuffer data() {
                return mappedByteBuffer;
            }
            
            @Override
            public void close() {
                // N/A, VK is persistent mapping
            }
        };
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
            subresourceRange.layerCount(texture.getDepthOrLayers());
            
            assert beginFrameTransferCommandBuffer != null;
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
            this.writeToTexture(texture, image, 0, 0, 0, 0, width, height, 0, 0);
        }
    }
    
    @Override
    public void writeToTexture(GpuTexture texture, NativeImage nativeImage, int mipLevel, int arrayLayer, int dstXOffset, int dstYOffset, int width, int height, int srcXOffset, int srcYOffset) {
        
        final var cinnabarTexture = (CinnabarGpuTexture) texture;
        final var bufferSize = nativeImage.getWidth() * nativeImage.getHeight() * texture.getFormat().pixelSize();
        
        final var uploadBuffer = new VkBuffer(device, bufferSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, device.hostMemoryType);
        device.destroyEndOfFrame(uploadBuffer);
        LibCString.nmemcpy(uploadBuffer.allocationInfo.pMappedData(), nativeImage.getPointer(), uploadBuffer.size);
        
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
            subResource.baseArrayLayer(arrayLayer);
            subResource.layerCount(1);
            
            assert beginFrameTransferCommandBuffer != null;
            vkCmdCopyBufferToImage(beginFrameTransferCommandBuffer, uploadBuffer.handle(), cinnabarTexture.imageHandle, VK_IMAGE_LAYOUT_GENERAL, bufferImageCopy);
        }
    }
    
    @Override
    public void writeToTexture(GpuTexture texture, IntBuffer intBuffer, NativeImage.Format bufferFormat, int mipLevel, int depthOffset, int x, int y, int width, int height) {
        final var cinnabarTexture = (CinnabarGpuTexture) texture;
        final var bufferSize = width * height * texture.getFormat().pixelSize();
        
        final var uploadBuffer = new VkBuffer(device, bufferSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, device.hostMemoryType);
        device.destroyEndOfFrame(uploadBuffer);
        LibCString.nmemcpy(uploadBuffer.allocationInfo.pMappedData(), MemoryUtil.memAddress(intBuffer), uploadBuffer.size);
        
        try (final var stack = this.memoryStack.push()) {
            final var bufferImageCopy = VkBufferImageCopy.calloc(1, stack);
            bufferImageCopy.bufferOffset(0);
            bufferImageCopy.bufferRowLength(width);
            bufferImageCopy.bufferImageHeight(height);
            bufferImageCopy.imageOffset().set(x, y, depthOffset);
            bufferImageCopy.imageExtent().set(width, height, 1);
            final var subResource = bufferImageCopy.imageSubresource();
            subResource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            subResource.mipLevel(mipLevel);
            subResource.baseArrayLayer(0);
            subResource.layerCount(1);
            
            assert beginFrameTransferCommandBuffer != null;
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
        final var cinnabarBuffer = (CinnabarGpuBuffer) buffer;
        final var backingSlice = cinnabarBuffer.backingSlice();
        
        try (final var stack = memoryStack.push()) {
            final var copy = VkBufferImageCopy.calloc(1, stack);
            copy.bufferOffset(backingSlice.range.offset() + offset);
            final var subresource = copy.imageSubresource();
            subresource.aspectMask(cinnabarTexture.aspectMask());
            subresource.mipLevel(mip);
            subresource.baseArrayLayer(0);
            subresource.layerCount(1);
            copy.imageOffset().set(xOffset, yOffset, 0);
            copy.imageExtent().set(width, height, 1);
            
            fullBarrier(getMainDrawCommandBuffer());
            vkCmdCopyImageToBuffer(getMainDrawCommandBuffer(), cinnabarTexture.imageHandle, VK_IMAGE_LAYOUT_GENERAL, backingSlice.buffer().handle, copy);
            cinnabarBuffer.accessed();
            fullBarrier(getMainDrawCommandBuffer());
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
            
            fullBarrier(getMainDrawCommandBuffer());
            vkCmdBlitImage(getMainDrawCommandBuffer(), cinnabarSrc.imageHandle, VK_IMAGE_LAYOUT_GENERAL, cinnabarDst.imageHandle, VK_IMAGE_LAYOUT_GENERAL, blits, VK_FILTER_NEAREST);
            fullBarrier(getMainDrawCommandBuffer());
        }
    }
    
    @Override
    public void presentTexture(GpuTextureView imageView) {
        
        final var window = (CinnabarWindow) Minecraft.getInstance().getWindow();
        final var swapchain = window.swapchain();
        assert swapchain.hasImageAcquired();
        
        blitCommandBuffer = currentCommandPool().alloc("presentTexture blit");
        vkBeginCommandBuffer(blitCommandBuffer, commandBufferBeginInfo);
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
            
            assert imageView.texture().getDepthOrLayers() == 1;
            
            final var srcOffsets = VkOffset3D.calloc(2, stack);
            srcOffsets.x(0).y(0).z(0);
            srcOffsets.position(1);
            srcOffsets.x(imageView.getWidth(0)).y(imageView.getHeight(0)).z(1);
            srcOffsets.position(0);
            
            final var dstOffsets = VkOffset3D.calloc(2, stack);
            dstOffsets.x(0).y(swapchain.height).z(0);
            dstOffsets.position(1);
            dstOffsets.x(swapchain.width).y(0).z(1);
            dstOffsets.position(0);
            
            final var imageSubresource = VkImageSubresourceLayers.calloc();
            imageSubresource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            assert imageView.baseMipLevel() == 0;
            assert imageView.mipLevels() == 1;
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
            vkCmdBlitImage(blitCommandBuffer, ((CinnabarGpuTexture) imageView.texture()).imageHandle, VK_IMAGE_LAYOUT_GENERAL, swapchain.acquiredImage(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blitRegion, VK_FILTER_NEAREST);
            fullBarrier(blitCommandBuffer);
            
            imageBarrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            imageBarrier.newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            imageBarrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            imageBarrier.dstAccessMask(0);
            // nothing needs to wait on this, the semaphore signals the queue present
            vkCmdPipelineBarrier(blitCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, imageBarrier);
        }
        vkEndCommandBuffer(blitCommandBuffer);
    }
    
    public void flushCommandBuffers() {
        assert beginFrameTransferCommandBuffer != null;
        
        // barrier at the end of this command buffer for all implicit transfers
        fullBarrier(beginFrameTransferCommandBuffer);
        endCommandBuffers();
        
        final var window = (CinnabarWindow) Minecraft.getInstance().getWindow();
        final var swapchain = window.swapchain();
        assert swapchain.hasImageAcquired();
        // only the blit needs to wait
        try (final var stack = memoryStack.push()) {
            final var submitInfo = VkSubmitInfo.calloc(2, stack).sType$Default();
            
            final var buffers = stack.callocPointer(commandBuffers.size());
            for (int i = 0; i < commandBuffers.size(); i++) {
                @Nullable
                final var cb = commandBuffers.get(i);
                if (cb == null) {
                    throw new IllegalStateException();
                }
                buffers.put(i, cb);
            }
            commandBuffers.clear();
            submitInfo.pCommandBuffers(buffers);
            
            submitInfo.position(1).sType$Default();
            final var timelineSubmitInfo = VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default();
            submitInfo.pNext(timelineSubmitInfo);
            
            if (blitCommandBuffer != null) {
                
                submitInfo.pWaitSemaphores(stack.longs(swapchain.semaphore()));
                submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT));
                submitInfo.waitSemaphoreCount(1);
                
                submitInfo.pCommandBuffers(stack.pointers(blitCommandBuffer));
                blitCommandBuffer = null;
                timelineSubmitInfo.pSignalSemaphoreValues(stack.longs(device.currentFrame(), 0));
                submitInfo.pSignalSemaphores(stack.longs(interFrameSemaphore, swapchain.semaphore()));
            } else {
                timelineSubmitInfo.pSignalSemaphoreValues(stack.longs(device.currentFrame()));
                submitInfo.pSignalSemaphores(stack.longs(interFrameSemaphore));
            }
            
            submitInfo.position(0);
            vkQueueSubmit(device.graphicsQueue, submitInfo, VK_NULL_HANDLE);
        }
        
        // frame done
        device.newFrame();
        
        // wait for the last time this frame index was submitted
        // the semaphore starts at MaximumFramesInFlight, so this returns immediately for the first few frames
        try (final var stack = memoryStack.push()) {
            final var waitInfo = VkSemaphoreWaitInfo.calloc(stack).sType$Default();
            waitInfo.semaphoreCount(1);
            waitInfo.pSemaphores(stack.longs(interFrameSemaphore));
            waitInfo.pValues(stack.longs(device.currentFrame() - MagicNumbers.MaximumFramesInFlight));
            if (checkVkCode(vkWaitSemaphores(device.vkDevice, waitInfo, -1)) != VK_SUCCESS) {
                throw new IllegalStateException();
            }
        }
        
        currentFrameNumber++;
        beginCommandBuffers();
        
        assert beginFrameTransferCommandBuffer != null;
        device.startFrame();
        fullBarrier(beginFrameTransferCommandBuffer);
        fullBarrier(getMainDrawCommandBuffer());
    }
    
    public void endFrame() {
        flushCommandBuffers();
    }
    
    @Override
    public GpuFence createFence() {
        return new GpuFence() {
            final long waitValue = device.currentFrame();
            
            @Override
            public void close() {
                
            }
            
            @Override
            public boolean awaitCompletion(long l) {
                if (waitValue == device.currentFrame()) {
                    flushCommandBuffers();
                }
                try (final var stack = memoryStack.push()) {
                    final var waitInfo = VkSemaphoreWaitInfo.calloc(stack).sType$Default();
                    waitInfo.semaphoreCount(1);
                    waitInfo.pSemaphores(stack.longs(interFrameSemaphore));
                    waitInfo.pValues(stack.longs(waitValue));
                    final var returnCode = checkVkCode(vkWaitSemaphores(device.vkDevice, waitInfo, -1));
                    return returnCode == VK_SUCCESS;
                }
            }
        };
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
