package graphics.cinnabar.internal.mixin.mixins.minecraft.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import graphics.cinnabar.api.annotations.NotNullDefault;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@NotNullDefault
@Mixin(Uniform.class)
public class UniformMixin {
    @Overwrite
    public static void glBindAttribLocation(int program, int index, CharSequence name) {;
        // ignored, VK uses explicit attrib location
    }
    
    @Overwrite
    public static int glGetAttribLocation(int program, CharSequence name) {
        // post uses Position, and Position, and Position
        // its always at location 0
        return 0;
    }
}
