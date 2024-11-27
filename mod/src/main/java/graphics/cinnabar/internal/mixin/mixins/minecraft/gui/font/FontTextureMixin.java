package graphics.cinnabar.internal.mixin.mixins.minecraft.gui.font;

import com.mojang.blaze3d.platform.NativeImage;
import graphics.cinnabar.internal.extensions.minecraft.renderer.texture.CinnabarAbstractTexture;
import net.minecraft.client.gui.font.FontTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.GL_RG;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

@Mixin(FontTexture.class)
public class FontTextureMixin {
    private CinnabarAbstractTexture asCinnabarTexture() {
        //noinspection DataFlowIssue
        return (CinnabarAbstractTexture) (Object) this;
    }
    
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "prepareImage"))
    private void Cinnabar$FontTextureMixin$prepareImage(NativeImage.InternalGlFormat pixelFormat, int textureId, int width, int height) {
        asCinnabarTexture().prepareImage(formatRemapper(pixelFormat.glFormat()), 0, width, height);
    }
    
    private static int formatRemapper(int format) {
        // remap to VK format
        return switch (format) {
            case GL_RED -> VK_FORMAT_R8_UNORM;
            case GL_RG -> VK_FORMAT_R8G8_UNORM;
            case GL_RGB -> VK_FORMAT_R8G8B8_UNORM;
            case GL_RGBA -> VK_FORMAT_R8G8B8A8_UNORM;
            default -> -1;
        };
    }
}
