package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.hg.enums.HgCompareOp;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.NonExtendable
public interface HgSampler extends HgObject {
    record CreateInfo(boolean minLinear, boolean magLinear, int addressU, int addressV, int addressW, boolean mip, HgCompareOp compareOp) {
        public CreateInfo(boolean minLinear, boolean magLinear, int addressU, int addressV, int addressW, boolean mip){
            this(minLinear, magLinear, addressU, addressV, addressW, mip, HgCompareOp.ALWAYS);
        }
    }
}
