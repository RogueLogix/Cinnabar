package graphics.cinnabar.internal.mixin.mixins.blaze3d.platform;

import com.mojang.blaze3d.platform.Window;
import graphics.cinnabar.internal.extensions.blaze3d.platform.CinnabarWindow;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Mixin(Window.class)
public class WindowMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V"))
    private void glfwMakeContextCurrent(long window) {
        // no-op
    }
    
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private GLCapabilities createCapabilities() {
        // no-op
        return null;
    }
}