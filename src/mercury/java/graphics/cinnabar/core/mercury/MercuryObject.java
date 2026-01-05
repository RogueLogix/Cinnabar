package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgObject;
import org.lwjgl.system.MemoryStack;

import static graphics.cinnabar.core.mercury.Mercury.MEMORY_STACK;

public abstract class MercuryObject implements HgObject {
    
    protected final MercuryDevice device;
    
    public MercuryObject(MercuryDevice device) {
        this.device = device;
    }
    
    @Override
    public MercuryDevice device() {
        return device;
    }
    
    protected static MemoryStack memoryStack() {
        return MEMORY_STACK.get();
    }
}
