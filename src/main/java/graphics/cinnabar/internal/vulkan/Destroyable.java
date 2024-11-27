package graphics.cinnabar.internal.vulkan;

import graphics.cinnabar.api.annotations.ThreadSafety;

public interface Destroyable {
    // destroys may be called by a worker thread
    @ThreadSafety.Any
    void destroy();
}
