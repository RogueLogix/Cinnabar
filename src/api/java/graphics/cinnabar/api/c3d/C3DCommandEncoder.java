package graphics.cinnabar.api.c3d;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.hg.HgCommandBuffer;
import graphics.cinnabar.api.hg.HgFramebuffer;
import graphics.cinnabar.api.hg.HgQueue;
import graphics.cinnabar.api.hg.HgRenderPass;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

public interface C3DCommandEncoder extends CommandEncoderBackend {
    
    HgCommandBuffer allocateCommandBuffer();
    
    @API
    void insertCommandBuffer(HgCommandBuffer commandBuffer);
    
    @API
    void insertQueueItem(HgQueue.Item item);
    
    C3DRenderPass createRenderPass(Supplier<String> debugGroup, GpuTextureView colorTexture, OptionalInt clearColor, @Nullable GpuTextureView depthTexture, OptionalDouble clearDepth);
    
    C3DRenderPass createRenderPass(Supplier<String> debugGroup, HgRenderPass renderpass, HgFramebuffer framebuffer);
}
