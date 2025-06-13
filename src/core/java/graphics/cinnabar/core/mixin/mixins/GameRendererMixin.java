package graphics.cinnabar.core.mixin.mixins;

import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    
    @Shadow
    private Minecraft minecraft;
    
    @Shadow
    public float getDepthFar() {
        return 0;
    }
    
    @Overwrite
    public Matrix4f getProjectionMatrix(float fov) {
        Matrix4f matrix4f = new Matrix4f();
        
        return matrix4f.perspective(
                fov * (float) (Math.PI / 180.0),
                (float) this.minecraft.getWindow().getWidth() / this.minecraft.getWindow().getHeight(),
                0.05F,
                this.getDepthFar(),
                VulkanStartup.isSupported()
        );
    }
}
