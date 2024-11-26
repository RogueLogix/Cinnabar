package graphics.cinnabar.internal.extensions.blaze3d.vertex;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.extensions.minecraft.renderer.CinnabarShaderInstance;
import graphics.cinnabar.internal.statemachine.CinnabarBlendState;
import graphics.cinnabar.internal.statemachine.CinnabarFramebufferState;
import graphics.cinnabar.internal.statemachine.CinnabarGeneralState;
import graphics.cinnabar.internal.util.CinnabarSharedIndexBuffers;
import graphics.cinnabar.internal.vulkan.memory.VulkanMemoryAllocation;
import graphics.cinnabar.internal.vulkan.util.LiveHandles;
import graphics.cinnabar.internal.vulkan.util.VulkanQueueHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCString;
import org.lwjgl.vulkan.*;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.vulkan.EXTExtendedDynamicState2.vkCmdSetLogicOpEXT;
import static org.lwjgl.vulkan.EXTExtendedDynamicState3.vkCmdSetColorWriteMaskEXT;
import static org.lwjgl.vulkan.EXTShaderObject.vkCmdSetLogicOpEnableEXT;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

@NonnullDefault
public class CinnabarVertexBuffer extends VertexBuffer {
    private final Exception allocationPoint = new RuntimeException();
    
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
        MeshData.DrawState drawState = meshData.drawState();
        
        this.indexCount = drawState.indexCount();
        this.indexType = drawState.indexType();
        this.mode = drawState.mode();
        
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
                
                final var memoryRequirements = VkMemoryRequirements2.calloc(stack).sType$Default();
                final var bufferRequirements = VkDeviceBufferMemoryRequirements.calloc(stack).sType$Default();
                bufferRequirements.pCreateInfo(createInfo);
                vkGetDeviceBufferMemoryRequirements(device, bufferRequirements, memoryRequirements);
                bufferAllocation = CinnabarRenderer.GPUMemoryAllocator.alloc(memoryRequirements);
                
                // size up the actual buffer to match the allocation's size
                createInfo.size(bufferAllocation.range().size());
                vkGetDeviceBufferMemoryRequirements(device, bufferRequirements, memoryRequirements);
                if (memoryRequirements.memoryRequirements().size() > bufferAllocation.range().size()) {
                    throw new IllegalStateException();
                }
                
                if (bufferHandle != VK_NULL_HANDLE) {
                    throw new IllegalStateException("About to leak VkBuffer!");
                }
                
                vkCreateBuffer(device, createInfo, null, longPtr);
                bufferHandle = longPtr.get(0);
                
                vkBindBufferMemory(device, bufferHandle, bufferAllocation.memoryHandle(), bufferAllocation.range().offset());
                
                LiveHandles.create(bufferHandle, allocationPoint);
            }
        }
        assert bufferAllocation != null;
        assert bufferHandle != VK_NULL_HANDLE;
        
        final var queue = CinnabarRenderer.queueHelper;
        final var commandBuffer = queue.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS);
        
        final var stagingBuffer = queue.cpuBufferAllocatorForSubmit().alloc(totalBytesNeeded);
        queue.destroyEndOfSubmit(stagingBuffer);
        final var hostPtr = stagingBuffer.hostPtr;
        
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
            final var depInfo = VkDependencyInfo.calloc(stack).sType$Default();
            final var bufferDepInfo = VkBufferMemoryBarrier2.calloc(1, stack).sType$Default();
            depInfo.pBufferMemoryBarriers(bufferDepInfo);
            bufferDepInfo.buffer(bufferHandle);
            bufferDepInfo.size(totalBytesNeeded);
            bufferDepInfo.offset(0);
            
            bufferDepInfo.srcStageMask(VK_PIPELINE_STAGE_VERTEX_INPUT_BIT);
            bufferDepInfo.srcAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT);
            bufferDepInfo.dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT);
            bufferDepInfo.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier2(commandBuffer, depInfo);
            
            final var copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.srcOffset(0);
            copyRegion.dstOffset(0);
            copyRegion.size(totalBytesNeeded);
            copyRegion.limit(1);
            vkCmdCopyBuffer(commandBuffer, stagingBuffer.handle, bufferHandle, copyRegion);
            
            bufferDepInfo.srcStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT);
            bufferDepInfo.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            bufferDepInfo.dstStageMask(VK_PIPELINE_STAGE_VERTEX_INPUT_BIT);
            bufferDepInfo.dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT);
            vkCmdPipelineBarrier2(commandBuffer, depInfo);
        }
        markUsed(VK_PIPELINE_STAGE_TRANSFER_BIT);
        
        meshData.close();
    }
    
    public void markUsed(int stageMask) {
        final var queue = CinnabarRenderer.queueHelper;
//        destroyWaitSemaphoreValue = queue.nextSignalImplicit(VulkanQueueHelper.QueueType.MAIN_GRAPHICS, stageMask);
    }
    
    public void gpuDestroy() {
        final var queue = CinnabarRenderer.queueHelper;
        
        LiveHandles.destroy(bufferHandle);
        
        final var device = CinnabarRenderer.device();
        final var oldHandle = bufferHandle;
        queue.destroyEndOfSubmit(() -> {
            vkDestroyBuffer(device, oldHandle, null);
        });
        bufferHandle = VK_NULL_HANDLE;
        if (bufferAllocation != null) {
            queue.destroyEndOfSubmit(bufferAllocation);
            bufferAllocation = null;
        }
        indicesOffset = -1;
    }
    
    @Override
    public void close() {
        gpuDestroy();
    }
    
    @Override
    public void draw() {
        draw(CinnabarRenderer.queueHelper.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS));
    }
    
    protected void _drawWithShader(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, ShaderInstance shader) {
        final var cinnabarShader = (CinnabarShaderInstance) shader;
        shader.setDefaultUniforms(this.mode, modelViewMatrix, projectionMatrix, Minecraft.getInstance().getWindow());
        final var commandBuffer = CinnabarRenderer.queueHelper.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS);
        cinnabarShader.apply(commandBuffer);
        draw(commandBuffer);
    }
    
    private void draw(VkCommandBuffer commandBuffer) {
        vkCmdSetPrimitiveTopology(commandBuffer, vkTopology(mode.asGLMode));
        
        vkCmdSetViewport(commandBuffer, 0, CinnabarFramebufferState.viewport());
        vkCmdSetScissor(commandBuffer, 0, CinnabarFramebufferState.scissor());
        
        vkCmdSetCullMode(commandBuffer, CinnabarGeneralState.cull ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE);
        vkCmdSetDepthTestEnable(commandBuffer, CinnabarGeneralState.depthTest);
        vkCmdSetDepthWriteEnable(commandBuffer, CinnabarGeneralState.depthWrite);
        vkCmdSetDepthCompareOp(commandBuffer, CinnabarGeneralState.depthFunc);
        CinnabarBlendState.apply(commandBuffer);
        
        // TODO: track/set logic op
        vkCmdSetLogicOpEnableEXT(commandBuffer, false);
        vkCmdSetLogicOpEXT(commandBuffer, VK_LOGIC_OP_NO_OP);
        
        
        try (final var stack = MemoryStack.stackPush()) {
            vkCmdSetColorWriteMaskEXT(commandBuffer, 0, stack.ints(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT));
            
            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(bufferHandle), stack.longs(0));
            if (indicesOffset == -1) {
                final var sharedBuffer = CinnabarSharedIndexBuffers.getIndexBufferForMode(mode, indexCount);
                vkCmdBindIndexBuffer(commandBuffer, sharedBuffer.handle, 0, VK_INDEX_TYPE_UINT32);
            } else {
                vkCmdBindIndexBuffer(commandBuffer, bufferHandle, indicesOffset, vkIndexType(indexType));
            }
        }
        
        CinnabarFramebufferState.resumeRendering(commandBuffer);
        vkCmdDrawIndexed(commandBuffer, indexCount, 1, 0, 0, 0);
        CinnabarFramebufferState.suspendRendering(commandBuffer);
    }
    
    private static int vkTopology(int glMode) {
        return switch (glMode) {
            case GL_TRIANGLES -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case GL_TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case GL_TRIANGLE_FAN -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            default -> throw new IllegalStateException("Unsupported topology");
        };
    }
    
    private static int vkIndexType(VertexFormat.IndexType type) {
        return switch (type) {
            case SHORT -> VK_INDEX_TYPE_UINT16;
            case INT -> VK_INDEX_TYPE_UINT32;
        };
    }
}
