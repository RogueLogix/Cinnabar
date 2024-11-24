package graphics.cinnabar.internal.mixin.mixins.blaze3d.vertex;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.internal.extensions.blaze3d.vertex.CinnabarVertexBuffer;
import graphics.cinnabar.internal.mixin.helpers.blaze3d.vertex.VertexFormatMixinHelper;
import graphics.cinnabar.internal.vulkan.Destroyable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(VertexFormat.class)
public class VertexFormatMixin implements Destroyable {
    @Redirect(method = "getImmediateDrawVertexBuffer", at = @At(value = "NEW", target = "(Lcom/mojang/blaze3d/vertex/VertexBuffer$Usage;)Lcom/mojang/blaze3d/vertex/VertexBuffer;"))
    private VertexBuffer getImmediateDrawVertexBuffer$createVertexBuffer(VertexBuffer.Usage usage) {
        VertexFormatMixinHelper.register(this);
        return new CinnabarVertexBuffer(usage);
    }
    
    @Shadow
    private VertexBuffer immediateDrawVertexBuffer;
    
    @Override
    public void destroy() {
        if (immediateDrawVertexBuffer != null) {
            immediateDrawVertexBuffer.close();
        }
    }
}
