package graphics.cinnabar.api.hg;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@ApiStatus.NonExtendable
public interface HgFramebuffer extends HgObject<HgFramebuffer> {
    
    int width();
    
    int height();
    
    record CreateInfo(HgRenderPass renderPass, List<HgImage.View> colorAttachments, @Nullable HgImage.View depthAttachment) {
    }
}
