package graphics.cinnabar.api.cvk.systems;

import com.mojang.blaze3d.textures.GpuTextureView;
import graphics.cinnabar.api.b3dext.systems.ExtCommandEncoder;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

public interface CVKCommandEncoder extends ExtCommandEncoder {
    
    // ---------- Overrides for return time, function unmodified ----------
    
    CVKRenderPass createRenderPass(Supplier<String> debugGroup, GpuTextureView colorAttachment, OptionalInt colorClear);
    
    CVKRenderPass createRenderPass(Supplier<String> debugGroup, GpuTextureView colorAttachment, OptionalInt colorClear, @Nullable GpuTextureView depthAttachment, OptionalDouble depthClear);
}
