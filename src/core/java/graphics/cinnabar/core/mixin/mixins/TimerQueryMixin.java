package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.systems.TimerQuery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(TimerQuery.class)
public class TimerQueryMixin {
    @Overwrite
    public boolean isRecording() {
        return true;
    }
    
    @Overwrite
    public void beginProfile() {
    }
    
    @Overwrite
    public TimerQuery.FrameProfile endProfile() {
        return null;
    }
}
