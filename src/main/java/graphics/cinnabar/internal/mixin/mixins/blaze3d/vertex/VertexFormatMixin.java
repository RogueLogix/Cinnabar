package graphics.cinnabar.internal.mixin.mixins.blaze3d.vertex;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.internal.extensions.blaze3d.vertex.CinnabarVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(VertexFormat.class)
public class VertexFormatMixin {
    @Redirect(method = "getImmediateDrawVertexBuffer", at = @At(value = "NEW", target = "(Lcom/mojang/blaze3d/vertex/VertexBuffer$Usage;)Lcom/mojang/blaze3d/vertex/VertexBuffer;"))
    private VertexBuffer getImmediateDrawVertexBuffer$createVertexBuffer(VertexBuffer.Usage usage) {
        return new CinnabarVertexBuffer(usage);
    }
}
