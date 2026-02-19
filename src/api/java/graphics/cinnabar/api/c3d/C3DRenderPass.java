package graphics.cinnabar.api.c3d;

import com.mojang.blaze3d.systems.RenderPassBackend;
import graphics.cinnabar.api.hg.HgCommandBuffer;
import it.unimi.dsi.fastutil.ints.IntList;

public interface C3DRenderPass extends RenderPassBackend {
    void clearAttachments(IntList clearColors, double clearDepth, int x, int y, int width, int height);
    
    HgCommandBuffer commandBuffer();
}
