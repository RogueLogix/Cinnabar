package graphics.cinnabar.core.b3d.buffers;

import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import graphics.cinnabar.lib.annotations.RewriteHierarchy;
import net.minecraft.util.Mth;

@RewriteHierarchy
public class Std140ArraySizeCalculator extends Std140SizeCalculator {
    
    private int firstElementAlignment = -1;
    
    @Override
    public int get() {
        return Mth.roundToward(super.get(), firstElementAlignment);
    }
    
    @Override
    public Std140SizeCalculator align(int alignment) {
        if (firstElementAlignment == -1) {
            firstElementAlignment = alignment;
        }
        return super.align(alignment);
    }
}
