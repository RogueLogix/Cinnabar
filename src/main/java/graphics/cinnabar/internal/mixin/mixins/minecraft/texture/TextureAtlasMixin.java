package graphics.cinnabar.internal.mixin.mixins.minecraft.texture;

import graphics.cinnabar.internal.extensions.minecraft.renderer.texture.CinnabarAbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

@Mixin(TextureAtlas.class)
public class TextureAtlasMixin {
    
    private CinnabarAbstractTexture asCinnabarTexture() {
        //noinspection DataFlowIssue
        return (CinnabarAbstractTexture) (Object) this;
    }
    
    @Redirect(method = "upload", at = @At(value = "INVOKE", target = "prepareImage"))
    private void prepareImage1(int textureId, int mip, int width, int height) {
        asCinnabarTexture().prepareImage(VK_FORMAT_R8G8B8A8_UNORM, mip, width, height);
    }
}
