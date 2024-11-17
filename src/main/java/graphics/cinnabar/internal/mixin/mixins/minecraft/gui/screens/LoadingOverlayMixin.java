package graphics.cinnabar.internal.mixin.mixins.minecraft.gui.screens;

import graphics.cinnabar.internal.statemachine.CinnabarFramebufferState;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LoadingOverlay.class)
public class LoadingOverlayMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_clearColor(FFFF)V"))
    public void renderClearColor(float r, float g, float b, float a) {
        CinnabarFramebufferState.clearColor(r, g, b, a);
    }
    
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_clear(IZ)V"))
    public void renderClear(int bits, boolean clearError) {
        CinnabarFramebufferState.clear(bits);
    }
}
