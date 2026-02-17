package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.annotations.Constant;
import graphics.cinnabar.api.annotations.ThreadSafety;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.NonExtendable
public interface HgSurface extends HgObject<HgSurface> {
    @ThreadSafety.MainGraphics(note = "may query GLFW framebuffer size, which must be done on the main thread")
    Swapchain createSwapchain(boolean vsync, @Nullable Swapchain previous);
    
    interface Swapchain extends HgObject<Swapchain> {
        
        @Constant
        @ThreadSafety.Many
        int width();
        
        @Constant
        @ThreadSafety.Many
        int height();
        
        @ThreadSafety.Any
        boolean acquire();
        
        @ThreadSafety.Any
        boolean present();
        
        @ThreadSafety.Any
        HgSemaphore currentSemaphore();
    }
}
