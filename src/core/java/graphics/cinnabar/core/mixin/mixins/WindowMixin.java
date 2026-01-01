package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuBackend;
import graphics.cinnabar.core.hg3d.Hg3DBackend;
import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(Window.class)
public class WindowMixin {
    
    @ModifyArgs(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;initializeBackend([Lcom/mojang/blaze3d/systems/GpuBackend;IILjava/lang/String;JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;)Lcom/mojang/blaze3d/systems/WindowAndDevice;")
    )
    private void injectHg3DBackend(Args args) {
        final var defaultBackends = (GpuBackend[])args.get(0);
        final var backends = new GpuBackend[defaultBackends.length + 1];
        backends[0] = new Hg3DBackend();
        for (int i = 0; i < defaultBackends.length; i++) {
            backends[i + 1] = defaultBackends[i];
        }
        args.set(0, backends);
    }
}
