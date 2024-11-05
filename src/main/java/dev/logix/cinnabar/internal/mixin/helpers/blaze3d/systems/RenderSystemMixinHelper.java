package dev.logix.cinnabar.internal.mixin.helpers.blaze3d.systems;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.logix.cinnabar.internal.CinnabarRenderer;
import org.spongepowered.asm.mixin.Overwrite;

public class RenderSystemMixinHelper extends RenderSystem {
    @Overwrite
    public static int maxSupportedTextureSize() {
        if (MAX_SUPPORTED_TEXTURE_SIZE == -1) {
            MAX_SUPPORTED_TEXTURE_SIZE = Math.min(Math.min(CinnabarRenderer.limits().maxFramebufferWidth(), CinnabarRenderer.limits().maxFramebufferHeight()), CinnabarRenderer.limits().maxImageDimension2D());
        }
        return MAX_SUPPORTED_TEXTURE_SIZE;
    }
}
