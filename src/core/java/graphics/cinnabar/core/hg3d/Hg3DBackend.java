package graphics.cinnabar.core.hg3d;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.GLFWErrorScope;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.*;
import com.mojang.jtracy.TracyClient;
import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.core.profiling.ProfilingGpuDevice;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

import java.util.function.Function;

import static org.lwjgl.glfw.GLFW.*;

public class Hg3DBackend implements GpuBackend {
    
    private static final ReferenceArrayList<Function<GpuDeviceBackend, GpuDeviceBackend>> deviceWrappers = new ReferenceArrayList<>();
    
    @Override
    public String getName() {
        return "CinnabarVK Hg3D";
    }
    
    @Override
    public WindowAndDevice createDeviceWithWindow(int width, int height, String title, long monitor, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions) throws BackendCreationException {
        
        if (TracyClient.isAvailable()) {
            deviceWrappers.add(ProfilingGpuDevice::new);
        }
        
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
            #endif
            {
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
            
            GpuDeviceBackend wrappedDevice = device;
            for (int i = deviceWrappers.size() - 1; i >= 0; i--) {
                wrappedDevice = deviceWrappers.get(i).apply(wrappedDevice);
            }
            
            return new WindowAndDevice(window, new GpuDevice(wrappedDevice));
        }
    }
    
    @API
    public static void injectGpuDeviceBackendWrapper(Function<GpuDeviceBackend, GpuDeviceBackend> wrapper) {
        deviceWrappers.add(wrapper);
    }
}
