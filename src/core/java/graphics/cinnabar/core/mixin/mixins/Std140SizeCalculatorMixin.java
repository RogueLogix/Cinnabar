package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Std140SizeCalculator.class)
public class Std140SizeCalculatorMixin {
    
    private int firstElementAlignment = -1;
    @Shadow
    private int size;
    
    @Overwrite(remap = false)
    public int get() {
        return Mth.roundToward(size, firstElementAlignment);
    }
    
    @Overwrite(remap = false)
    public Std140SizeCalculator align(int alignment) {
        if (firstElementAlignment == -1) {
            firstElementAlignment = alignment;
        }
        this.size = Mth.roundToward(this.size, alignment);
        return (Std140SizeCalculator) (Object) this;
    }
}
