package dev.logix.cinnabar.internal.extensions.blaze3d.platform;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import dev.logix.cinnabar.Cinnabar;
import dev.logix.cinnabar.internal.CinnabarRenderer;
import net.neoforged.fml.loading.ImmediateWindowHandler;
import org.jetbrains.annotations.Nullable;

import static org.lwjgl.glfw.GLFW.*;

public class CinnabarWindow extends Window {
    public CinnabarWindow(WindowEventHandler eventHandler, ScreenManager screenManager, DisplayData displayData, @Nullable String preferredFullscreenVideoMode, String title) {
        super(eventHandler, screenManager, displayData, preferredFullscreenVideoMode, title);
    }
    
    @Override
    public void close() {
        super.close();
        CinnabarRenderer.destroy();
    }
    
    // called from mixin redirection in super constructor
    public static long setupMinecraftWindow(int width, int height, String title, long monitor) {
        // if the version isn't "3.2" the interned object, a window already exists, and needs to be destroyed
        // if it is "3.2", that means its the dummy, and no window actually exists, so this can be skipped
        //noinspection StringEquality
        if(ImmediateWindowHandler.getGLVersion() != "3.2"){
            final long immediateWindow = ImmediateWindowHandler.setupMinecraftWindow(() -> width, () -> height, () -> title, () -> monitor);
            glfwMakeContextCurrent(0);
            glfwDestroyWindow(immediateWindow);
        }
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE); // TODO: swapchain recreation
        return glfwCreateWindow(width, height, title, monitor, 0);
    }
    
}
