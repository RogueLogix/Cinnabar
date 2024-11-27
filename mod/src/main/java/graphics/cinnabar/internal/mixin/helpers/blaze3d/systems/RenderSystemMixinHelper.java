package graphics.cinnabar.internal.mixin.helpers.blaze3d.systems;

import com.mojang.blaze3d.systems.RenderSystem;
import graphics.cinnabar.internal.CinnabarRenderer;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.spongepowered.asm.mixin.Overwrite;

@NonnullDefault
public class RenderSystemMixinHelper extends RenderSystem {
    @Overwrite
    public static int maxSupportedTextureSize() {
        if (MAX_SUPPORTED_TEXTURE_SIZE == -1) {
            MAX_SUPPORTED_TEXTURE_SIZE = Math.min(Math.min(CinnabarRenderer.limits().maxFramebufferWidth(), CinnabarRenderer.limits().maxFramebufferHeight()), CinnabarRenderer.limits().maxImageDimension2D());
        }
        return MAX_SUPPORTED_TEXTURE_SIZE;
    }
}
