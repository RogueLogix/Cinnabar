package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.annotations.ThreadSafety;
import it.unimi.dsi.fastutil.ints.IntList;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@SuppressWarnings("UnusedReturnValue")
@ApiStatus.NonExtendable
@ThreadSafety.VulkanObjectHandle(note = "must sync with pool")
public interface HgCommandBuffer extends HgObject {
    
    HgCommandBuffer begin();
    
    HgCommandBuffer end();
    
    HgCommandBuffer barrier();
    
    // ---------- Always valid commands ----------
    
    HgCommandBuffer barrier(long srcStage, long srcAccess, long dstStage, long dstAccess);
    
    HgCommandBuffer queueOwnershipTransfer(HgQueue fromQueue, HgQueue toQueue, @Nullable List<HgBuffer.Slice> buffers, @Nullable List<HgImage.ResourceRange> images, long srcStage, long srcAccess, long dstStage, long dstAccess);
    
    HgCommandBuffer initImages(List<HgImage> images);
    
    // ---------- Outside RenderPass commands ----------
    
    HgCommandBuffer copyBufferToBuffer(HgBuffer.Slice src, HgBuffer.Slice dst);
    
    HgCommandBuffer copyBufferToImage(HgBuffer.ImageSlice buffer, HgImage.TransferRange image);
    
    HgCommandBuffer copyImageToBuffer(HgImage.TransferRange image, HgBuffer.ImageSlice buffer);
    
    HgCommandBuffer copyImageToImage(HgImage.TransferRange src, HgImage.TransferRange dst);
    
    HgCommandBuffer clearColorImage(HgImage.ResourceRange image, int clearARGB);
    
    // -1 to not do the clear for that aspect
    HgCommandBuffer clearDepthStencilImage(HgImage.ResourceRange image, double clearDepth, int clearStencil);
    
    // also flips the image to match GL coordinates
    // must have image acquired
    HgCommandBuffer blitToSwapchain(HgImage.View view, HgSurface.Swapchain swapchain);
    
    HgCommandBuffer beginRenderPass(HgRenderPass renderPass, HgFramebuffer framebuffer);
    
    // ---------- Inside RenderPass commands ----------
    
    HgCommandBuffer endRenderPass();
    
    HgCommandBuffer setViewport(int attachment, int x, int y, int width, int height);
    
    HgCommandBuffer setScissor(int attachment, int x, int y, int width, int height);
    
    HgCommandBuffer clearAttachments(IntList clearColors, double clearDepth, int x, int y, int width, int height);
    
    HgCommandBuffer bindPipeline(HgGraphicsPipeline pipeline);
    
    HgCommandBuffer bindUniformSet(int index, HgUniformSet uniformSet);
    
    HgCommandBuffer bindVertexBuffer(int index, HgBuffer.Slice buffer);
    
    HgCommandBuffer bindIndexBuffer(HgBuffer.Slice buffer, int type);
    
    HgCommandBuffer draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);
    
    HgCommandBuffer drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance);
    
    HgCommandBuffer drawIndirect(HgBuffer.Slice commands);
    
    HgCommandBuffer drawIndexedIndirect(HgBuffer.Slice commands);
    
    interface Pool extends HgObject {
        
        @ThreadSafety.VulkanObjectHandle
        HgCommandBuffer allocate();
        
        @ThreadSafety.VulkanObjectHandle
        void reset();
    }
    
}
