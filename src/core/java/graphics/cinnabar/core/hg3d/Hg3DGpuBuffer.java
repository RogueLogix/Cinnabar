package graphics.cinnabar.core.hg3d;

import com.mojang.blaze3d.buffers.GpuBuffer;
import graphics.cinnabar.api.hg.HgBuffer;

public class Hg3DGpuBuffer extends GpuBuffer implements Hg3DObject {
    protected final Hg3DGpuDevice device;
    private final HgBuffer buffer;
    private boolean isClosed = false;
    
    public Hg3DGpuBuffer(Hg3DGpuDevice device, int usage, int size) {
        super(usage, size);
        this.device = device;
        final boolean clientStorageHint = (usage & USAGE_HINT_CLIENT_STORAGE) != 0;
        final boolean mappable = (usage & (USAGE_MAP_READ | USAGE_MAP_WRITE)) != 0;
        final var memoryType = clientStorageHint ? HgBuffer.MemoryType.MAPPABLE : mappable ? HgBuffer.MemoryType.MAPPABLE_PREF_DEVICE : HgBuffer.MemoryType.AUTO_PREF_DEVICE;
        buffer = device.hgDevice().createBuffer(memoryType, size, Hg3DConst.bufferUsageBits(usage));
    }
    
    @Override
    public boolean isClosed() {
        return isClosed;
    }
    
    @Override
    public void close() {
        if (isClosed) {
            return;
        }
        isClosed = true;
        device.destroyEndOfFrame(buffer);
    }
    
    @Override
    public Hg3DGpuDevice device() {
        return device;
    }
    
    public HgBuffer buffer() {
        return buffer;
    }
}
