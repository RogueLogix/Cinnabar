package graphics.cinnabar.core.profiling;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.GLFWErrorScope;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.WindowAndDevice;
import graphics.cinnabar.core.hg3d.Hg3DGpuDevice;

import static org.lwjgl.glfw.GLFW.*;

public class ProfilingBackend implements GpuBackend {
    @Override
    public String getName() {
        return "CinnabarVK Profiling";
    }
    
    @Override
    public WindowAndDevice createDeviceWithWindow(int width, int height, String title, long monitor, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions) throws BackendCreationException {
        GLFWErrorCapture glfwErrors = new GLFWErrorCapture();
        
        try (GLFWErrorScope ignored = new GLFWErrorScope(glfwErrors)) {
            
            final long window;
            final Hg3DGpuDevice device;
            #if NEO
            var earlyLoadingScreen = net.neoforged.fml.loading.EarlyLoadingScreenController.current();
            if (earlyLoadingScreen != null) {
                window = earlyLoadingScreen.takeOverGlfwWindow();
                glfwSetWindowTitle(window, title);
                // second VkDevice/VkInstance must be created after the first gets yote, some things get very _very_ mad if you don't
                device = new Hg3DGpuDevice(defaultShaderSource, debugOptions);
            } else
            #endif {
                try {
                    device = new Hg3DGpuDevice(defaultShaderSource, debugOptions);
                } catch (IllegalStateException e) {
                    throw new BackendCreationException("Vulkan not supported");
                }
                glfwDefaultWindowHints();
                glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
                window = glfwCreateWindow(width, height, title, monitor, 0L);
            }
            
            device.attachWindow(window);
            
            return new WindowAndDevice(window, new GpuDevice(new ProfilingGpuDevice(device)));
        }
    }
}
