package graphics.cinnabar.api.b3dext.systems;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.b3dext.buffers.ExtGpuBuffer;
import graphics.cinnabar.api.b3dext.pipeline.ExtCompiledRenderPipeline;
import graphics.cinnabar.api.b3dext.textures.ExtGpuTexture;
import graphics.cinnabar.api.b3dext.textures.ExtTextureView;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.function.BiFunction;
import java.util.function.Supplier;

@API
public interface ExtGpuDevice extends GpuDevice {
    static ExtGpuDevice get() {
        return (ExtGpuDevice) RenderSystem.getDevice();
    }
    
    // ---------- Overrides for return time, function unmodified ----------
    
    @Override
    ExtCommandEncoder createCommandEncoder();
    
    @Override
    ExtGpuTexture createTexture(@Nullable Supplier<String> label, int usage, TextureFormat format, int width, int height, int depthOrLayers, int mips);
    
    @Override
    ExtGpuTexture createTexture(@Nullable String label, int usage, TextureFormat format, int width, int height, int depthOrLayers, int mips);
    
    @Override
    ExtTextureView createTextureView(GpuTexture texture);
    
    @Override
    ExtTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels);
    
    @Override
    ExtGpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, int size);
    
    @Override
    ExtGpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data);
    
    @Override
    default ExtCompiledRenderPipeline precompilePipeline(RenderPipeline pipeline) {
        return precompilePipeline(pipeline, null);
    }
    
    @Override
    ExtCompiledRenderPipeline precompilePipeline(RenderPipeline renderPipeline, @Nullable BiFunction<ResourceLocation, ShaderType, String> shaderSource);
}
