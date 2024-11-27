package graphics.cinnabar.internal.mixin.mixins.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import graphics.cinnabar.internal.extensions.minecraft.renderer.texture.CinnabarAbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.GL_RG;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

@Mixin(DynamicTexture.class)
public class DynamicTextureMixin {
    
    private CinnabarAbstractTexture asCinnabarTexture() {
        //noinspection DataFlowIssue
        return (CinnabarAbstractTexture) (Object) this;
    }
    
    @Redirect(method = "lambda$new$0()V", at = @At(value = "INVOKE", target = "prepareImage"))
    private void prepareImage1(int textureId, int width, int height) {
        asCinnabarTexture().prepareImage(VK_FORMAT_R8G8B8A8_UNORM, 0, width, height);
    }
    
    @Redirect(method = "<init>(Lcom/mojang/blaze3d/platform/NativeImage;)V", at = @At(value = "INVOKE", target = "prepareImage"))
    private void prepareImage2(int textureId, int width, int height) {
        asCinnabarTexture().prepareImage(VK_FORMAT_R8G8B8A8_UNORM, 0, width, height);
    }
    
    @Redirect(method = "<init>(IIZ)V", at = @At(value = "INVOKE", target = "prepareImage"))
    private void prepareImage3(int textureId, int width, int height) {
        asCinnabarTexture().prepareImage(VK_FORMAT_R8G8B8A8_UNORM, 0, width, height);
    }
}
