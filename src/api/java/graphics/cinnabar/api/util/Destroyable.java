package graphics.cinnabar.api.util;

import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.threading.IWorkQueue;
import graphics.cinnabar.api.threading.ThreadIndex;

public interface Destroyable extends IWorkQueue.Work {
    
    /*
     * It is only valid to call destroy on an object once
     * If an implementation can detect multiple calls, it should throw an error
     *
     * Some implementations may allow multiple calls, using this more as a reset function than an actual destroy
     * that is the exception, not the rule, check with exact implementation first
     *
     * Any native resources (ie: vulkan objects) should be cleaned up by the time this function returns
     */
    @ThreadSafety.Any
    void destroy();
    
    @Override
    default void accept(ThreadIndex threadIndex) {
        destroy();
    }
}
