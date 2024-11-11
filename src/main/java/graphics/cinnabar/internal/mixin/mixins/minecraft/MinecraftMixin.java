package graphics.cinnabar.internal.mixin.mixins.minecraft;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.extensions.blaze3d.pipeline.CinnabarMainTarget;
import graphics.cinnabar.internal.extensions.minecraft.renderer.CinnabarVirtualScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.VirtualScreen;
import net.minecraft.util.TimeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;initBackendSystem()Lnet/minecraft/util/TimeSource$NanoTimeSource;"
            )
    )
    private static TimeSource.NanoTimeSource Cinnabar$initBackendSystem() {
        final var nanoTimeSource = RenderSystem.initBackendSystem();
        // need to init GLFW first
        CinnabarRenderer.create();
        return nanoTimeSource;
    }
    
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/client/renderer/VirtualScreen;"
            )
    )
    private static VirtualScreen Cinnabar$createVirtualScreen(Minecraft minecraft) {
        return new CinnabarVirtualScreen(minecraft);
    }
    
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "NEW",
                    target = "(II)Lcom/mojang/blaze3d/pipeline/MainTarget;"
            )
    )
    private static MainTarget Cinnabar$createVirtualScreen(int width, int height) {
        return new CinnabarMainTarget(width, height);
    }
}
