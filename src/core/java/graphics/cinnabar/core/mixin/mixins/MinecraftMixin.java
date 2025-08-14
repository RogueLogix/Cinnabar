package graphics.cinnabar.core.mixin.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.lwjgl.system.Configuration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "<init>", at = @At("HEAD"))
    private static void startup(final GameConfig gameConfig, CallbackInfo info) {
        Configuration.STACK_SIZE.set(256);
    }
}
