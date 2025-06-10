package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.systems.TimerQuery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Optional;

@Mixin(TimerQuery.class)
public class TimerQueryMixin {
    @Overwrite
    public static Optional<TimerQuery> getInstance() {
        return Optional.empty();
    }
}
