package graphics.cinnabar.internal.vulkan;

import net.roguelogix.phosphophyllite.threading.ThreadSafety;

public interface Destroyable {
    // destroys may be called by a worker thread
    @ThreadSafety.Any
    void destroy();
}
