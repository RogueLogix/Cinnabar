package graphics.cinnabar.internal.extensions.blaze3d.vertex;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.vulkan.memory.CPUMemoryVkBuffer;
import graphics.cinnabar.internal.vulkan.memory.VulkanMemoryAllocation;
import graphics.cinnabar.internal.vulkan.util.VulkanQueueHelper;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCString;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import static org.lwjgl.vulkan.VK10.*;

@NonnullDefault
public class CinnabarVertexBuffer extends VertexBuffer {
    private static final int PENDING_STAGING_BUFFERS = CinnabarRenderer.SUBMITS_IN_FLIGHT;
    
    private long destroyWaitSemaphoreValue = 0;
    
    private final CPUMemoryVkBuffer[] stagingBuffers = new CPUMemoryVkBuffer[PENDING_STAGING_BUFFERS];
    private final long[] stagingBufferSemaphoreValues = new long[PENDING_STAGING_BUFFERS];
    
    private long bufferHandle = VK_NULL_HANDLE;
    @Nullable
    private VulkanMemoryAllocation bufferAllocation;
    private long indicesOffset = -1;
    
    public CinnabarVertexBuffer(Usage usage) {
        super(usage);
    }
    
    public void bind() {
    }
    
    @Override
    public void upload(MeshData meshData) {
        final var vertexData = meshData.vertexBuffer();
        @Nullable final var indexData = meshData.indexBuffer();
        final var totalBytesNeeded = vertexData.limit() + (indexData != null ? indexData.limit() : 0);
        
        if (bufferAllocation != null && totalBytesNeeded > bufferAllocation.range().size()) {
            // must reallocate GPU buffer, so it needs to be destroyed first
            gpuDestroy();
        }
        
        if (bufferAllocation == null) {
            final var device = CinnabarRenderer.device();
            // no GPU buffer allocated
            try (final var stack = MemoryStack.stackPush()) {
                final var longPtr = stack.callocLong(1);
                final var createInfo = VkBufferCreateInfo.calloc(stack);
                createInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
                createInfo.pNext(0);
                createInfo.flags(0);
                createInfo.size(totalBytesNeeded);
                createInfo.usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                createInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                createInfo.pQueueFamilyIndices(null);
                
                vkCreateBuffer(device, createInfo, null, longPtr);
                bufferHandle = longPtr.get(0);
                
                final var memoryRequirements = VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(device, bufferHandle, memoryRequirements);
                bufferAllocation = CinnabarRenderer.GPUMemoryAllocator.alloc(memoryRequirements);
                
                vkBindBufferMemory(device, bufferHandle, bufferAllocation.memoryHandle(), bufferAllocation.range().offset());
            }
        }
        assert bufferAllocation != null;
        assert bufferHandle != VK_NULL_HANDLE;
        
        final var queue = CinnabarRenderer.queueHelper;
        final var commandBuffer = queue.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS);
        int stagingBufferIndex = 0;
        for (int i = 0; i < stagingBufferSemaphoreValues.length; i++) {
            if (stagingBufferSemaphoreValues[i] < stagingBufferSemaphoreValues[stagingBufferIndex]) {
                stagingBufferIndex = i;
            }
        }
        queue.clientSubmitAndWaitImplicit(VulkanQueueHelper.QueueType.MAIN_GRAPHICS, stagingBufferSemaphoreValues[stagingBufferIndex]);
        if (stagingBuffers[stagingBufferIndex] != null) {
            stagingBuffers[stagingBufferIndex].destroy();
            stagingBuffers[stagingBufferIndex] = null;
        }
        
        
        final var stagingBuffer = stagingBuffers[stagingBufferIndex] = CPUMemoryVkBuffer.alloc(totalBytesNeeded);
        final var hostPtr = stagingBuffer.hostPtr();
        
        // TODO: if buffer ownership can be transferred, there is no need for this memcpy
        final var vertexDataPtr = MemoryUtil.memAddress(vertexData);
        LibCString.nmemcpy(hostPtr.pointer(), vertexDataPtr, vertexData.limit());
        
        indicesOffset = -1;
        if (indexData != null) {
            final var offset = vertexData.limit();
            final var indexDataPtr = MemoryUtil.memAddress(indexData);
            LibCString.nmemcpy(hostPtr.pointer() + offset, indexDataPtr, indexData.limit());
            indicesOffset = offset;
        }
        
        try (final var stack = MemoryStack.stackPush()) {
            final var copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.srcOffset(0);
            copyRegion.dstOffset(0);
            copyRegion.size(totalBytesNeeded);
            copyRegion.limit(1);
            vkCmdCopyBuffer(commandBuffer, stagingBuffers[stagingBufferIndex].bufferHandle(), bufferHandle, copyRegion);
        }
        stagingBufferSemaphoreValues[stagingBufferIndex] = queue.nextSignalImplicit(VulkanQueueHelper.QueueType.MAIN_GRAPHICS, VK_PIPELINE_STAGE_TRANSFER_BIT);
        markUsed(VK_PIPELINE_STAGE_TRANSFER_BIT);
    }
    
    public void markUsed(int stageMask) {
        final var queue = CinnabarRenderer.queueHelper;
        destroyWaitSemaphoreValue = queue.nextSignalImplicit(VulkanQueueHelper.QueueType.MAIN_GRAPHICS, stageMask);
    }
    
    public void gpuDestroy() {
        final var queue = CinnabarRenderer.queueHelper;
        
        // if in use, need to wait for it to not be in use
        if (destroyWaitSemaphoreValue != 0) {
            queue.clientSubmitAndWaitImplicit(VulkanQueueHelper.QueueType.MAIN_GRAPHICS, destroyWaitSemaphoreValue);
        }
        final var device = CinnabarRenderer.device();
        vkDestroyBuffer(device, bufferHandle, null);
        bufferHandle = VK_NULL_HANDLE;
        if (bufferAllocation != null) {
            CinnabarRenderer.GPUMemoryAllocator.free(bufferAllocation);
            bufferAllocation = null;
        }
        indicesOffset = -1;
        
    }
    
    @Override
    public void close() {
        gpuDestroy();
        for (int i = 0; i < stagingBufferSemaphoreValues.length; i++) {
            assert stagingBufferSemaphoreValues[i] <= destroyWaitSemaphoreValue;
            stagingBuffers[i].destroy();
        }
    }
}
