package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VertexFormat.Builder.class)
public class VertexFormatBuilderMixin {
    
    @Shadow
    private int offset;
    
    @Inject(method = "add(Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormatElement;)Lcom/mojang/blaze3d/vertex/VertexFormat$Builder;", at = @At("HEAD"))
    private void add(final String name, final VertexFormatElement element, CallbackInfoReturnable<VertexFormat.Builder> ci) {
        // align up, bug in MC w/ missing padding
        this.offset = (this.offset + (element.type().size() - 1)) & -element.type().size();
    }
}
