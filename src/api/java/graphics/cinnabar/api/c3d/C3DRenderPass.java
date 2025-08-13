package graphics.cinnabar.api.c3d;

import com.mojang.blaze3d.systems.RenderPass;
import it.unimi.dsi.fastutil.ints.IntList;

public interface C3DRenderPass extends RenderPass {
    void clearAttachments(IntList clearColors, double clearDepth, int x, int y, int width, int height);
}
