package graphics.cinnabar.internal.mixin.mixins.blaze3d.vertex;

import com.mojang.blaze3d.vertex.VertexBuffer;
import graphics.cinnabar.internal.extensions.blaze3d.vertex.CinnabarVertexBuffer;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NonnullDefault
@Mixin(VertexBuffer.class)
public class VertexBufferMixin {
    
    private VertexBuffer asVertexBuffer(){
        //noinspection DataFlowIssue
        return (VertexBuffer) (Object) this;
    }
    
    @Inject(method = "<init>", at = @At("TAIL"))
    private void typeVerification(CallbackInfo ignored) {
        if (asVertexBuffer() instanceof CinnabarVertexBuffer){
            return;
        }
        throw new IllegalStateException("All VertexBuffers must be CinnabarVertexBuffer instances");
    }
    
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "com/mojang/blaze3d/platform/GlStateManager._glGenBuffers()I"))
    private int noopGenBuffers() {
        return -2;
    }
    
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "com/mojang/blaze3d/platform/GlStateManager._glGenVertexArrays()I"))
    private int noopGenVAO() {
        return -2;
    }
    
    @Overwrite
    public static void unbind() {
    }
}
