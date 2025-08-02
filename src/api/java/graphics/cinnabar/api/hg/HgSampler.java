package graphics.cinnabar.api.hg;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.NonExtendable
public interface HgSampler extends HgObject {
    record CreateInfo(boolean minLinear, boolean magLinear, int addressU, int addressV, int addressW, boolean mip) {
    }
}
