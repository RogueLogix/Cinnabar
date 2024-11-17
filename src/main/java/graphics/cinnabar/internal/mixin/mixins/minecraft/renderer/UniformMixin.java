package graphics.cinnabar.internal.mixin.mixins.minecraft.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@NonnullDefault
@Mixin(Uniform.class)
public class UniformMixin {
    @Overwrite
    public static void glBindAttribLocation(int program, int index, CharSequence name) {;
    }
}
