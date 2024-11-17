package graphics.cinnabar.internal.statemachine;

import com.mojang.blaze3d.pipeline.RenderTarget;
import graphics.cinnabar.internal.extensions.blaze3d.pipeline.CinnabarRenderTarget;
import graphics.cinnabar.internal.extensions.blaze3d.platform.CinnabarWindow;
import net.minecraft.client.Minecraft;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

@NonnullDefault
public class CinnabarFramebufferState {
    private static CinnabarWindow window() {
        return (CinnabarWindow)Minecraft.getInstance().getWindow();
    }
    
    @Nullable
    private static CinnabarRenderTarget boundTarget;
    
    public static void bind(@Nullable CinnabarRenderTarget renderTarget) {
        boundTarget = renderTarget;
    }
    
    @Nullable
    public static RenderTarget bound() {
        return boundTarget;
    }
    
    public static void clearColor(float r, float g, float b, float a) {
        if (boundTarget != null) {
            boundTarget.clearColor(r, g, b, a);
        }
    }
    
    public static void clear(int bits) {
        if (boundTarget != null) {
            boundTarget.clear(true);
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
}
