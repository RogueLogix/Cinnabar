package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.exceptions.NotImplemented;
import graphics.cinnabar.api.hg.*;
import graphics.cinnabar.api.memory.GrowingMemoryStack;
import it.unimi.dsi.fastutil.ints.IntList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.*;

import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK12.*;

public class MercuryCommandBuffer extends MercuryObject implements HgCommandBuffer {
    private static final VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc().sType$Default();
    
    private final VkCommandBuffer commandBuffer;
    private final Consumer<VkCommandBuffer> freeFunction;
    private final GrowingMemoryStack memoryStack;
    @Nullable
    private MercuryGraphicsPipelineLayout currentPipelineLayout;
    
    public MercuryCommandBuffer(MercuryDevice device, VkCommandBuffer commandBuffer, Consumer<VkCommandBuffer> freeFunction) {
        super(device);
        this.commandBuffer = commandBuffer;
        this.freeFunction = freeFunction;
        memoryStack = new GrowingMemoryStack();
    }
    
    @Override
    public void destroy() {
        freeFunction.accept(commandBuffer);
    }
    
    public VkCommandBuffer vkCommandBuffer() {
        return commandBuffer;
    }
    
    @Override
    public HgCommandBuffer setName(String name) {
        if (device.debugMarkerEnabled()) {
            try (final var stack = memoryStack.push()) {
                final var nameInfo = VkDebugMarkerObjectNameInfoEXT.calloc(stack).sType$Default();
                nameInfo.pObjectName(stack.UTF8(name));
                nameInfo.object(commandBuffer.address());
                nameInfo.objectType(VK_OBJECT_TYPE_COMMAND_BUFFER);
                EXTDebugMarker.vkDebugMarkerSetObjectNameEXT(device.vkDevice(), nameInfo);
            }
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer begin() {
        vkBeginCommandBuffer(commandBuffer, beginInfo);
        return this;
    }
    
    @Override
    public HgCommandBuffer end() {
        vkEndCommandBuffer(commandBuffer);
        memoryStack.reset(); // frees most of the memory held by the stack
        return this;
    }
    
    // ---------- Always valid commands ----------
    
    
    @Override
    public HgCommandBuffer pushDebugGroup(String name) {
        if (device.debugMarkerEnabled()) {
            try (final var stack = memoryStack.push()) {
                final var markerInfo = VkDebugMarkerMarkerInfoEXT.calloc(stack).sType$Default();
                markerInfo.pMarkerName(stack.UTF8(name));
                markerInfo.color(0, 1);
                markerInfo.color(1, 1);
                markerInfo.color(2, 1);
                markerInfo.color(3, 1);
                EXTDebugMarker.vkCmdDebugMarkerBeginEXT(commandBuffer, markerInfo);
            }
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer popDebugGroup() {
        if (device.debugMarkerEnabled()) {
            EXTDebugMarker.vkCmdDebugMarkerEndEXT(commandBuffer);
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer barrier() {
        barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        return this;
    }
    
    @Override
    public HgCommandBuffer barrier(long srcStage, long srcAccess, long destStage, long dstAccess) {
        try (final var stack = this.memoryStack.push()) {
            final var memoryBarrier = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            memoryBarrier.srcStageMask(srcStage);
            memoryBarrier.srcAccessMask(srcAccess);
            memoryBarrier.dstStageMask(destStage);
            memoryBarrier.dstAccessMask(dstAccess);
            final var depInfo = VkDependencyInfo.calloc(stack).sType$Default();
            depInfo.pMemoryBarriers(memoryBarrier);
            vkCmdPipelineBarrier2KHR(commandBuffer, depInfo);
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer queueOwnershipTransfer(HgQueue fromQueue, HgQueue toQueue, @Nullable List<HgBuffer.Slice> buffers, @Nullable List<HgImage.ResourceRange> images, long sourceStage, long srcAccess, long destStage, long dstAccess) {
        throw new NotImplemented();
    }
    
    @Override
    public HgCommandBuffer initImages(List<HgImage> images) {
        try (final var stack = memoryStack.push()) {
            final var imageBarriers = VkImageMemoryBarrier2.calloc(images.size(), stack).sType$Default();
            for (int i = 0; i < images.size(); i++) {
                imageBarriers.position(i);
                final var image = (MercuryImage) images.get(i);
                
                imageBarriers.srcStageMask(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                imageBarriers.srcAccessMask(0);
                imageBarriers.dstStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
                imageBarriers.dstAccessMask(VK_ACCESS_MEMORY_WRITE_BIT); // it's invalid to read from an uninitialized texture, so only care about writes
                imageBarriers.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                imageBarriers.newLayout(VK_IMAGE_LAYOUT_GENERAL);
                imageBarriers.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                imageBarriers.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                imageBarriers.image(image.vkImage());
                final var subresourceRange = imageBarriers.subresourceRange();
                subresourceRange.aspectMask(image.format().aspects());
                subresourceRange.baseArrayLayer(0);
                subresourceRange.levelCount(image.levelCount());
                subresourceRange.baseArrayLayer(0);
                subresourceRange.layerCount(image.layerCount());
            }
            imageBarriers.position(0);
            final var depInfo = VkDependencyInfo.calloc(stack).sType$Default();
            depInfo.pImageMemoryBarriers(imageBarriers);
            vkCmdPipelineBarrier2KHR(commandBuffer, depInfo);
        }
        return this;
    }
    
    // ---------- Outside RenderPass commands ----------
    
    @Override
    public HgCommandBuffer copyBufferToBuffer(HgBuffer.Slice src, HgBuffer.Slice dst) {
        try (final var stack = memoryStack.push()) {
            
            final VkBufferCopy.Buffer bufferCopies;
            // special case for copy the whole thing
            bufferCopies = VkBufferCopy.calloc(1, stack);
            bufferCopies.srcOffset(src.offset());
            bufferCopies.dstOffset(dst.offset());
            bufferCopies.size(Math.min(src.size(), dst.size()));
            
            bufferCopies.position(0);
            
            vkCmdCopyBuffer(commandBuffer, ((MercuryBuffer) src.buffer()).vkBuffer(), ((MercuryBuffer) dst.buffer()).vkBuffer(), bufferCopies);
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer copyBufferToImage(HgBuffer.ImageSlice buffer, HgImage.TransferRange imageRange) {
        try (final var stack = memoryStack.push()) {
            final var copy = VkBufferImageCopy.calloc(1, stack);
            copy.bufferOffset(buffer.offset());
            final var subresource = copy.imageSubresource();
            subresource.aspectMask(imageRange.image().format().aspects());
            subresource.mipLevel(imageRange.mipLevel());
            subresource.baseArrayLayer(imageRange.baseLayer());
            subresource.layerCount(imageRange.layerCount());
            copy.imageOffset().set(imageRange.offset().x(), imageRange.offset().y(), imageRange.offset().z());
            copy.imageExtent().set(imageRange.extent().x(), imageRange.extent().y(), imageRange.extent().z());
            copy.bufferRowLength(buffer.width());
            copy.bufferImageHeight(buffer.height());
            
            vkCmdCopyBufferToImage(commandBuffer, ((MercuryBuffer) buffer.buffer()).vkBuffer(), ((MercuryImage) imageRange.image()).vkImage(), VK_IMAGE_LAYOUT_GENERAL, copy);
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer copyImageToBuffer(HgImage.TransferRange imageRange, HgBuffer.ImageSlice buffer) {
        try (final var stack = memoryStack.push()) {
            final var copy = VkBufferImageCopy.calloc(1, stack);
            copy.bufferOffset(buffer.offset());
            final var subresource = copy.imageSubresource();
            subresource.aspectMask(imageRange.image().format().aspects());
            subresource.mipLevel(imageRange.mipLevel());
            subresource.baseArrayLayer(imageRange.baseLayer());
            subresource.layerCount(imageRange.layerCount());
            copy.imageOffset().set(imageRange.offset().x(), imageRange.offset().y(), imageRange.offset().z());
            copy.imageExtent().set(imageRange.extent().x(), imageRange.extent().y(), imageRange.extent().z());
            copy.bufferRowLength(buffer.width());
            copy.bufferImageHeight(buffer.height());
            
            vkCmdCopyImageToBuffer(commandBuffer, ((MercuryImage) imageRange.image()).vkImage(), VK_IMAGE_LAYOUT_GENERAL, ((MercuryBuffer) buffer.buffer()).vkBuffer(), copy);
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer copyImageToImage(HgImage.TransferRange src, HgImage.TransferRange dst) {
        try (final var stack = memoryStack.push()) {
            final var copy = VkImageCopy.calloc(1, stack);
            {
                final var subresource = copy.srcSubresource();
                subresource.aspectMask(src.image().format().aspects());
                subresource.mipLevel(src.mipLevel());
                subresource.baseArrayLayer(src.baseLayer());
                subresource.layerCount(src.layerCount());
                copy.srcOffset().set(src.offset().x(), src.offset().y(), src.offset().z());
            }
            {
                final var subresource = copy.dstSubresource();
                subresource.aspectMask(dst.image().format().aspects());
                subresource.mipLevel(dst.mipLevel());
                subresource.baseArrayLayer(dst.baseLayer());
                subresource.layerCount(dst.layerCount());
                copy.dstOffset().set(dst.offset().x(), dst.offset().y(), dst.offset().z());
            }
            copy.extent().set(src.extent().x(), src.extent().y(), src.extent().z());
            vkCmdCopyImage(commandBuffer, ((MercuryImage) src.image()).vkImage(), VK_IMAGE_LAYOUT_GENERAL, ((MercuryImage) dst.image()).vkImage(), VK_IMAGE_LAYOUT_GENERAL, copy);
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer clearColorImage(HgImage.ResourceRange range, int clearARGB) {
        assert range.image() instanceof MercuryImage;
        try (final var stack = this.memoryStack.push()) {
            final var vkClearColor = VkClearColorValue.calloc(stack);
            vkClearColor.float32(0, (float) ((clearARGB >> 16) & 0xFF) / 255.0f);
            vkClearColor.float32(1, (float) ((clearARGB >> 8) & 0xFF) / 255.0f);
            vkClearColor.float32(2, (float) ((clearARGB >> 0) & 0xFF) / 255.0f);
            vkClearColor.float32(3, (float) ((clearARGB >> 24) & 0xFF) / 255.0f);
            
            final var subresourceRange = VkImageSubresourceRange.calloc(stack);
            subresourceRange.baseMipLevel(range.baseMipLevel());
            subresourceRange.levelCount(range.mipLevels());
            subresourceRange.baseArrayLayer(range.baseArrayLayer());
            subresourceRange.layerCount(range.layerCount());
            subresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            vkCmdClearColorImage(commandBuffer, ((MercuryImage) range.image()).vkImage(), VK_IMAGE_LAYOUT_GENERAL, vkClearColor, subresourceRange);
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer clearDepthStencilImage(HgImage.ResourceRange range, double clearDepth, int clearStencil) {
        assert range.image() instanceof MercuryImage;
        try (final var stack = this.memoryStack.push()) {
            final var clearValue = VkClearDepthStencilValue.calloc(stack);
            clearValue.depth((float) clearDepth);
            clearValue.stencil(clearStencil);
            
            final var subresourceRange = VkImageSubresourceRange.calloc(stack);
            subresourceRange.baseMipLevel(range.baseMipLevel());
            subresourceRange.levelCount(range.mipLevels());
            subresourceRange.baseArrayLayer(range.baseArrayLayer());
            subresourceRange.layerCount(range.layerCount());
            if (clearDepth != -1.0) {
                subresourceRange.aspectMask(subresourceRange.aspectMask() | VK_IMAGE_ASPECT_DEPTH_BIT);
            }
            if (clearStencil != -1) {
                subresourceRange.aspectMask(subresourceRange.aspectMask() | VK_IMAGE_ASPECT_STENCIL_BIT);
            }
            vkCmdClearDepthStencilImage(commandBuffer, ((MercuryImage) range.image()).vkImage(), VK_IMAGE_LAYOUT_GENERAL, clearValue, subresourceRange);
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer blitToSwapchain(HgImage.View view, HgSurface.Swapchain swapchain) {
        assert swapchain instanceof MercurySwapchain;
        assert view instanceof MercuryImageView;
        try (final var stack = memoryStack.push()) {
            final var imageBarrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            imageBarrier.srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            imageBarrier.dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            imageBarrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            imageBarrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            final var imageSubresourceRange = VkImageSubresourceRange.calloc(stack);
            imageSubresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            imageSubresourceRange.baseMipLevel(0);
            imageSubresourceRange.levelCount(1);
            imageSubresourceRange.baseArrayLayer(0);
            imageSubresourceRange.layerCount(1);
            imageBarrier.subresourceRange(imageSubresourceRange);
            
            imageBarrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            imageBarrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageBarrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            imageBarrier.image(((MercurySwapchain) swapchain).currentVkImage());
            // wait for nothing, this is just the swapchain image
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, imageBarrier);
            
            assert view.image().depth() == 1;
            assert view.image().layerCount() == 1;
            
            final var srcOffsets = VkOffset3D.calloc(2, stack);
            srcOffsets.x(0).y(0).z(0);
            srcOffsets.position(1);
            srcOffsets.x(view.image().width()).y(view.image().height()).z(1);
            srcOffsets.position(0);
            
            final var dstOffsets = VkOffset3D.calloc(2, stack);
            dstOffsets.x(0).y(swapchain.height()).z(0);
            dstOffsets.position(1);
            dstOffsets.x(swapchain.width()).y(0).z(1);
            dstOffsets.position(0);
            
            final var imageSubresource = VkImageSubresourceLayers.calloc(stack);
            imageSubresource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            assert view.baseMipLevel() == 0;
            assert view.levelCount() == 1;
            imageSubresource.mipLevel(0);
            imageSubresource.baseArrayLayer(0);
            imageSubresource.layerCount(1);
            
            final var blitRegion = VkImageBlit.calloc(1, stack);
            blitRegion.srcSubresource(imageSubresource);
            blitRegion.srcOffsets(srcOffsets);
            blitRegion.dstSubresource(imageSubresource);
            blitRegion.dstOffsets(dstOffsets);
            
            // these barriers are for the src texture, they should be relaxed, but that's a later problem
            vkCmdBlitImage(commandBuffer, ((MercuryImage) view.image()).vkImage(), VK_IMAGE_LAYOUT_GENERAL, ((MercurySwapchain) swapchain).currentVkImage(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blitRegion, VK_FILTER_NEAREST);
            
            imageBarrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            imageBarrier.newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            imageBarrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            imageBarrier.dstAccessMask(0);
            // nothing needs to wait on this, the semaphore signals the queue present
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, imageBarrier);
        }
        return this;
    }
    
    // ---------- Inside RenderPass commands ----------
    
    @Override
    public HgCommandBuffer beginRenderPass(HgRenderPass renderPass, HgFramebuffer framebuffer) {
        try (final var stack = memoryStack.push()) {
            final var passBeginInfo = VkRenderPassBeginInfo.calloc(stack).sType$Default();
            passBeginInfo.renderPass(((MercuryRenderPass) renderPass).vkRenderPass());
            passBeginInfo.framebuffer(((MercuryFramebuffer) framebuffer).vkFramebuffer());
            final var renderArea = passBeginInfo.renderArea();
            final var renderOffset = renderArea.offset();
            final var renderExtent = renderArea.extent();
            renderOffset.set(0, 0);
            renderExtent.set(framebuffer.width(), framebuffer.height());
            
            final var subpassBeginInfo = VkSubpassBeginInfo.calloc(stack).sType$Default();
            subpassBeginInfo.contents(VK_SUBPASS_CONTENTS_INLINE);
            
            vkCmdBeginRenderPass2(commandBuffer, passBeginInfo, subpassBeginInfo);
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer endRenderPass() {
        vkCmdEndRenderPass(commandBuffer);
        return this;
    }
    
    @Override
    public HgCommandBuffer setViewport(int attachment, int x, int y, int width, int height) {
        try (final var stack = memoryStack.push()) {
            final var viewport = VkViewport.calloc(1, stack);
            viewport.x(x);
            viewport.y(y);
            viewport.width(width);
            viewport.height(height);
            viewport.minDepth(0.0f);
            viewport.maxDepth(1.0f);
            vkCmdSetViewport(commandBuffer, attachment, viewport);
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer setScissor(int attachment, int x, int y, int width, int height) {
        try (final var stack = memoryStack.push()) {
            final var scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(x, y);
            scissor.extent().set(width, height);
            vkCmdSetScissor(commandBuffer, attachment, scissor);
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer clearAttachments(IntList clearColors, double clearDepth, int x, int y, int width, int height) {
        try (final var stack = memoryStack().push()) {
            final var rects = VkClearRect.calloc(clearColors.size() + 1, stack);
            for (int i = 0; i < clearColors.size() + 1; i++) {
                rects.position(i);
                rects.baseArrayLayer(0);
                rects.layerCount(1);
                rects.rect().offset().set(x, y);
                rects.rect().extent().set(width, height);
            }
            rects.position(0);
            
            final var attachments = VkClearAttachment.calloc(clearColors.size() + 1, stack);
            for (int i = 0; i < clearColors.size(); i++) {
                final var vkClearValue = VkClearValue.calloc(stack);
                final var vkClearColor = vkClearValue.color();
                final var clearARGB = clearColors.getInt(i);
                vkClearColor.float32(0, (float) ((clearARGB >> 16) & 0xFF) / 255.0f);
                vkClearColor.float32(1, (float) ((clearARGB >> 8) & 0xFF) / 255.0f);
                vkClearColor.float32(2, (float) ((clearARGB >> 0) & 0xFF) / 255.0f);
                vkClearColor.float32(3, (float) ((clearARGB >> 24) & 0xFF) / 255.0f);
                attachments.position(i);
                attachments.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                attachments.colorAttachment(i);
                attachments.clearValue(vkClearValue);
                
            }
            
            if (clearDepth != -1) {
                final var vkClearValue = VkClearValue.calloc(stack);
                final var clearValue = vkClearValue.depthStencil();
                clearValue.depth((float) clearDepth);
                attachments.position(clearColors.size());
                attachments.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                attachments.clearValue(vkClearValue);
            } else {
                attachments.limit(clearColors.size());
            }
            
            attachments.position(0);
            vkCmdClearAttachments(commandBuffer, attachments, rects);
        }
        return this;
    }
    
    @Override
    public HgCommandBuffer bindPipeline(HgGraphicsPipeline pipeline) {
        final var mercuryPipeline = ((MercuryGraphicsPipeline) pipeline);
        currentPipelineLayout = (MercuryGraphicsPipelineLayout) mercuryPipeline.layout();
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, mercuryPipeline.vkPipeline());
        return this;
    }
    
    @Override
    public HgCommandBuffer bindUniformSet(int index, HgUniformSet uniformSet) {
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, currentPipelineLayout.vkPipelineLayout(), index, new long[]{((MercuryUniformSetPool.SetInstance) uniformSet).set()}, null);
        return this;
    }
    
    @Override
    public HgCommandBuffer bindVertexBuffer(int index, HgBuffer.Slice buffer) {
        vkCmdBindVertexBuffers(commandBuffer, index, new long[]{((MercuryBuffer) buffer.buffer()).vkBuffer()}, new long[]{buffer.offset()});
        return this;
    }
    
    @Override
    public HgCommandBuffer bindIndexBuffer(HgBuffer.Slice buffer, int type) {
        vkCmdBindIndexBuffer(commandBuffer, ((MercuryBuffer) buffer.buffer()).vkBuffer(), buffer.offset(), type);
        return this;
    }
    
    @Override
    public HgCommandBuffer draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        vkCmdDraw(commandBuffer, vertexCount, instanceCount, firstVertex, firstInstance);
        return this;
    }
    
    @Override
    public HgCommandBuffer drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        vkCmdDrawIndexed(commandBuffer, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
        return this;
    }
    
    @Override
    public HgCommandBuffer drawIndirect(HgBuffer.Slice commands) {
        vkCmdDrawIndirect(commandBuffer, ((MercuryBuffer) commands.buffer()).vkBuffer(), commands.offset(), Math.toIntExact(commands.size() / VkDrawIndirectCommand.SIZEOF), VkDrawIndirectCommand.SIZEOF);
        return this;
    }
    
    @Override
    public HgCommandBuffer drawIndexedIndirect(HgBuffer.Slice commands) {
        vkCmdDrawIndexedIndirect(commandBuffer, ((MercuryBuffer) commands.buffer()).vkBuffer(), commands.offset(), Math.toIntExact(commands.size() / VkDrawIndexedIndirectCommand.SIZEOF), VkDrawIndexedIndirectCommand.SIZEOF);
        return this;
    }
}
