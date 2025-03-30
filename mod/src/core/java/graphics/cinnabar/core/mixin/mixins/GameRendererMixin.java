package graphics.cinnabar.core.mixin.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    
    @Shadow
    private float zoom = 1.0F;
    @Shadow
    private float zoomX;
    @Shadow
    private float zoomY;
    @Shadow
    private Minecraft minecraft;
    
    @Shadow
    public float getDepthFar(){
        return 0;
    }
    
    
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "setOrtho"))
    private static Matrix4f setOrtho0to1(Matrix4f matrix, float left, float right, float bottom, float top, float zNear, float zFar) {
        return matrix.setOrtho(left, right, bottom, top, zNear, zFar, true);
    }
    
    @Overwrite
    private void tryTakeScreenshotIfNeeded() {
        // TODO: implement this properly
    }
    
    @Overwrite
    public Matrix4f getProjectionMatrix(float fov) {
        Matrix4f matrix4f = new Matrix4f();
        if (this.zoom != 1.0F) {
            matrix4f.translate(this.zoomX, -this.zoomY, 0.0F);
            matrix4f.scale(this.zoom, this.zoom, 1.0F);
        }
        
        return matrix4f.perspective(
                fov * (float) (Math.PI / 180.0),
                (float)this.minecraft.getWindow().getWidth() / this.minecraft.getWindow().getHeight(),
                0.05F,
                this.getDepthFar(),
                true
        );
    }
}
