package graphics.cinnabar.api.b3dext.buffers;

import com.mojang.blaze3d.buffers.GpuBuffer;

public abstract class ExtGpuBuffer extends GpuBuffer {
    
    public static final int USAGE_INDIRECT_COMMANDS = USAGE_UNIFORM_TEXEL_BUFFER << 1;
    
    public ExtGpuBuffer(int size, int usage) {
        super(size, usage);
    }
}
