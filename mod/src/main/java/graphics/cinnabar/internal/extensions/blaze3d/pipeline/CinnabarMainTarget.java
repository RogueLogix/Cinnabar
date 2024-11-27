package graphics.cinnabar.internal.extensions.blaze3d.pipeline;

import com.mojang.blaze3d.pipeline.MainTarget;

public class CinnabarMainTarget extends MainTarget {
    
    public CinnabarMainTarget(int width, int height) {
        super(width, height);
    }
    
    private CinnabarRenderTarget asCinnabarRenderTarget(){
        // mixin plugin will swap the super class
        //noinspection DataFlowIssue
        return (CinnabarRenderTarget) (Object)this;
    }
    
    @Override
    public void createFrameBuffer(int width, int height) {
        resize(width, height, false);
    }
}
