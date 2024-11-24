package graphics.cinnabar.internal.util;

import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.vulkan.memory.CPUMemoryVkBuffer;
import graphics.cinnabar.internal.vulkan.memory.VulkanBuffer;
import graphics.cinnabar.internal.vulkan.util.VulkanQueueHelper;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NonnullDefault;
import org.lwjgl.vulkan.VkBufferCopy;

import static org.lwjgl.vulkan.VK10.*;

@NonnullDefault
public class CinnabarSharedIndexBuffers {
    
    private static VulkanBuffer sequentialIndices = new VulkanBuffer(1, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
    private static VulkanBuffer quadIndices = new VulkanBuffer(1, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
    private static VulkanBuffer lineIndices = new VulkanBuffer(1, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
    
    private static VulkanBuffer upload(CPUMemoryVkBuffer tempBuffer) {
        final var newBuffer = new VulkanBuffer(tempBuffer.hostPtr().size(), VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        final var queue = CinnabarRenderer.queueHelper;
        final var commandBuffer = queue.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS);
        try (final var stack = MemoryStack.stackPush()) {
            final var copy = VkBufferCopy.calloc(1, stack);
            copy.size(newBuffer.size);
            vkCmdCopyBuffer(commandBuffer, tempBuffer.bufferHandle(), newBuffer.handle, copy);
            queue.queueBarrier(VulkanQueueHelper.QueueType.MAIN_GRAPHICS, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT, VK_ACCESS_INDEX_READ_BIT);
        }
        return newBuffer;
    }
    
    public static VulkanBuffer getIndexBufferForMode(VertexFormat.Mode mode, int indexCount) {
        return switch (mode) {
            case QUADS -> {
                if (quadIndices.size < (indexCount * 4L)) {
                    final int newSize = Math.max(indexCount * 4, (int) quadIndices.size * 2);
                    final int newIndexCount = newSize / 4;
                    final var tempBuffer = CPUMemoryVkBuffer.alloc(newSize);
                    CinnabarRenderer.queueDestroyEndOfGPUSubmit(tempBuffer);
                    final var intBuff = tempBuffer.hostPtr();
                    final var polyCount = newIndexCount / 6;
                    for (int poly = 0; poly < polyCount; poly++) {
                        int baseIndex = poly * 6;
                        int baseVertex = poly * 4;
                        intBuff.putIntIdx(baseIndex, baseVertex);
                        intBuff.putIntIdx(baseIndex + 1, baseVertex + 1);
                        intBuff.putIntIdx(baseIndex + 2, baseVertex + 2);
                        intBuff.putIntIdx(baseIndex + 3, baseVertex + 2);
                        intBuff.putIntIdx(baseIndex + 4, baseVertex + 3);
                        intBuff.putIntIdx(baseIndex + 5, baseVertex);
                    }
                    CinnabarRenderer.queueDestroyEndOfGPUSubmit(quadIndices);
                    quadIndices = upload(tempBuffer);
                    
                }
                yield quadIndices;
            }
            case LINES -> {
                if (lineIndices.size < (indexCount * 4L)) {
                    final int newSize = indexCount * 4;
                    final var tempBuffer = CPUMemoryVkBuffer.alloc(newSize);
                    CinnabarRenderer.queueDestroyEndOfGPUSubmit(tempBuffer);
                    final var intBuff = tempBuffer.hostPtr();
                    final var polyCount = indexCount / 6;
                    for (int poly = 0; poly < polyCount; poly++) {
                        int baseIndex = poly * 6;
                        int baseVertex = poly * 4;
                        intBuff.putIntIdx(baseIndex, baseVertex);
                        intBuff.putIntIdx(baseIndex + 1, baseVertex + 1);
                        intBuff.putIntIdx(baseIndex + 2, baseVertex + 2);
                        intBuff.putIntIdx(baseIndex + 3, baseVertex + 3);
                        intBuff.putIntIdx(baseIndex + 4, baseVertex + 2);
                        intBuff.putIntIdx(baseIndex + 5, baseVertex + 1);
                    }
                    CinnabarRenderer.queueDestroyEndOfGPUSubmit(lineIndices);
                    lineIndices = upload(tempBuffer);
                }
                yield lineIndices;
            }
            default -> {
                if (sequentialIndices.size < indexCount * 4L) {
                    final int newSize = indexCount * 4;
                    final var tempBuffer = CPUMemoryVkBuffer.alloc(newSize);
                    CinnabarRenderer.queueDestroyEndOfGPUSubmit(tempBuffer);
                    final var intBuff = tempBuffer.hostPtr();
                    for (int i = 0; i < indexCount; i++) {
                        intBuff.putIntIdx(i, i);
                    }
                    CinnabarRenderer.queueDestroyEndOfGPUSubmit(sequentialIndices);
                    sequentialIndices = upload(tempBuffer);
                }
                yield sequentialIndices;
            }
        };
    }
    
    public static void destroy() {
        quadIndices.destroy();
        lineIndices.destroy();
        sequentialIndices.destroy();
    }
}
