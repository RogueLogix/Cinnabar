package graphics.cinnabar.core.profiling;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.textures.*;
import com.mojang.jtracy.TracyClient;
import graphics.cinnabar.core.hg3d.Hg3D;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Supplier;

public class ProfilingGpuDevice implements GpuDeviceBackend {
    
    private final GpuDeviceBackend realDevice;
    private final CommandEncoderBackend commandEncoder;
    
    public ProfilingGpuDevice(GpuDeviceBackend realDevice) {
        this.realDevice = realDevice;
        commandEncoder = new ProfilingCommandEncoder(this, realDevice.createCommandEncoder());
    }
    
    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return commandEncoder;
    }
    
    @Override
    public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        try (final var ignored = TracyClient.beginZone("GpuDevice.createSampler", false)) {
            return realDevice.createSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
        }
    }
    
    @Override
    public GpuTexture createTexture(@Nullable Supplier<String> label, @GpuTexture.Usage int usage, TextureFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        try (final var ignored = TracyClient.beginZone("GpuDevice.createTexture", false)) {
            return realDevice.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
    }
    
    @Override
    public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, TextureFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        try (final var ignored = TracyClient.beginZone("GpuDevice.createTexture", false)) {
            return realDevice.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
    }
    
    @Override
    public GpuTextureView createTextureView(GpuTexture texture) {
        try (final var ignored = TracyClient.beginZone("GpuDevice.createTextureView", false)) {
            return realDevice.createTextureView(texture);
        }
    }
    
    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        try (final var ignored = TracyClient.beginZone("GpuDevice.createTextureView", false)) {
            return realDevice.createTextureView(texture, baseMipLevel, mipLevels);
        }
    }
    
    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
        try (final var ignored = TracyClient.beginZone("GpuDevice.createBuffer(size)", false)) {
            return realDevice.createBuffer(label, usage, size);
        }
    }
    
    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
        try (final var ignored = TracyClient.beginZone("GpuDevice.createBuffer(data)", false)) {
            return realDevice.createBuffer(label, usage, data);
        }
    }
    
    @Override
    public String getImplementationInformation() {
        return realDevice.getImplementationInformation();
    }
    
    @Override
    public List<String> getLastDebugMessages() {
        return realDevice.getLastDebugMessages();
    }
    
    @Override
    public boolean isDebuggingEnabled() {
        return realDevice.isDebuggingEnabled();
    }
    
    @Override
    public String getVendor() {
        return realDevice.getVendor();
    }
    
    @Override
    public String getBackendName() {
        return "Profiling(" + realDevice.getBackendName() + ")";
    }
    
    @Override
    public String getVersion() {
        return realDevice.getVersion();
    }
    
    @Override
    public String getRenderer() {
        return realDevice.getRenderer();
    }
    
    @Override
    public int getMaxTextureSize() {
        return realDevice.getMaxTextureSize();
    }
    
    @Override
    public int getUniformOffsetAlignment() {
        return realDevice.getUniformOffsetAlignment();
    }
    
    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, @Nullable ShaderSource shaderSource) {
        try (final var ignored = TracyClient.beginZone("GpuDevice.precompilePipeline", false)) {
            return realDevice.precompilePipeline(pipeline, shaderSource);
        }
    }
    
    @Override
    public void clearPipelineCache() {
        try (final var ignored = TracyClient.beginZone("GpuDevice.clearPipelineCache", false)) {
            realDevice.clearPipelineCache();
        }
    }
    
    @Override
    public List<String> getEnabledExtensions() {
        return realDevice.getEnabledExtensions();
    }
    
    @Override
    public int getMaxSupportedAnisotropy() {
        return realDevice.getMaxSupportedAnisotropy();
    }
    
    @Override
    public void close() {
        try (final var ignored = TracyClient.beginZone("GpuDevice.close", false)) {
            realDevice.close();
        }
    }
    
    @Override
    public void setVsync(boolean enabled) {
        try (final var ignored = TracyClient.beginZone("GpuDevice.setVsync", false)) {
            realDevice.setVsync(enabled);
        }
    }
    
    @Override
    public void presentFrame() {
        try (final var ignored = TracyClient.beginZone("GpuDevice.presentFrame", false)) {
            realDevice.presentFrame();
        }
    }
    
    @Override
    public boolean isZZeroToOne() {
        return realDevice.isZZeroToOne();
    }

    // FIXME - Somehow get realDevice.supportsReverseZ()Z
    public boolean supportsReverseZ() {
        return Hg3D.USE_REVERSE_Z;
    }
}
