package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.GraphicsWorkarounds;
import com.mojang.blaze3d.systems.GpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(GraphicsWorkarounds.class)
public class GraphicsWorkaroundsMixin {
    @Overwrite
    private static boolean isIntelGen11(GpuDevice device) {
        // forces immediate draw to always use a new buffer, which means they can all be uploaded at the beginning onf the frame
        // this massively helps performance
        // mojang happens to support this as a workaround, but ill use it for perf
        return true;
    }
}
