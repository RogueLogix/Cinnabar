package graphics.cinnabar.api.cvk.systems;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import graphics.cinnabar.api.b3dext.systems.ExtGpuDevice;
import graphics.cinnabar.api.b3dext.textures.ExtGpuTexture;
import graphics.cinnabar.api.cvk.buffers.CVKGpuBuffer;
import graphics.cinnabar.api.cvk.pipeline.CVKCompiledRenderPipeline;
import graphics.cinnabar.api.cvk.textures.CVKGpuTexture;
import graphics.cinnabar.api.cvk.textures.CVKGpuTextureView;
import graphics.cinnabar.api.util.Destroyable;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkQueue;

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
    
    VkQueue graphicsQueue();
    
    int graphicsQueueFamily();
    
    VkQueue computeQueue();
    
    int computeQueueFamily();
    
    VkQueue transferQueue();
    
    int transferQueueFamily();
    
    // ---------- Overrides for return type, function unmodified ----------
    
    // ----- Ext -----
    
    @Override
    CVKGpuTexture createTexture(@Nullable String label, int usage, ExtGpuTexture.Type type, TextureFormat format, int width, int height, int depth, int layers, int mips);
    
    @Override
    CVKGpuTexture createTexture(@Nullable Supplier<String> label, int usage, ExtGpuTexture.Type type, TextureFormat format, int width, int height, int depth, int layers, int mips);
    
    @Override
    CVKGpuTextureView createTextureView(ExtGpuTexture texture, ExtGpuTexture.Type type, TextureFormat format, int baseMipLevel, int mipLevels, int baseArrayLayer, int layerCount);
    
    // ----- Vanilla -----
    
    @Override
    CVKCommandEncoder createCommandEncoder();
    
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
