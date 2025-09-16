package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.annotations.Constant;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.hg.enums.HgFormat;
import it.unimi.dsi.fastutil.longs.LongLongImmutablePair;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@ApiStatus.NonExtendable
public interface HgDevice extends HgObject {
    
    record CreateInfo() {
    } 
    
    @Override
    default HgDevice device() {
        return this;
    }

    interface AllocFailedCallback {
        @ThreadSafety.Many
        boolean allocFailed(boolean deviceLocal, long allocSize);
    }

    @ThreadSafety.Any(lockGroups = "bufferCreate")
    void setAllocFailedCallback(@Nullable AllocFailedCallback callback);
    
    @ThreadSafety.VulkanObjectHandle(note = "must sync with all queues")
    void waitIdle();
    
    @ThreadSafety.Many
    HgQueue queue(HgQueue.Type queueType);

    @ThreadSafety.Many(lockGroups = "bufferCreate")
    HgBuffer createBuffer(HgBuffer.MemoryRequest request, long size, long usage);

    @Nullable
    @ThreadSafety.Many(lockGroups = "bufferCreate")
    HgBuffer tryCreateBuffer(HgBuffer.MemoryRequest request, long size, long usage);

    @ThreadSafety.Many
    HgImage createImage(HgImage.Type type, HgFormat format, int width, int height, int depth, int layers, int mipLevels, long usage, int flags, boolean hostMemory);
    
    @ThreadSafety.Many
    HgSampler createSampler(HgSampler.CreateInfo createInfo);
    
    @ThreadSafety.Many
    HgFramebuffer createFramebuffer(HgFramebuffer.CreateInfo createInfo);
    
    @ThreadSafety.Many
    HgRenderPass createRenderPass(HgRenderPass.CreateInfo createInfo);
    
    @ThreadSafety.Many
    HgUniformSet.Layout createUniformSetLayout(HgUniformSet.Layout.CreateInfo createInfo);
    
    @ThreadSafety.Many
    HgGraphicsPipeline.ShaderSet createShaderSet(HgGraphicsPipeline.ShaderSet.CreateInfo createInfo);
    
    @ThreadSafety.Many
    HgGraphicsPipeline.Layout createPipelineLayout(HgGraphicsPipeline.Layout.CreateInfo createInfo);
    
    @ThreadSafety.Many
    HgGraphicsPipeline createPipeline(HgGraphicsPipeline.CreateInfo createInfo);
    
    @ThreadSafety.Many
    HgSurface createSurface(long glfwWindowHandle);
    
    @ThreadSafety.Many
    HgSemaphore createSemaphore(long initialValue);
    
    @ThreadSafety.Many
    boolean waitSemaphores(List<HgSemaphore.Op> hgSemaphores, long timeout, boolean any);
    
    @Constant
    @ThreadSafety.Many
    Properties properties();
    
    @Constant
    @ThreadSafety.Many
    interface Properties {
        String apiVersion();
        
        String driverVersion();
        
        String renderer();
        
        String vendor();
        
        long uboAlignment();
        
        int maxTexture2dSize();
    }
    
    @ThreadSafety.Any
    void addDebugText(List<String> lines);
    
    @ThreadSafety.Any
    void markFame();

    @ThreadSafety.Many
    LongLongImmutablePair hostLocalMemoryStats();

    @ThreadSafety.Many
    LongLongImmutablePair deviceLocalMemoryStats();

    @Constant
    @ThreadSafety.Many
    boolean UMA();
}
