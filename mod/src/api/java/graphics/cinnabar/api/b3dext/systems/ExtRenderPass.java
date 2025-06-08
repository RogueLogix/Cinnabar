package graphics.cinnabar.api.b3dext.systems;

import com.mojang.blaze3d.systems.RenderPass;

public interface ExtRenderPass extends RenderPass {
    
    void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);
    
    // ---------- Vanilla RenderPass ----------
    
    @Override
    default void draw(int firstVertex, int vertexCount) {
        draw(vertexCount, 1, firstVertex, 0);
    }
}
