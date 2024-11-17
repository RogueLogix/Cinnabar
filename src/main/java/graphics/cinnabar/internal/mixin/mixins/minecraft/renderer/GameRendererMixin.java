package graphics.cinnabar.internal.mixin.mixins.minecraft.renderer;

import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.internal.extensions.minecraft.renderer.CinnabarShaderInstance;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Redirect(method = "preloadUiShader", at = @At(value = "NEW", target = "(Lnet/minecraft/server/packs/resources/ResourceProvider;Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormat;)Lnet/minecraft/client/renderer/ShaderInstance;"))
    private static ShaderInstance preloadUiShader(ResourceProvider resourceProvider, String name, VertexFormat vertexFormat) throws IOException {
        return new CinnabarShaderInstance(resourceProvider, ResourceLocation.parse(name), vertexFormat);
    }
    
    @Redirect(method = "preloadShader", at = @At(value = "NEW", target = "(Lnet/minecraft/server/packs/resources/ResourceProvider;Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormat;)Lnet/minecraft/client/renderer/ShaderInstance;"))
    private static ShaderInstance preloadShader(ResourceProvider resourceProvider, String name, VertexFormat vertexFormat) throws IOException {
        return new CinnabarShaderInstance(resourceProvider, ResourceLocation.parse(name), vertexFormat);
    }
    
    @Redirect(method = "reloadShaders", at = @At(value = "NEW", target = "(Lnet/minecraft/server/packs/resources/ResourceProvider;Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormat;)Lnet/minecraft/client/renderer/ShaderInstance;"))
    private static ShaderInstance reloadShaders(ResourceProvider resourceProvider, String name, VertexFormat vertexFormat) throws IOException {
        return new CinnabarShaderInstance(resourceProvider, ResourceLocation.parse(name), vertexFormat);
    }
    
}
