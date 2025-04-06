package graphics.cinnabar.core.vk.descriptors;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.b3d.command.CinnabarCommandEncoder;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import graphics.cinnabar.core.vk.memory.VkMemoryPool;
import it.unimi.dsi.fastutil.objects.ReferenceImmutableList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;

import java.util.List;

import static org.lwjgl.vulkan.VK13.*;

public class UBOBinding implements DescriptorSetBinding {

    private final CinnabarDevice device;
    private final int binding;
    public final int size;
    public final List<UBOMember> members;

    public UBOBinding(CinnabarDevice device, int binding, int size, List<UBOMember> members) {
        this.device = device;
        this.binding = binding;
        this.size = size;
        this.members = new ReferenceImmutableList<>(members);
    }

    @Override
    public int bindingPoint() {
        return binding;
    }

    @Override
    public int type() {
        return VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    }

    @Override
    public int count() {
        return 1;
    }

    @API
    @ThreadSafety.Many
    public boolean bufferCompatible(UBOBinding other) {
        if (other.size != size) {
            return false;
        }
        return other.members.equals(members);
    }

    @ThreadSafety.Many
    public void writeBindingInfo(VkDescriptorSetLayoutBinding.Buffer info) {
        info.binding(binding);
        info.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
        info.descriptorCount(1);
        info.stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        info.pImmutableSamplers(null);
    }

    public Staging createStaging() {
        return new Staging();
    }

    public class Staging implements Destroyable, UBOWriter {
        private final PointerWrapper stagingData = PointerWrapper.alloc(size);
        @Nullable
        private Buffer lastUploadBuffer;
        private boolean dirty = true;
        private long lastUploadFrame = -1;

        @Override
        public void destroy() {
            stagingData.free();
        }

        @ThreadSafety.Any
        public void writeAll(PointerWrapper newData) {
            dirty = true;
            newData.copyTo(stagingData);
        }

        @Override
        @ThreadSafety.Any
        public void write(UBOMember member, PointerWrapper newData) {
            dirty = true;
            newData.copyTo(stagingData, member.offset, member.size);
        }

        @ThreadSafety.Any
        public boolean isDirty() {
            final var currentFrame = device.currentFrameIndex();
            return dirty || lastUploadFrame != currentFrame || lastUploadBuffer == null;
        }

        @ThreadSafety.Any
        public Buffer upload() {
            if (!isDirty()) {
                assert lastUploadBuffer != null;
                return lastUploadBuffer;
            }
            dirty = false;
            lastUploadFrame = device.currentFrameIndex();
            lastUploadBuffer = new Buffer(stagingData);
            device.destroyEndOfFrame(lastUploadBuffer);
            return lastUploadBuffer;
        }

        public UBOBinding binding() {
            return UBOBinding.this;
        }

        public PointerWrapper stagingData() {
            return stagingData;
        }
    }

    public class Buffer implements Destroyable {

        public final VkBuffer buffer;

        Buffer(PointerWrapper data) {
            this(data, device.hostTransientMemoryPool(), device.deviceTransientMemoryPool);
        }

        Buffer(PointerWrapper data, VkMemoryPool.CPU stagingPool, VkMemoryPool gpuPool) {
            buffer = new VkBuffer(device, size, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, gpuPool);
            final var stagingBuffer = new VkBuffer(device, size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, stagingPool);
            device.destroyEndOfFrame(stagingBuffer);
            data.copyTo(stagingBuffer.allocation.cpu().hostPointer);
            
            device.createCommandEncoder().copyBufferToBuffer(stagingBuffer, buffer);
        }

        @Override
        public void destroy() {
            buffer.destroy();
        }

        public UBOBinding binding() {
            return UBOBinding.this;
        }
    }
}
