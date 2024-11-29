package graphics.cinnabar.internal.mixin.mixins.minecraft.renderer;

import com.mojang.blaze3d.vertex.VertexBuffer;
import graphics.cinnabar.internal.extensions.blaze3d.vertex.CinnabarVertexBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import graphics.cinnabar.api.annotations.NotNullDefault;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NotNullDefault
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
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
