package graphics.cinnabar.core.profiling;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQuery;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.jtracy.TracyClient;
import graphics.cinnabar.api.exceptions.NotImplemented;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

public class ProfilingCommandEncoder implements CommandEncoderBackend {
    
    private final ProfilingGpuDevice device;
    private final CommandEncoderBackend realCommandEncoder;
    
    public ProfilingCommandEncoder(ProfilingGpuDevice device,  CommandEncoderBackend realCommandEncoder) {
        this.device = device;
        this.realCommandEncoder = realCommandEncoder;
    }
    
    @Override
    public RenderPassBackend createRenderPass(Supplier<String> label, GpuTextureView colorTexture, OptionalInt clearColor) {
        throw new NotImplemented();
    }
    
    @Override
    public RenderPassBackend createRenderPass(Supplier<String> label, GpuTextureView colorTexture, OptionalInt clearColor, @Nullable GpuTextureView depthTexture, OptionalDouble clearDepth) {
        return new ProfilingRenderPass(label, realCommandEncoder.createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth));
    }
    
    @Override
    public boolean isInRenderPass() {
        return realCommandEncoder.isInRenderPass();
    }
    
    @Override
    public void clearColorTexture(GpuTexture colorTexture, int clearColor) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.clearColorTexture", false)) {
            realCommandEncoder.clearColorTexture(colorTexture, clearColor);
        }
    }
    
    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.clearColorAndDepthTextures", false)) {
            realCommandEncoder.clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth);
        }
    }
    
    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth, int regionX, int regionY, int regionWidth, int regionHeight) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.clearColorAndDepthTextures", false)) {
            realCommandEncoder.clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth, regionX, regionY, regionWidth, regionHeight);
        }
    }
    
    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.clearDepthTexture", false)) {
            realCommandEncoder.clearDepthTexture(depthTexture, clearDepth);
        }
    }
    
    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.writeToBuffer", false)) {
            realCommandEncoder.writeToBuffer(destination, data);
        }
    }
    
    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBufferSlice buffer, boolean read, boolean write) {
        return realCommandEncoder.mapBuffer(buffer, read, write);
    }
    
    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.copyToBuffer", false)) {
            realCommandEncoder.copyToBuffer(source, target);
        }
    }
    
    @Override
    public void writeToTexture(GpuTexture destination, NativeImage source, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height, int sourceX, int sourceY) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.writeToTexture", false)) {
            realCommandEncoder.writeToTexture(destination, source, mipLevel, depthOrLayer, destX, destY, width, height, sourceX, sourceY);
        }
    }
    
    @Override
    public void writeToTexture(GpuTexture destination, ByteBuffer source, NativeImage.Format format, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.writeToTexture", false)) {
            realCommandEncoder.writeToTexture(destination, source, format, mipLevel, depthOrLayer, destX, destY, width, height);
        }
    }
    
    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.copyTextureToBuffer", false)) {
            realCommandEncoder.copyTextureToBuffer(source, destination, offset, callback, mipLevel);
        }
    }
    
    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y, int width, int height) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.copyTextureToBuffer", false)) {
            realCommandEncoder.copyTextureToBuffer(source, destination, offset, callback, mipLevel);
        }
    }
    
    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY, int width, int height) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.copyTextureToTexture", false)) {
            realCommandEncoder.copyTextureToTexture(source, destination, mipLevel, destX, destY, sourceX, sourceY, width, height);
        }
    }
    
    @Override
    public void presentTexture(GpuTextureView texture) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.presentTexture", false)) {
            realCommandEncoder.presentTexture(texture);
        }
    }
    
    @Override
    public GpuFence createFence() {
        // TODO: wrap this to profile the wait time too
        try (final var ignored = TracyClient.beginZone("CommandEncoder.createFence", false)) {
            return realCommandEncoder.createFence();
        }
    }
    
    @Override
    public GpuQuery timerQueryBegin() {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.timerQueryBegin", false)) {
            return realCommandEncoder.timerQueryBegin();
        }
    }
    
    @Override
    public void timerQueryEnd(GpuQuery query) {
        try (final var ignored = TracyClient.beginZone("CommandEncoder.timerQueryEnd", false)) {
            realCommandEncoder.timerQueryEnd(query);
        }
    }
}
