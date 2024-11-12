package graphics.cinnabar.internal.mixin.mixins.blaze3d.systems;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import graphics.cinnabar.internal.mixin.helpers.blaze3d.systems.RenderSystemMixinHelper;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RenderSystem.class)
public class RenderSystemMixin {
    
    @Overwrite
    public static void initRenderer(int debugVerbosity, boolean synchronous) {
        // ignored, Cinnabar takes care of the debug setup
        // TODO: do need to still handle the GLX cpuInfo setup though,
    }
    
    @Overwrite
    public static int maxSupportedTextureSize() {
        return RenderSystemMixinHelper.maxSupportedTextureSize();
    }
    
    @Shadow
    private static Matrix4f projectionMatrix = new Matrix4f();
    @Shadow
    private static Matrix4f savedProjectionMatrix = new Matrix4f();
    @Shadow
    private static Matrix4f modelViewMatrix = new Matrix4f();
    @Shadow
    private static Matrix4f textureMatrix = new Matrix4f();
    
    @Overwrite
    public static void setupDefaultState(int x, int y, int width, int height) {
//        GlStateManager._clearDepth(1.0);
//        GlStateManager._enableDepthTest();
//        GlStateManager._depthFunc(515);
        projectionMatrix.identity();
        savedProjectionMatrix.identity();
        modelViewMatrix.identity();
        textureMatrix.identity();
//        GlStateManager._viewport(x, y, width, height);
    }
    
}
