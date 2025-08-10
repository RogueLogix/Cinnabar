package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.platform.Window;
import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Window.class)
public class WindowMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "org.lwjgl.glfw.GLFW.glfwCreateWindow", remap = false))
    private static long glfwCreateWindow(int width, int height, @NativeType("char const *") CharSequence title, @NativeType("GLFWmonitor *") long monitor, @NativeType("GLFWwindow *") long share) {
        if (VulkanStartup.isSupported()) {
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        }
        return GLFW.glfwCreateWindow(width, height, title, monitor, share);
    }
}
