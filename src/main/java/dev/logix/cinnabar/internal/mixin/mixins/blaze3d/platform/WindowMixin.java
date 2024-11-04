package dev.logix.cinnabar.internal.mixin.mixins.blaze3d.platform;

import com.mojang.blaze3d.platform.Window;
import dev.logix.cinnabar.internal.extensions.blaze3d.platform.CinnabarWindow;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Mixin(Window.class)
public class WindowMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"
            )
    )
    private long setupMinecraftWindow(final IntSupplier width, final IntSupplier height, final Supplier<String> title, final LongSupplier monitor){
        return CinnabarWindow.setupMinecraftWindow(width.getAsInt(), height.getAsInt(), title.get(), monitor.getAsLong());
    }
    
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