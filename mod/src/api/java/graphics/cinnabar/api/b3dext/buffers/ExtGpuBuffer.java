package graphics.cinnabar.api.b3dext.buffers;

import com.mojang.blaze3d.buffers.GpuBuffer;

public abstract class ExtGpuBuffer extends GpuBuffer {
    public ExtGpuBuffer(int size, int usage) {
        super(size, usage);
    }
}
