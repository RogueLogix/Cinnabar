package graphics.cinnabar.core.b3d.buffers;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.memory.VkBuffer;

import static org.lwjgl.vulkan.VK13.*;

public abstract class CinnabarGpuBuffer extends GpuBuffer implements Destroyable {
    protected final CinnabarDevice device;
    private boolean isClosed = false;
    
    public CinnabarGpuBuffer(CinnabarDevice device, BufferType type, BufferUsage usage, int size) {
        super(type, usage, size);
        this.device = device;
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
        device.destroyEndOfFrame(this);
    }
    
    public abstract VkBuffer getBufferForWrite();
    
    public abstract VkBuffer getBufferForRead();
    
    private static final int defaultUsageBits = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    protected static int typeUsageBits(BufferType bufferType) {
        return defaultUsageBits | switch (bufferType) {
            case VERTICES -> VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
            case INDICES -> VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
            case UNIFORM -> VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
            case COPY_READ, COPY_WRITE -> 0; // default transfer bits covers this
            case PIXEL_PACK, PIXEL_UNPACK -> 0; // pixel (un)pack also counts as just transfer
        };
    }
}
