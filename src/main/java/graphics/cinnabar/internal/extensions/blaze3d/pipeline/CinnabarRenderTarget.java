package graphics.cinnabar.internal.extensions.blaze3d.pipeline;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sun.jna.platform.win32.WinCrypt;
import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.extensions.blaze3d.platform.CinnabarWindow;
import graphics.cinnabar.internal.statemachine.CinnabarFramebufferState;
import graphics.cinnabar.internal.vulkan.memory.VulkanMemoryAllocation;
import graphics.cinnabar.internal.vulkan.util.LiveHandles;
import graphics.cinnabar.internal.vulkan.util.VulkanQueueHelper;
import net.minecraft.client.Minecraft;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static graphics.cinnabar.internal.vulkan.MagicNumbers.FramebufferColorFormat;
import static graphics.cinnabar.internal.vulkan.MagicNumbers.FramebufferDepthFormat;
import static graphics.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

@NonnullDefault
public class CinnabarRenderTarget extends RenderTarget {
    private final VkDevice device = CinnabarRenderer.device();

    private long colorImageHandle = 0;
    private long colorImageViewHandle = 0;
    @Nullable
    private VulkanMemoryAllocation colorImageAllocation;

    private long depthImageHandle = 0;
    private long depthImageViewHandle = 0;
    @Nullable
    private VulkanMemoryAllocation depthImageAllocation;

    private final VkRect2D renderArea = VkRect2D.calloc();
    private final VkExtent2D renderExtent = renderArea.extent();

    public CinnabarRenderTarget(boolean useDepth) {
        super(useDepth);
    }

    @Override
    protected void _resize(int width, int height, boolean clearError) {
        RenderSystem.assertOnRenderThreadOrInit();
        if (this.colorImageHandle != 0) {
            this.destroyBuffers();
        }

        this.createBuffers(width, height, clearError);
    }

    @Override
    public void createBuffers(int width, int height, boolean clearError) {
        RenderSystem.assertOnRenderThreadOrInit();
        this.width = width;
        this.viewWidth = width;
        this.height = height;
        this.viewHeight = height;
        renderExtent.set(width, height);
        assert colorImageHandle == 0;
        assert colorImageViewHandle == 0;
        assert colorImageAllocation == null;
        assert depthImageHandle == 0;
        assert depthImageViewHandle == 0;
        assert depthImageAllocation == null;
        // TODO: no depth support
        assert useDepth;
        // TODO: stencil support
        assert !isStencilEnabled();

        try (final var stack = MemoryStack.stackPush()) {
            final var longPtr = stack.mallocLong(1);

            final var extent = VkExtent3D.malloc(stack);
            extent.width(width);
            extent.height(height);
            extent.depth(1);

            final var colorImageCreateInfo = VkImageCreateInfo.calloc(stack).sType$Default();
            colorImageCreateInfo.imageType(VK_IMAGE_TYPE_2D);
            colorImageCreateInfo.extent(extent);
            colorImageCreateInfo.mipLevels(1);
            colorImageCreateInfo.arrayLayers(1);
            colorImageCreateInfo.tiling(VK_IMAGE_TILING_OPTIMAL);
            colorImageCreateInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            colorImageCreateInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            colorImageCreateInfo.samples(VK_SAMPLE_COUNT_1_BIT);
            colorImageCreateInfo.flags(0);
            colorImageCreateInfo.usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            colorImageCreateInfo.format(FramebufferColorFormat);

            throwFromCode(vkCreateImage(device, colorImageCreateInfo, null, longPtr));
            colorImageHandle = longPtr.get(0);
            LiveHandles.create(colorImageHandle);

            final var depthImageCreateInfo = VkImageCreateInfo.calloc(stack).sType$Default();
            depthImageCreateInfo.imageType(VK_IMAGE_TYPE_2D);
            depthImageCreateInfo.extent(extent);
            depthImageCreateInfo.mipLevels(1);
            depthImageCreateInfo.arrayLayers(1);
            depthImageCreateInfo.tiling(VK_IMAGE_TILING_OPTIMAL);
            depthImageCreateInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            depthImageCreateInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            depthImageCreateInfo.samples(VK_SAMPLE_COUNT_1_BIT);
            depthImageCreateInfo.flags(0);
            depthImageCreateInfo.usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            depthImageCreateInfo.format(FramebufferDepthFormat);

            throwFromCode(vkCreateImage(device, depthImageCreateInfo, null, longPtr));
            depthImageHandle = longPtr.get(0);
            LiveHandles.create(depthImageHandle);
        }

        try (final var stack = MemoryStack.stackPush()) {
            final var memoryRequirements = VkMemoryRequirements.malloc(stack);

            vkGetImageMemoryRequirements(device, colorImageHandle, memoryRequirements);
            colorImageAllocation = CinnabarRenderer.GPUMemoryAllocator.alloc(memoryRequirements);
            vkBindImageMemory(device, colorImageHandle, colorImageAllocation.memoryHandle(), colorImageAllocation.range().offset());

            vkGetImageMemoryRequirements(device, depthImageHandle, memoryRequirements);
            depthImageAllocation = CinnabarRenderer.GPUMemoryAllocator.alloc(memoryRequirements);
            vkBindImageMemory(device, depthImageHandle, depthImageAllocation.memoryHandle(), depthImageAllocation.range().offset());
        }

        try (final var stack = MemoryStack.stackPush()) {
            final var longPtr = stack.mallocLong(1);

            final var colorImageViewCreateInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
            colorImageViewCreateInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            colorImageViewCreateInfo.format(FramebufferColorFormat);
            final var colorImageSubresourceRange = VkImageSubresourceRange.calloc(stack);
            colorImageSubresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            colorImageSubresourceRange.baseMipLevel(0);
            colorImageSubresourceRange.levelCount(1);
            colorImageSubresourceRange.baseArrayLayer(0);
            colorImageSubresourceRange.layerCount(1);
            colorImageViewCreateInfo.subresourceRange(colorImageSubresourceRange);

            colorImageViewCreateInfo.image(colorImageHandle);
            throwFromCode(vkCreateImageView(device, colorImageViewCreateInfo, null, longPtr));
            colorImageViewHandle = longPtr.get(0);
            LiveHandles.create(colorImageViewHandle);

            final var depthImageViewCreateInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
            depthImageViewCreateInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            depthImageViewCreateInfo.format(FramebufferDepthFormat);
            final var depthImageSubresourceRange = VkImageSubresourceRange.calloc(stack);
            depthImageSubresourceRange.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
            depthImageSubresourceRange.baseMipLevel(0);
            depthImageSubresourceRange.levelCount(1);
            depthImageSubresourceRange.baseArrayLayer(0);
            depthImageSubresourceRange.layerCount(1);
            depthImageViewCreateInfo.subresourceRange(depthImageSubresourceRange);

            depthImageViewCreateInfo.image(depthImageHandle);
            throwFromCode(vkCreateImageView(device, depthImageViewCreateInfo, null, longPtr));
            depthImageViewHandle = longPtr.get(0);
            LiveHandles.create(depthImageViewHandle);
        }
        
        try (final var stack = MemoryStack.stackPush()) {
            final var commandBuffer = CinnabarRenderer.queueHelper.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS);
            
            final var colorSubresourceRange = VkImageSubresourceRange.calloc(stack);
            colorSubresourceRange.baseMipLevel(0);
            colorSubresourceRange.levelCount(1);
            colorSubresourceRange.baseArrayLayer(0);
            colorSubresourceRange.layerCount(1);
            colorSubresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            final var depthSubresourceRange = VkImageSubresourceRange.calloc(stack);
            depthSubresourceRange.baseMipLevel(0);
            depthSubresourceRange.levelCount(1);
            depthSubresourceRange.baseArrayLayer(0);
            depthSubresourceRange.layerCount(1);
            depthSubresourceRange.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
            
            
            // dont care to general
            // wait for nothing, but transfer (clear) must wait for this
            final var depInfo = VkDependencyInfo.calloc(stack).sType$Default();
            final var imageBarriers = VkImageMemoryBarrier2.calloc(2, stack);
            imageBarriers.position(0);
            imageBarriers.sType$Default();
            imageBarriers.image(colorImageHandle);
            imageBarriers.srcStageMask(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
            imageBarriers.dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT);
            imageBarriers.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            imageBarriers.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageBarriers.newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            imageBarriers.subresourceRange(colorSubresourceRange);
            imageBarriers.position(1);
            imageBarriers.sType$Default();
            imageBarriers.image(depthImageHandle);
            imageBarriers.srcStageMask(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
            imageBarriers.dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT);
            imageBarriers.dstAccessMask(VK_ACCESS_MEMORY_WRITE_BIT);
            imageBarriers.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageBarriers.newLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL);
            imageBarriers.subresourceRange(depthSubresourceRange);
            imageBarriers.position(0);
            depInfo.pImageMemoryBarriers(imageBarriers);
            vkCmdPipelineBarrier2(commandBuffer, depInfo);
        }
    }

    @Override
    public void destroyBuffers() {
        CinnabarRenderer.waitIdle();

        RenderSystem.assertOnRenderThreadOrInit();
        assert colorImageHandle != 0;
        assert colorImageViewHandle != 0;
        assert colorImageAllocation != null;
        assert depthImageHandle != 0;
        assert depthImageViewHandle != 0;
        assert depthImageAllocation != null;

        vkDestroyImageView(device, depthImageViewHandle, null);
        vkDestroyImageView(device, colorImageViewHandle, null);
        vkDestroyImage(device, depthImageHandle, null);
        vkDestroyImage(device, colorImageHandle, null);
        LiveHandles.destroy(depthImageViewHandle);
        LiveHandles.destroy(colorImageViewHandle);
        LiveHandles.destroy(depthImageHandle);
        LiveHandles.destroy(colorImageHandle);

        colorImageAllocation.destroy();
        colorImageAllocation = null;
        depthImageAllocation.destroy();
        depthImageAllocation = null;

        depthImageHandle = 0;
        colorImageHandle = 0;
    }

    @Override
    public void clear(boolean clearError) {
        clear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    }

    public void clear(int bits) {
        if((bits & GL_DEPTH_BUFFER_BIT) == 0 && (bits & GL_COLOR_BUFFER_BIT) == 0){
            return;
        }
        final var doColorClear = (bits & GL_COLOR_BUFFER_BIT) != 0;
        final var doDepthClear = (bits & GL_DEPTH_BUFFER_BIT) != 0;
        // no-op should be fine, beginRendering will take care of any required clearing (which is also none for color)
        // other option would be to insert an explicit clear op into the command stream, but then the color op needs to be load (vs clear)
        try (final var stack = MemoryStack.stackPush()) {
            final var commandBuffer = CinnabarRenderer.queueHelper.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS);

            final var colorSubresourceRange = VkImageSubresourceRange.calloc(stack);
            colorSubresourceRange.baseMipLevel(0);
            colorSubresourceRange.levelCount(1);
            colorSubresourceRange.baseArrayLayer(0);
            colorSubresourceRange.layerCount(1);
            colorSubresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            final var depthSubresourceRange = VkImageSubresourceRange.calloc(stack);
            depthSubresourceRange.baseMipLevel(0);
            depthSubresourceRange.levelCount(1);
            depthSubresourceRange.baseArrayLayer(0);
            depthSubresourceRange.layerCount(1);
            depthSubresourceRange.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);


            // dont care to general
            // wait for previous frame/clear (should already be complete really),
            // transfer (clear) must also wait for this
            final var depInfo = VkDependencyInfo.calloc(stack).sType$Default();
            final var imageBarriers = VkImageMemoryBarrier2.calloc(2, stack);
            imageBarriers.position(0);
            imageBarriers.sType$Default();
            imageBarriers.image(colorImageHandle);
            imageBarriers.srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT);
            imageBarriers.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT);
            imageBarriers.dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT);
            imageBarriers.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            imageBarriers.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageBarriers.newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            imageBarriers.subresourceRange(colorSubresourceRange);
            imageBarriers.position(1);
            imageBarriers.sType$Default();
            imageBarriers.image(depthImageHandle);
            imageBarriers.srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT);
            imageBarriers.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT);
            imageBarriers.dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT);
            imageBarriers.dstAccessMask(VK_ACCESS_MEMORY_WRITE_BIT);
            imageBarriers.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageBarriers.newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            imageBarriers.subresourceRange(depthSubresourceRange);
            imageBarriers.position(0);

            if(!doColorClear){
                imageBarriers.position(1);
            } else if (!doDepthClear){
                imageBarriers.limit(1);
            }
            depInfo.pImageMemoryBarriers(imageBarriers);
            vkCmdPipelineBarrier2(commandBuffer, depInfo);

            if(doColorClear) {
                final var clearColor = VkClearColorValue.calloc(stack);
                for (int i = 0; i < 4; i++) {
                    clearColor.float32(i, clearChannels[i]);
                }
                vkCmdClearColorImage(commandBuffer, colorImageHandle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearColor, colorSubresourceRange);
            }

            if(doDepthClear) {
                final var depthClear = VkClearDepthStencilValue.calloc(stack);
                depthClear.depth(1.0f);
                vkCmdClearDepthStencilImage(commandBuffer, depthImageHandle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, depthClear, depthSubresourceRange);
            }

            // no need to do a layout transition here, general is fine
            // wait for transfer, and force everything else to wait on this
            imageBarriers.position(0);
            imageBarriers.srcStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT);
            imageBarriers.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            imageBarriers.dstStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            imageBarriers.dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            imageBarriers.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            imageBarriers.newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            imageBarriers.position(1);
            imageBarriers.srcStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT);
            imageBarriers.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            imageBarriers.dstStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            imageBarriers.dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            imageBarriers.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            imageBarriers.newLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL);
            if(!doColorClear){
                imageBarriers.position(1);
            } else if (!doDepthClear){
                imageBarriers.limit(1);
            }
            vkCmdPipelineBarrier2(commandBuffer, depInfo);
        }
    }

    private void beginRendering(VkCommandBuffer commandBuffer, boolean resuming, boolean suspending) {
        try (final var stack = MemoryStack.stackPush()) {
            final var renderingInfo = VkRenderingInfo.calloc(stack).sType$Default();
            if (resuming) {
                renderingInfo.flags(renderingInfo.flags() | VK_RENDERING_RESUMING_BIT);
            }
            if (suspending) {
                renderingInfo.flags(renderingInfo.flags() | VK_RENDERING_SUSPENDING_BIT);
            }

            final var renderArea = renderingInfo.renderArea();
            // render offset is zeros from the calloc, no need to change
            final var renderExtent = renderArea.extent();
            renderExtent.set(width, height);

            renderingInfo.layerCount(1);

            // load/store is done to allow rendering to be started/stopped without a clear (which may never happen, in which case, i might do the clear ops here)
            final var colorAttachments = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default();
            colorAttachments.imageView(colorImageViewHandle);
            colorAttachments.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            colorAttachments.resolveMode(VK_RESOLVE_MODE_NONE);
            colorAttachments.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            colorAttachments.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            renderingInfo.pColorAttachments(colorAttachments);

            final var depthAttachment = VkRenderingAttachmentInfo.calloc(stack).sType$Default();
            depthAttachment.imageView(depthImageViewHandle);
            depthAttachment.imageLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL);
            depthAttachment.resolveMode(VK_RESOLVE_MODE_NONE);
            depthAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            depthAttachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            renderingInfo.pDepthAttachment(depthAttachment);

            vkCmdBeginRendering(commandBuffer, renderingInfo);
        }
    }

    public void beginRendering(VkCommandBuffer commandBuffer) {
        beginRendering(commandBuffer, false, true);
    }

    public void suspendRendering(VkCommandBuffer commandBuffer) {
        vkCmdEndRendering(commandBuffer);
    }

    public void resumeRendering(VkCommandBuffer commandBuffer) {
        beginRendering(commandBuffer, true, true);
    }

    public void endRendering(VkCommandBuffer commandBuffer) {
        // because the normal being/resume rendering does a suspending renderpass, i need to resume it for completion
        vkCmdEndRendering(commandBuffer);
        beginRendering(commandBuffer, true, false);
        vkCmdEndRendering(commandBuffer);
    }

    @Override
    public void setFilterMode(int filterMode) {
        // TODO:
    }

    public void bindWrite(boolean setViewport) {
        CinnabarFramebufferState.bind(this);
        // TODO viewport
    }

    public void unbindWrite() {
        CinnabarFramebufferState.bind(null);
    }

    public VkRect2D renderArea() {
        return renderArea;
    }

    public void clearColor(float r, float g, float b, float a) {
        clearChannels[0] = r;
        clearChannels[1] = g;
        clearChannels[2] = b;
        clearChannels[3] = a;
    }

    @Override
    public void blitToScreen(int width, int height, boolean disableBlend) {
        try (final var stack = MemoryStack.stackPush()) {
            final var commandBuffer = CinnabarRenderer.queueHelper.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS);

            final var window = (CinnabarWindow) Minecraft.getInstance().getWindow();

            final var imageBarrier = VkImageMemoryBarrier.calloc(1).sType$Default();
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
            imageBarrier.oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            imageBarrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            imageBarrier.image(colorImageHandle);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, imageBarrier);

            imageBarrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            imageBarrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageBarrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            imageBarrier.image(window.getImageForBlit());
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, imageBarrier);

            final var srcOffsets = VkOffset3D.calloc(2, stack);
            srcOffsets.x(0).y(0).z(0);
            srcOffsets.position(1);
            srcOffsets.x(width).y(height).z(1);
            srcOffsets.position(0);

            final var dstOffsets = VkOffset3D.calloc(2, stack);
            dstOffsets.x(0).y(0).z(0);
            dstOffsets.position(1);
            dstOffsets.x(window.swapchainExtent.x).y(window.swapchainExtent.y).z(1);
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

            vkCmdBlitImage(commandBuffer, colorImageHandle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, window.getImageForBlit(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blitRegion, VK_FILTER_NEAREST);

            imageBarrier.newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            imageBarrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            imageBarrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            imageBarrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            imageBarrier.image(colorImageHandle);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT, 0, null, null, imageBarrier);

            imageBarrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            imageBarrier.newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            imageBarrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            imageBarrier.image(window.getImageForBlit());
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT, 0, null, null, imageBarrier);

        }
    }
}
