package graphics.cinnabar.internal.mixin.mixins.blaze3d.systems;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import graphics.cinnabar.internal.exceptions.NotImplemented;
import graphics.cinnabar.internal.extensions.minecraft.renderer.texture.CinnabarAbstractTexture;
import graphics.cinnabar.internal.mixin.helpers.blaze3d.systems.RenderSystemMixinHelper;
import graphics.cinnabar.internal.statemachine.CinnabarBlendState;
import graphics.cinnabar.internal.statemachine.CinnabarFramebufferState;
import graphics.cinnabar.internal.statemachine.CinnabarGeneralState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.vulkan.VK10.*;

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
//        GlStateManager._clearDepth(1.0); // no replacement, its always cleared to 1.0
        enableDepthTest();
        depthFunc(GL_LEQUAL);
        projectionMatrix.identity();
        savedProjectionMatrix.identity();
        modelViewMatrix.identity();
        textureMatrix.identity();
        viewport(x, y, width, height);
    }
    
    @Overwrite
    public static void activeTexture(int texture) {
        CinnabarAbstractTexture.active(texture - GL_TEXTURE0);
    }
    
    @Overwrite
    public static void bindTexture(int texture) {
        throw new NotImplemented();
    }
    
    @Overwrite
    public static void clear(int bits, boolean clearError) {
        CinnabarFramebufferState.clear(bits);
    }
    
    @Overwrite
    public static void enableCull() {
        CinnabarGeneralState.cull = true;
    }
    
    @Overwrite
    public static void disableCull() {
        CinnabarGeneralState.cull = false;
    }
    
    @Overwrite
    public static void viewport(int x, int y, int width, int height) {
        CinnabarFramebufferState.setViewport(x, y, width, height);
    }
    
    @Overwrite
    public static void depthMask(boolean enabled) {
        CinnabarGeneralState.depthWrite = enabled;
    }
    
    @Overwrite
    public static void enableBlend() {
        CinnabarBlendState.setEnabled(true);
    }
    
    @Overwrite
    public static void disableBlend() {
        CinnabarBlendState.setEnabled(false);
    }
    
    @Overwrite
    public static void blendFunc(int srcFactor, int dstFactor) {
        CinnabarBlendState.setBlendFactors(srcFactor, dstFactor, srcFactor, dstFactor);
    }
    
    @Overwrite
    public static void blendFunc(GlStateManager.SourceFactor srcFactor, GlStateManager.DestFactor dstFactor) {
        CinnabarBlendState.setBlendFactors(srcFactor.value, srcFactor.value, srcFactor.value, dstFactor.value);
        
    }
    
    @Overwrite
    public static void blendFuncSeparate(GlStateManager.SourceFactor srcFactor, GlStateManager.DestFactor dstFactor, GlStateManager.SourceFactor srcAlpha, GlStateManager.DestFactor dstAlpha) {
        CinnabarBlendState.setBlendFactors(srcFactor.value, dstFactor.value, srcAlpha.value, dstAlpha.value);
        
    }
    
    @Overwrite
    public static void blendFuncSeparate(int srcFactor, int dstFactor, int srcAlpha, int dstAlpha) {
        CinnabarBlendState.setBlendFactors(srcFactor, dstFactor, srcAlpha, dstAlpha);
    }
    
    @Overwrite
    public static void disableDepthTest() {
        CinnabarGeneralState.depthTest = false;
    }
    
    @Overwrite
    public static void enableDepthTest() {
        CinnabarGeneralState.depthTest = true;
    }
    
    private static int depthFuncRemapper(int glFunc) {
        return switch (glFunc){
            case GL_NEVER -> VK_COMPARE_OP_NEVER;
            case GL_LESS -> VK_COMPARE_OP_LESS;
            case GL_EQUAL -> VK_COMPARE_OP_EQUAL;
            case GL_LEQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
            case GL_GREATER -> VK_COMPARE_OP_GREATER;
            case GL_NOTEQUAL -> VK_COMPARE_OP_NOT_EQUAL;
            case GL_GEQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            case GL_ALWAYS -> VK_COMPARE_OP_ALWAYS;
            default -> throw new IllegalStateException("Unexpected value: " + glFunc);
        };
    }
    
    @Overwrite
    public static void depthFunc(int depthFunc) {
        CinnabarGeneralState.depthFunc = depthFuncRemapper(depthFunc);
    }
    
    
    @Overwrite
    public static void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        // TODO: colormask tracking
    }
    
    @Overwrite
    public static void texParameter(int target, int parameterName, int parameter) {
    }

    @Overwrite
    public static void enableScissor(int x, int y, int width, int height) {
        CinnabarFramebufferState.enableGlScissor(x, y, width, height);
    }

    @Overwrite
    public static void disableScissor() {
        CinnabarFramebufferState.disableGlScissor();
    }
}
