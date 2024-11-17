package graphics.cinnabar.internal.mixin.mixins.minecraft.renderer;

import com.mojang.blaze3d.vertex.VertexBuffer;
import graphics.cinnabar.internal.extensions.blaze3d.vertex.CinnabarVertexBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@NonnullDefault
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Redirect(method = "createStars", at = @At(value = "NEW", target = "(Lcom/mojang/blaze3d/vertex/VertexBuffer$Usage;)Lcom/mojang/blaze3d/vertex/VertexBuffer;"))
    private VertexBuffer createStars$createVertexBuffer(VertexBuffer.Usage usage) {
        return new CinnabarVertexBuffer(usage);
    }
    
    @Redirect(method = "createLightSky", at = @At(value = "NEW", target = "(Lcom/mojang/blaze3d/vertex/VertexBuffer$Usage;)Lcom/mojang/blaze3d/vertex/VertexBuffer;"))
    private VertexBuffer createLightSky$createVertexBuffer(VertexBuffer.Usage usage) {
        return new CinnabarVertexBuffer(usage);
    }
    
    @Redirect(method = "createDarkSky", at = @At(value = "NEW", target = "(Lcom/mojang/blaze3d/vertex/VertexBuffer$Usage;)Lcom/mojang/blaze3d/vertex/VertexBuffer;"))
    private VertexBuffer createDarkSky$createVertexBuffer(VertexBuffer.Usage usage) {
        return new CinnabarVertexBuffer(usage);
    }
}
