package graphics.cinnabar.core.profiling;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.jtracy.TracyClient;
import com.mojang.jtracy.Zone;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.function.Supplier;

public class ProfilingRenderPass implements RenderPassBackend {
    
    private final RenderPassBackend realRenderPass;
    private final Zone tracyZone;
    
    public ProfilingRenderPass(Supplier<String> label, RenderPassBackend realRenderPass) {
        tracyZone = TracyClient.beginZone("RenderPass.begin" + label.get(), false);
        this.realRenderPass = realRenderPass;
    }
    
    @Override
    public void close() {
        try (final var ignored = TracyClient.beginZone("RenderPass.close", false)) {
            realRenderPass.close();
        }
        tracyZone.close();
    }
    
    @Override
    public void pushDebugGroup(Supplier<String> label) {
        realRenderPass.pushDebugGroup(label);
    }
    
    @Override
    public void popDebugGroup() {
        realRenderPass.popDebugGroup();
    }
    
    @Override
    public void setPipeline(RenderPipeline pipeline) {
        try (final var ignored = TracyClient.beginZone("RenderPass.setPipeline", false)) {
            realRenderPass.setPipeline(pipeline);
        }
    }
    
    @Override
    public void bindTexture(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
        try (final var ignored = TracyClient.beginZone("RenderPass.bindTexture", false)) {
            realRenderPass.bindTexture(name, textureView, sampler);
        }
    }
    
    @Override
    public void setUniform(String name, GpuBuffer value) {
        try (final var ignored = TracyClient.beginZone("RenderPass.setUniform", false)) {
            realRenderPass.setUniform(name, value);
        }
    }
    
    @Override
    public void setUniform(String name, GpuBufferSlice value) {
        try (final var ignored = TracyClient.beginZone("RenderPass.setUniform", false)) {
            realRenderPass.setUniform(name, value);
        }
    }
    
    @Override
    public void enableScissor(int x, int y, int width, int height) {
        try (final var ignored = TracyClient.beginZone("RenderPass.enableScissor", false)) {
            realRenderPass.enableScissor(x, y, width, height);
        }
    }
    
    @Override
    public void disableScissor() {
        try (final var ignored = TracyClient.beginZone("RenderPass.disableScissor", false)) {
            realRenderPass.disableScissor();
        }
    }
    
    @Override
    public void setVertexBuffer(int slot, GpuBuffer vertexBuffer) {
        try (final var ignored = TracyClient.beginZone("RenderPass.setVertexBuffer", false)) {
            realRenderPass.setVertexBuffer(slot, vertexBuffer);
        }
    }
    
    @Override
    public void setIndexBuffer(GpuBuffer indexBuffer, VertexFormat.IndexType indexType) {
        try (final var ignored = TracyClient.beginZone("RenderPass.setIndexBuffer", false)) {
            realRenderPass.setIndexBuffer(indexBuffer, indexType);
        }
    }
    
    @Override
    public void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount) {
        try (final var ignored = TracyClient.beginZone("RenderPass.drawIndexed", false)) {
            realRenderPass.drawIndexed(baseVertex, firstIndex, indexCount, instanceCount);
        }
    }
    
    @Override
    public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer, VertexFormat.@Nullable IndexType defaultIndexType, Collection<String> dynamicUniforms, T uniformArgument) {
        try (final var ignored = TracyClient.beginZone("RenderPass.drawMultipleIndexed", false)) {
            realRenderPass.drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, dynamicUniforms, uniformArgument);
        }
    }
    
    @Override
    public void draw(int firstVertex, int vertexCount) {
        try (final var ignored = TracyClient.beginZone("RenderPass.draw", false)) {
            realRenderPass.draw(firstVertex, vertexCount);
        }
    }
    
    @Override
    public boolean isClosed() {
        return realRenderPass.isClosed();
    }
}
