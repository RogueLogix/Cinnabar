package graphics.cinnabar.internal.statemachine;

import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.extensions.blaze3d.pipeline.CinnabarRenderTarget;
import graphics.cinnabar.internal.extensions.blaze3d.platform.CinnabarWindow;
import graphics.cinnabar.internal.vulkan.util.VulkanQueueHelper;
import net.minecraft.client.Minecraft;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.*;

@NonnullDefault
public class CinnabarFramebufferState {
    private static CinnabarWindow window() {
        return (CinnabarWindow) Minecraft.getInstance().getWindow();
    }
    
    @Nullable
    private static CinnabarRenderTarget boundTarget;
    
    public static void bind(@Nullable CinnabarRenderTarget renderTarget) {
        // start renderpass on bind, and end it on unbind
        // its suspended in the mean time
        final var commandBuffer = CinnabarRenderer.queueHelper.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS);
        if (boundTarget != null) {
//            boundTarget.resumeRendering(commandBuffer);
//            boundTarget.endRendering(commandBuffer);
        }
        boundTarget = renderTarget;
        if (boundTarget != null) {
//            boundTarget.beginRendering(commandBuffer);
//            boundTarget.suspendRendering(commandBuffer);
        }
    }
    
    @Nullable
    public static CinnabarRenderTarget bound() {
        return boundTarget;
    }
    
    public static void clearColor(float r, float g, float b, float a) {
        if (boundTarget != null) {
            boundTarget.clearColor(r, g, b, a);
        }
    }
    
    public static void clear(int bits) {
        if (boundTarget != null) {
            boundTarget.clear(bits);
        } else {
            window().clear(bits);
        }
    }
    
    // TODO: not leak this
    private static VkRect2D renderArea = VkRect2D.calloc();
    private static VkOffset2D renderAreaOffset = renderArea.offset();
    
    private static VkExtent2D renderAreaExtent = renderArea.extent();
    
    public static VkRect2D renderArea() {
        if (boundTarget != null) {
            renderArea.set(boundTarget.renderArea());
        } else {
            renderAreaOffset.set(0, 0);
            renderAreaExtent.set(window().swapchainExtent.x, window().swapchainExtent.y);
        }
        return renderArea;
    }
    
    private static VkViewport.Buffer viewport = VkViewport.calloc(1);
    
    private static boolean viewportSet = false;
    
    public static void setViewport(int x, int y, int width, int height) {
        //noinspection resource
        renderArea();
        viewport.x(x);
        viewport.y(renderAreaExtent.height() - y);
        viewport.width(width);
        // flips the view, consistent with OpenGL
        viewport.height(-height);
        viewport.minDepth(0.0f);
        viewport.maxDepth(1.0f);
        viewportSet = true;
    }
    
    public static VkViewport.Buffer viewport() {
        if (!viewportSet) {
            //noinspection resource
            renderArea();
            setViewport(0, 0, renderAreaExtent.width(), renderAreaExtent.height());
        }
        return viewport;
    }
    
    public static void resumeRendering(VkCommandBuffer commandBuffer) {
        assert bound() != null;
        bound().beginRendering(commandBuffer);
    }
    
    public static void suspendRendering(VkCommandBuffer commandBuffer) {
        assert bound() != null;
        bound().endRendering(commandBuffer);
    }
    
    private static VkRect2D.Buffer scissor = VkRect2D.calloc(1);
    
    private static boolean scissorEnabled = false;
    
    public static void enableGlScissor(int x, int y, int width, int height) {
        scissorEnabled = true;
        final var offset = scissor.offset();
        final var extent = scissor.extent();
        offset.set(x, window().swapchainExtent.y - y - height);
        extent.set(width, height);
    }
    
    public static void disableGlScissor() {
        scissorEnabled = false;
    }
    
    public static boolean scissorEnabled() {
        return scissorEnabled;
    }
    
    public static VkRect2D.Buffer scissor() {
        if (!scissorEnabled) {
            //noinspection resource
            renderArea();
            scissor.offset(renderAreaOffset);
            scissor.extent(renderAreaExtent);
        }
        return scissor;
    }
}
