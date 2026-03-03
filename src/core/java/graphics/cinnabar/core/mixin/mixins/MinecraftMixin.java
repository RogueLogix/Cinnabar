package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.systems.GpuBackend;
import graphics.cinnabar.core.hg3d.Hg3DBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.lwjgl.system.Configuration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "<init>", at = @At("HEAD"))
    private static void startup(final GameConfig gameConfig, CallbackInfo info) {
        Configuration.STACK_SIZE.set(256);
    }
    
    @ModifyVariable(
            method = "<init>(Lnet/minecraft/client/main/GameConfig;)V",
            at = @At(value = "STORE"),
            ordinal = 0
    )
    private static GpuBackend[] overwriteBackends(GpuBackend[] defaultBackends){
        final var backends = new GpuBackend[defaultBackends.length + 1];
        backends[0] = new Hg3DBackend();
        System.arraycopy(defaultBackends, 0, backends, 1, defaultBackends.length);
        return backends;
    }
}
