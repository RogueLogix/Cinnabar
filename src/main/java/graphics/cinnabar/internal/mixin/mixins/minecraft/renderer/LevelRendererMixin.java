package graphics.cinnabar.internal.mixin.mixins.minecraft.renderer;

import com.mojang.blaze3d.vertex.VertexBuffer;
import graphics.cinnabar.internal.extensions.blaze3d.vertex.CinnabarVertexBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    @Shadow
    @Nullable
    private VertexBuffer starBuffer;
    @Shadow
    @Nullable
    private VertexBuffer skyBuffer;
    @Shadow
    @Nullable
    private VertexBuffer darkBuffer;
    @Shadow
    @Nullable
    private VertexBuffer cloudBuffer;
    
    @Inject(method = "close", at = @At("HEAD"))
    public void extraClose(CallbackInfo ci) {
        // TODO: this is an MC bug, file with Mojang and/or Neo
        if (this.darkBuffer != null) {
            this.darkBuffer.close();
        }
        if (this.skyBuffer != null) {
            this.skyBuffer.close();
        }
        if (this.starBuffer != null) {
            this.starBuffer.close();
        }
        if (this.cloudBuffer != null) {
            this.cloudBuffer.close();
        }
    }
}
