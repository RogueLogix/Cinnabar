package graphics.cinnabar.core.mixin.mixins;

import graphics.cinnabar.earlywindow.VulkanStartup;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(CachedOrthoProjectionMatrixBuffer.class)
public class CachedOrthoProjectionMatrixBufferMixin {
    @Shadow
    private float zNear;
    @Shadow
    private float zFar;
    @Shadow
    private boolean invertY;
    
    @Overwrite
    private Matrix4f createProjectionMatrix(float width, float height) {
        return new Matrix4f().setOrtho(0.0F, width, this.invertY ? height : 0.0F, this.invertY ? 0.0F : height, this.zNear, this.zFar, VulkanStartup.isSupported());
    }
}
