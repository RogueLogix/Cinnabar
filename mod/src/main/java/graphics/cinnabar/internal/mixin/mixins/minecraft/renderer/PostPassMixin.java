package graphics.cinnabar.internal.mixin.mixins.minecraft.renderer;

import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.internal.extensions.minecraft.renderer.CinnabarEffectInstance;
import graphics.cinnabar.internal.extensions.minecraft.renderer.CinnabarShaderInstance;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;

@Mixin(PostPass.class)
public class PostPassMixin {
    @Redirect(method = "<init>", at = @At(value = "NEW", target = "(Lnet/minecraft/server/packs/resources/ResourceProvider;Ljava/lang/String;)Lnet/minecraft/client/renderer/EffectInstance;"))
    private static EffectInstance createEffectInstance(ResourceProvider resourceProvider, String name) throws IOException {
        return new CinnabarEffectInstance(resourceProvider, name);
    }
}
