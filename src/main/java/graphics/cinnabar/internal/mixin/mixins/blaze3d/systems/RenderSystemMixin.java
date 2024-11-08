package graphics.cinnabar.internal.mixin.mixins.blaze3d.systems;

import com.mojang.blaze3d.systems.RenderSystem;
import graphics.cinnabar.internal.mixin.helpers.blaze3d.systems.RenderSystemMixinHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(RenderSystem.class)
public class RenderSystemMixin {
    @Overwrite
    public static void initRenderer(int debugVerbosity, boolean synchronous) {
        // ignored, Cinnabar takes care of the debug setup
        // TODO: do need to still handle the GLX cpuInfo setup though,
    }
    
    @Overwrite
    public static int maxSupportedTextureSize() {
        return RenderSystemMixinHelper.maxSupportedTextureSize();
    }
}
