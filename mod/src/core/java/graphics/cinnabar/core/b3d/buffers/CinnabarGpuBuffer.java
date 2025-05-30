package graphics.cinnabar.core.b3d.buffers;

import com.mojang.blaze3d.buffers.GpuBuffer;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.util.MagicNumbers;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import graphics.cinnabar.core.vk.memory.VkMemoryAllocation;
import graphics.cinnabar.core.vk.memory.VkMemoryPool;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.vulkan.VK13.*;

public final class CinnabarGpuBuffer extends GpuBuffer implements Destroyable {
    protected final CinnabarDevice device;
    private boolean isClosed = false;
    
    private final VkBuffer backingBuffer;
    private long lastAccessFrame = -1;
    
    public CinnabarGpuBuffer(CinnabarDevice device, int usage, int size, @Nullable String name) {
        super(usage, size);
        this.device = device;
        final var clientStorage = clientStorage(usage);
        backingBuffer = new VkBuffer(device, size, vkUsageBits(usage, clientStorage), clientStorage ? device.hostPersistentMemoryPool : device.devicePersistentMemoryPool);
        backingBuffer.setVulkanName(name);
    }
    
    @Override
    public void destroy() {
        backingBuffer.destroy();
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
    
    public VkBuffer backingBufferDirectAccess() {
        if (!canAccessDirectly()) {
            throw new IllegalStateException();
        }
        accessed();
        return backingBuffer;
    }
    
    public VkBuffer backingBuffer() {
        accessed();
        return backingBuffer;
    }
    
    public boolean accessedThisFrame() {
        return lastAccessFrame == device.currentFrame();
    }
    
    public boolean canAccessDirectly() {
        if ((usage() & USAGE_INDEX) != 0) {
            // for some reason, RADV is unhappy about writing directly to an index buffer on the GPU, idfk why
            // solution, force it to always go through a staging buffer
            return false;
        }
        // vertex buffers (and everything else) seem to be fine though, so normal rules can apply there
        // if this buffer hasn't been used for at least MaximumFramesInFlight frames, and is CPU mappable, it can be memcpyed directly to
        // this is somewhat rare, but worth checking for, most commonly this is newly created buffers
        return backingBuffer.allocation instanceof VkMemoryAllocation.CPU && lastAccessFrame < device.currentFrame() - MagicNumbers.MaximumFramesInFlight;
    }
    
    private static int vkUsageBits(int b3dUsage, boolean clientStorage) {
        int bits = 0;
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
            bits |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        }
        if ((b3dUsage & USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
            bits |= VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
        }
        return bits;
    }
    
    private boolean clientStorage(int b3dUsage) {
        // any mappable buffer must be in client storage if the device memory isn't mapable
        if (!(device.devicePersistentMemoryPool instanceof VkMemoryPool.CPU) && ((b3dUsage & (USAGE_MAP_WRITE | USAGE_MAP_READ)) != 0)) {
            return true;
        }
        return (b3dUsage & (USAGE_HINT_CLIENT_STORAGE)) != 0;
    }
}
