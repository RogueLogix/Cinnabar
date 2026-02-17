package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.annotations.Constant;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.hg.enums.HgFormat;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@ApiStatus.NonExtendable
public interface HgRenderPass extends HgObject<HgRenderPass> {
    @Constant
    @ThreadSafety.Many
    int colorAttachmentCount();
    
    record CreateInfo(List<HgFormat> colorFormats, @Nullable HgFormat depthStencilFormat) {
    }
}
