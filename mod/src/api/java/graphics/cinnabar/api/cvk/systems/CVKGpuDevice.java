package graphics.cinnabar.api.cvk.systems;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import graphics.cinnabar.api.b3dext.systems.ExtGpuDevice;
import graphics.cinnabar.api.cvk.buffers.CVKGpuBuffer;
import graphics.cinnabar.api.cvk.pipeline.CVKCompiledRenderPipeline;
import graphics.cinnabar.api.cvk.textures.CVKGpuTexture;
import graphics.cinnabar.api.cvk.textures.CVKTextureView;
import graphics.cinnabar.api.util.Destroyable;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;

import java.nio.ByteBuffer;
import java.util.function.BiFunction;
import java.util.function.Supplier;

@API
public interface CVKGpuDevice extends ExtGpuDevice {
    
    static CVKGpuDevice get() {
        return (CVKGpuDevice) RenderSystem.getDevice();
    }
    
    @API
    VkInstance vkInstance();
    
    @API
    VkDevice vkDevice();
    
    @API
    <T extends Destroyable> T destroyEndOfFrame(T destroyable);
    
    @API
    <T extends Destroyable> T destroyOnShutdown(T destroyable);
    
    @Internal
    void endFrame();
    
    // ---------- Overrides for return time, function unmodified ----------
    
    @Override
    CVKCommandEncoder createCommandEncoder();
    
    @Override
    CVKGpuTexture createTexture(@Nullable Supplier<String> label, int usage, TextureFormat format, int width, int height, int depthOrLayers, int mips);
    
    @Override
    CVKGpuTexture createTexture(@Nullable String label, int usage, TextureFormat format, int width, int height, int depthOrLayers, int mips);
    
    @Override
    CVKTextureView createTextureView(GpuTexture texture);
    
    @Override
    CVKTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels);
    
    @Override
    CVKGpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, int size);
    
    @Override
    CVKGpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data);
    
    @Override
    default CVKCompiledRenderPipeline precompilePipeline(RenderPipeline pipeline) {
        return precompilePipeline(pipeline, null);
    }
    
    @Override
    CVKCompiledRenderPipeline precompilePipeline(RenderPipeline renderPipeline, @Nullable BiFunction<ResourceLocation, ShaderType, String> shaderSource);
}
