package graphics.cinnabar.internal.mixin.mixins.blaze3d.platform;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.IntBuffer;
import java.util.function.Consumer;

@Mixin(GlStateManager.class)
public class GlStateManagerMixin {
    
    @Overwrite
    private static void _upload(
            int level,
            int xOffset,
            int yOffset,
            int width,
            int height,
            NativeImage.Format format,
            IntBuffer pixels,
            Consumer<IntBuffer> output
    ) {
        // TODO: handle this upload!
    }
}
