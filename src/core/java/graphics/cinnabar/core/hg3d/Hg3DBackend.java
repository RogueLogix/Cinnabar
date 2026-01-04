package graphics.cinnabar.core.hg3d;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.GLFWErrorScope;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.WindowAndDevice;
import graphics.cinnabar.loader.earlywindow.VulkanStartup;

import static org.lwjgl.glfw.GLFW.*;

public class Hg3DBackend implements GpuBackend {
    @Override
    public String getName() {
        return "CinnabarVK Hg3D";
    }
    
    @Override
    public WindowAndDevice createDeviceWithWindow(int width, int height, String title, long monitor, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions) throws BackendCreationException {
        if(!VulkanStartup.isSupported()){
            throw new BackendCreationException("Vulkan not supported");
        }
        
        GLFWErrorCapture glfwErrors = new GLFWErrorCapture();
        
        try (GLFWErrorScope ignored = new GLFWErrorScope(glfwErrors)) {
            final var device = new Hg3DGpuDevice(defaultShaderSource, debugOptions);
            
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            final long window = glfwCreateWindow(width, height, title, monitor, 0L);
            
            device.attachWindow(window);
            
            return new WindowAndDevice(window, device);
        }
    }
}
