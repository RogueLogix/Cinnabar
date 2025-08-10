package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.nio.ByteBuffer;

@Mixin(Std140Builder.class)
public class Std140BuilderMixin {
    private int firstElementAlignment = -1;
    @Shadow
    private ByteBuffer buffer;
    @Shadow
    private int start;
    
    @Overwrite(remap = false)
    public ByteBuffer get() {
        arrayAlign();
        return this.buffer.flip();
    }
    
    @Overwrite(remap = false)
    public Std140Builder align(int alignment) {
        if (firstElementAlignment == -1) {
            firstElementAlignment = alignment;
        }
        int i = this.buffer.position();
        this.buffer.position(this.start + Mth.roundToward(i - this.start, alignment));
        return (Std140Builder) (Object) this;
    }
    
    public Std140Builder arrayAlign() {
        align(firstElementAlignment);
        return (Std140Builder) (Object) this;
    }
}
