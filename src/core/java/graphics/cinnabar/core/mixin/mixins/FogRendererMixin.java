package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.ByteBuffer;

@Mixin(FogRenderer.class)
public class FogRendererMixin {
    
    @Overwrite
    protected void updateBuffer(
            ByteBuffer buffer,
            int position,
            Vector4f fogColor,
            float environmentalStart,
            float environmentalEnd,
            float renderDistanceStart,
            float renderDistanceEnd,
            float skyEnd,
            float cloudEnd
    ) {
        buffer.position(position);
        Std140Builder.intoBuffer(buffer)
                .putVec4(fogColor)
                .putFloat(environmentalStart)
                .putFloat(environmentalEnd)
                .putFloat(renderDistanceStart)
                .putFloat(renderDistanceEnd)
                .putFloat(skyEnd)
                .putFloat(cloudEnd)
                .get();
        // its expected that the buffer not be flipped, so unflip the buffer
        buffer.position(buffer.limit());
    }
}
