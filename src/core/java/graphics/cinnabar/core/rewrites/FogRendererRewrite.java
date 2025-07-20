package graphics.cinnabar.core.rewrites;

import com.mojang.blaze3d.buffers.Std140Builder;
import graphics.cinnabar.api.annotations.RewriteHierarchy;
import graphics.cinnabar.core.b3d.buffers.Std140ArrayBuilder;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector4f;

import java.nio.ByteBuffer;

@RewriteHierarchy
public class FogRendererRewrite extends FogRenderer {
    
    @Override
    protected void updateBuffer(
            ByteBuffer p_423489_,
            int p_423628_,
            @NotNull Vector4f p_423543_,
            float p_423485_,
            float p_423650_,
            float p_423492_,
            float p_423500_,
            float p_423575_,
            float p_423452_
    ) {
        p_423489_.position(p_423628_);
        final var arrayBuilder = (Std140ArrayBuilder) Std140Builder.intoBuffer(p_423489_)
                                                              .putVec4(p_423543_)
                                                              .putFloat(p_423485_)
                                                              .putFloat(p_423650_)
                                                              .putFloat(p_423492_)
                                                              .putFloat(p_423500_)
                                                              .putFloat(p_423575_)
                                                              .putFloat(p_423452_);
        arrayBuilder.arrayAlign();
    }
    
}
