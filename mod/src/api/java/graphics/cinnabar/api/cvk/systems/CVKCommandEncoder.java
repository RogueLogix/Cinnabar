package graphics.cinnabar.api.cvk.systems;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import graphics.cinnabar.api.b3dext.systems.ExtCommandEncoder;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

public interface CVKCommandEncoder extends ExtCommandEncoder {
    
    void copyBufferToBufferExternallySynced(GpuBufferSlice src, GpuBufferSlice dst, BufferCopy... copies);
    
    // ---------- Deprecated in favor of alternative, function unmodified ----------
    
    /*
     * Use CVKRenderPass clearAttachments instead
     * This is implemented using CVKRenderPass.clearAttachments and creates a RenderPass on the fly
     */
    @Override
    @Deprecated
    void clearColorAndDepthTextures(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth, int scissorX, int scissorY, int scissorWidth, int scissorHeight);
    
    // ---------- Overrides for return time, function unmodified ----------
    
    CVKRenderPass createRenderPass(Supplier<String> debugGroup, GpuTextureView colorAttachment, OptionalInt colorClear);
    
    CVKRenderPass createRenderPass(Supplier<String> debugGroup, GpuTextureView colorAttachment, OptionalInt colorClear, @Nullable GpuTextureView depthAttachment, OptionalDouble depthClear);
}
