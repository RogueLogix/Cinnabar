package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.hg.enums.HgCompareOp;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.NonExtendable
public interface HgSampler extends HgObject<HgSampler> {
    record CreateInfo(boolean minLinear, boolean magLinear, int addressU, int addressV, int addressW, float mip, HgCompareOp compareOp, float maxAnisotropy) {
        public CreateInfo(boolean minLinear, boolean magLinear, int addressU, int addressV, int addressW, float mip){
            this(minLinear, magLinear, addressU, addressV, addressW, mip, HgCompareOp.ALWAYS, 0.0f);
        }
        
        public CreateInfo(boolean minLinear, boolean magLinear, int addressU, int addressV, int addressW, float maxAnisotropy, float mip){
            this(minLinear, magLinear, addressU, addressV, addressW, mip, HgCompareOp.ALWAYS, maxAnisotropy);
        }
    }
}
