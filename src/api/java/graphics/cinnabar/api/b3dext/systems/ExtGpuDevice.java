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
import graphics.cinnabar.api.b3dext.textures.ExtGpuTextureView;
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
    
    ExtCapabilities extCapabilities();
    
    ExtGpuTexture createTexture(@Nullable String label, int usage, ExtGpuTexture.Type type, TextureFormat format, int width, int height, int depth, int layers, int mips);
    
    ExtGpuTexture createTexture(@Nullable Supplier<String> label, int usage, ExtGpuTexture.Type type, TextureFormat format, int width, int height, int depth, int layers, int mips);
    
    ExtGpuTextureView createTextureView(ExtGpuTexture texture, ExtGpuTexture.Type type, TextureFormat format, int baseMipLevel, int mipLevels, int baseArrayLayer, int layerCount);
    
    default ExtGpuTextureView createTextureView(ExtGpuTexture texture, TextureFormat format, int baseMipLevel, int mipLevels, int baseArrayLayer, int layerCount) {
        // by default, create a texture view of the same type as the underlying texture
        // this is required when faking texture views
        return createTextureView(texture, texture.type(), format, baseMipLevel, mipLevels, baseArrayLayer, layerCount);
    }
    
    default ExtGpuTextureView createTextureView(ExtGpuTexture texture, TextureFormat format, int baseMipLevel, int mipLevels) {
        return createTextureView(texture, format, baseMipLevel, mipLevels, 0, texture.layers());
    }
    
    default ExtGpuTextureView createTextureView(ExtGpuTexture texture, TextureFormat format) {
        return createTextureView(texture, format, 0, texture.getMipLevels());
    }
    
    default ExtGpuTextureView createTextureView(ExtGpuTexture texture, int baseMipLevel, int mipLevels, int baseArrayLayer, int layerCount) {
        return createTextureView(texture, texture.getFormat(), baseMipLevel, mipLevels, baseArrayLayer, layerCount);
    }
    
    // ---------- Overrides for return type, function unmodified ----------
    
    @Override
    ExtCommandEncoder createCommandEncoder();
    
    @Override
    default ExtGpuTexture createTexture(@Nullable Supplier<String> label, int usage, TextureFormat format, int width, int height, int depthOrLayers, int mips) {
        // cube textures its layers, non-cube its depth
        final var depth = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) == 0 ? depthOrLayers : 1;
        final var layers = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? depthOrLayers : 1;
        final var type = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) == 0 ? ExtGpuTexture.Type.TYPE_2D : ExtGpuTexture.Type.TYPE_CUBE;
        return createTexture(label, usage, type, format, width, height, depth, layers, mips);
    }
    
    @Override
    default ExtGpuTexture createTexture(@Nullable String label, int usage, TextureFormat format, int width, int height, int depthOrLayers, int mips) {
        // cube textures its layers, non-cube its depth
        final var depth = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) == 0 ? depthOrLayers : 1;
        final var layers = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? depthOrLayers : 1;
        final var type = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) == 0 ? ExtGpuTexture.Type.TYPE_2D : ExtGpuTexture.Type.TYPE_CUBE;
        return createTexture(label, usage, type, format, width, height, depth, layers, mips);
    }
    
    @Override
    default ExtGpuTextureView createTextureView(GpuTexture texture) {
        return createTextureView(texture, 0, texture.getMipLevels());
    }
    
    @Override
    default ExtGpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        if (!(texture instanceof ExtGpuTexture extTexture)) {
            throw new IllegalArgumentException();
        }
        return createTextureView(extTexture, baseMipLevel, mipLevels, 0, extTexture.layers());
    }
    
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
