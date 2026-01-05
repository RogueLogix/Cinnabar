package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgSurface;
import graphics.cinnabar.loader.earlywindow.GLFWClassloadHelper;
import org.jetbrains.annotations.Nullable;

import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;

public class MercurySurface extends MercuryObject implements HgSurface {
    private final long glfwWindow;
    private final long handle;
    
    public MercurySurface(MercuryDevice device, long glfwWindow) {
        super(device);
        this.glfwWindow = glfwWindow;
        try (final var stack = memoryStack().push()) {
            final var handlePtr = stack.longs(0);
            GLFWClassloadHelper.glfwExtCreateWindowSurface(device.vkDevice().getPhysicalDevice().getInstance(), glfwWindow, null, handlePtr);
            handle = handlePtr.get(0);
        }
    }
    
    @Override
    public void destroy() {
        vkDestroySurfaceKHR(device.vkDevice().getPhysicalDevice().getInstance(), handle, null);
    }
    
    @Override
    public Swapchain createSwapchain(boolean vsync, @Nullable Swapchain previous) {
        return new MercurySwapchain(this, vsync, (MercurySwapchain) previous);
    }
    
    public final long glfwWindow() {
        return glfwWindow;
    }
    
    public long vkSurface() {
        return handle;
    }
}
