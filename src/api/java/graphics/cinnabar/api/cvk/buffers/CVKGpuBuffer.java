package graphics.cinnabar.api.cvk.buffers;

import graphics.cinnabar.api.b3dext.buffers.ExtGpuBuffer;

public abstract class CVKGpuBuffer extends ExtGpuBuffer {
    public CVKGpuBuffer(int size, int usage) {
        super(size, usage);
    }
}
