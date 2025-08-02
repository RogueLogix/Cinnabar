package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgObject;

public abstract class MercuryObject implements HgObject {
    
    protected final MercuryDevice device;
    
    public MercuryObject(MercuryDevice device) {
        this.device = device;
    }
    
    @Override
    public MercuryDevice device() {
        return device;
    }
}
