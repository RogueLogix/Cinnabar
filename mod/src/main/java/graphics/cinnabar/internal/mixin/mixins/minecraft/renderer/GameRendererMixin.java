package graphics.cinnabar.internal.mixin.mixins.minecraft.renderer;

import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.internal.extensions.minecraft.renderer.CinnabarShaderInstance;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "setOrtho"))
    private static Matrix4f setOrtho0to1(Matrix4f matrix, float left, float right, float bottom, float top, float zNear, float zFar) {
        return matrix.setOrtho(left, right, bottom, top, zNear, zFar, true);
    }
    
    @Overwrite
    private void tryTakeScreenshotIfNeeded() {
        // TODO: implement this properly
    }
}
