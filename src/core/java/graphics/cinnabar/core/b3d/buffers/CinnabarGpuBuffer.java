package graphics.cinnabar.core.b3d.buffers;

import graphics.cinnabar.api.cvk.buffers.CVKGpuBuffer;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.util.MagicNumbers;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import org.jetbrains.annotations.Nullable;

import static org.lwjgl.vulkan.VK13.*;

public sealed abstract class CinnabarGpuBuffer extends CVKGpuBuffer implements Destroyable permits BufferPool.Buffer, CinnabarIndividualGpuBuffer {
    protected final CinnabarDevice device;
    private boolean isClosed = false;
    
    private long lastAccessFrame = -1;
    
    public CinnabarGpuBuffer(CinnabarDevice device, int usage, int size, @Nullable String name) {
        super(usage, size);
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
    
    public void accessed() {
        lastAccessFrame = device.currentFrame();
    }
    
    protected abstract VkBuffer.Slice internalBackingSlice();
    
    public VkBuffer.Slice backingSliceDirectAccess() {
        if (!canAccessDirectly()) {
            throw new IllegalStateException();
        }
        accessed();
        return internalBackingSlice();
    }
    
    public VkBuffer.Slice backingSlice() {
        accessed();
        return internalBackingSlice();
    }
    
    public boolean accessedThisFrame() {
        return lastAccessFrame == device.currentFrame();
    }
    
    public boolean canAccessDirectly() {
        // vertex buffers (and everything else) seem to be fine though, so normal rules can apply there
        // if this buffer hasn't been used for at least MaximumFramesInFlight frames, and is CPU mappable, it can be memcpyed directly to
        // this is somewhat rare, but worth checking for, most commonly this is newly created buffers
        return internalBackingSlice().buffer().allocationInfo.pMappedData() != 0 && lastAccessFrame < device.currentFrame() - MagicNumbers.MaximumFramesInFlight;
    }
    
    protected static int vkUsageBits(int b3dUsage, boolean clientStorage) {
        int bits = 0;
        if (b3dUsage > ((USAGE_INDIRECT_COMMANDS << 1) - 1)) {
            throw new IllegalArgumentException("Unknown b3dUsage bits set");
        }
        // always need transfer_dst for uploads to device buffers
        if (!clientStorage || (b3dUsage & USAGE_COPY_DST) != 0) {
            bits |= VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        }
        if ((b3dUsage & USAGE_COPY_SRC) != 0) {
            bits |= VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        }
        if ((b3dUsage & USAGE_VERTEX) != 0) {
            bits |= VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        }
        if ((b3dUsage & USAGE_INDEX) != 0) {
            bits |= VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        }
        if ((b3dUsage & USAGE_UNIFORM) != 0) {
            bits |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        }
        if ((b3dUsage & USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
            bits |= VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
        }
        if ((b3dUsage & USAGE_INDIRECT_COMMANDS) != 0) {
            bits |= VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        }
        return bits;
    }
    
    protected boolean clientStorage(int b3dUsage) {
        // any mappable buffer must be in client storage if the device memory isn't mapable
        if (((device.deviceMemoryType.rightInt() & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) == 0) && ((b3dUsage & (USAGE_MAP_WRITE | USAGE_MAP_READ)) != 0)) {
            return true;
        }
        return (b3dUsage & (USAGE_HINT_CLIENT_STORAGE)) != 0;
    }
}
