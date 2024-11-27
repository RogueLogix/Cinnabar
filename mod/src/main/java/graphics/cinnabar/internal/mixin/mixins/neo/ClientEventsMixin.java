package graphics.cinnabar.internal.mixin.mixins.neo;

import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.internal.extensions.minecraft.renderer.CinnabarShaderInstance;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;

@Mixin(ClientHooks.ClientEvents.class)
public class ClientEventsMixin {
    @Redirect(method = "registerShaders", at = @At(value = "NEW", target = "(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)Lnet/minecraft/client/renderer/ShaderInstance;"))
    private static ShaderInstance createShaderInstance(ResourceProvider resourceProvider, ResourceLocation shaderLocation, VertexFormat vertexFormat) throws IOException {
        return new CinnabarShaderInstance(resourceProvider, shaderLocation, vertexFormat);
    }
}
