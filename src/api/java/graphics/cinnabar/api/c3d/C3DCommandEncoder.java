package graphics.cinnabar.api.c3d;

import com.mojang.blaze3d.systems.CommandEncoder;
import graphics.cinnabar.api.hg.HgCommandBuffer;
import graphics.cinnabar.api.hg.HgFramebuffer;
import graphics.cinnabar.api.hg.HgRenderPass;

import java.util.function.Supplier;

public interface C3DCommandEncoder extends CommandEncoder {
    
    void insertCommandBuffer(HgCommandBuffer commandBuffer);
    
    C3DRenderPass createRenderPass(Supplier<String> debugGroup, HgRenderPass renderpass, HgFramebuffer framebuffer);
}
