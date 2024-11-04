package dev.logix.cinnabar.internal.mixin.mixins.minecraft;

import dev.logix.cinnabar.internal.extensions.minecraft.renderer.CinnabarVirtualScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.VirtualScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/client/renderer/VirtualScreen;"
            )
    )
    private static VirtualScreen Cinnabar$createVirtualScreen(Minecraft minecraft) {
        return new CinnabarVirtualScreen(minecraft);
    }
}
