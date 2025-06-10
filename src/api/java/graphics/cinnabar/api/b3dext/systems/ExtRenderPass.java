package graphics.cinnabar.api.b3dext.systems;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import graphics.cinnabar.api.memory.MagicMemorySizes;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

import java.nio.ByteBuffer;
import java.util.List;

public interface ExtRenderPass extends RenderPass {
    
    void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);
    
    /**
     * @link <a href="https://registry.khronos.org/OpenGL-Refpages/gl4/html/glMultiDrawArraysIndirect.xhtml">glMultiDrawArraysIndirect</a>
     * @see org.lwjgl.vulkan.VkDrawIndirectCommand
     */
    record DrawCommand(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        public void put(ByteBuffer buffer, long offset) {
            get(MemoryUtil.memAddress(buffer) + offset);
        }
        
        public void put(Pointer ptr) {
            get(ptr.address());
        }
        
        public void get(long addr) {
            MemoryUtil.memPutInt(addr, vertexCount);
            addr += MagicMemorySizes.INT_BYTE_SIZE;
            MemoryUtil.memPutInt(addr, instanceCount);
            addr += MagicMemorySizes.INT_BYTE_SIZE;
            MemoryUtil.memPutInt(addr, firstVertex);
            addr += MagicMemorySizes.INT_BYTE_SIZE;
            MemoryUtil.memPutInt(addr, firstInstance);
        }
    }
    
    default void multiDraw(List<DrawCommand> draws) {
        for (DrawCommand draw : draws) {
            draw(draw.vertexCount, draw.instanceCount, draw.firstVertex, draw.firstInstance);
        }
    }
    
    void drawIndirect(GpuBufferSlice buffer);
    
    default void multiDrawIndirect(GpuBufferSlice buffer, int stride) {
        if (stride == 0) {
            stride = VkDrawIndirectCommand.SIZEOF;
        }
        final var drawCount = buffer.length() / stride;
        for (int i = 0; i < drawCount; i++) {
            drawIndirect(buffer.slice(i * stride, VkDrawIndirectCommand.SIZEOF));
        }
    }
    
    default void multiDrawIndirect(GpuBufferSlice buffer) {
        multiDrawIndirect(buffer, 0);
    }
    
    void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance);
    
    /**
     * <a href="https://registry.khronos.org/OpenGL-Refpages/gl4/html/glMultiDrawElementsIndirect.xhtml">glMultiDrawElementsIndirect</a>
     *
     * @see org.lwjgl.vulkan.VkDrawIndexedIndirectCommand
     */
    record DrawIndexedCommand(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        public void put(ByteBuffer buffer, long offset) {
            put(MemoryUtil.memAddress(buffer) + offset);
        }
        
        public void put(Pointer ptr) {
            put(ptr.address());
        }
        
        public void put(long addr) {
            MemoryUtil.memPutInt(addr, indexCount);
            addr += MagicMemorySizes.INT_BYTE_SIZE;
            MemoryUtil.memPutInt(addr, instanceCount);
            addr += MagicMemorySizes.INT_BYTE_SIZE;
            MemoryUtil.memPutInt(addr, firstIndex);
            addr += MagicMemorySizes.INT_BYTE_SIZE;
            MemoryUtil.memPutInt(addr, vertexOffset);
            addr += MagicMemorySizes.INT_BYTE_SIZE;
            MemoryUtil.memPutInt(addr, firstInstance);
        }
    }
    
    default void multiDrawIndexed(List<DrawIndexedCommand> draws) {
        for (DrawIndexedCommand draw : draws) {
            drawIndexed(draw.indexCount, draw.instanceCount, draw.firstIndex, draw.vertexOffset, draw.firstInstance);
        }
    }
    
    void drawIndexedIndirect(GpuBufferSlice buffer);
    
    default void multiDrawIndexedIndirect(GpuBufferSlice buffer, int stride) {
        if (stride == 0) {
            stride = VkDrawIndexedIndirectCommand.SIZEOF;
        }
        final var drawCount = buffer.length() / stride;
        for (int i = 0; i < drawCount; i++) {
            drawIndexedIndirect(buffer.slice(i * stride, VkDrawIndexedIndirectCommand.SIZEOF));
        }
    }
    
    default void multiDrawIndexedIndirect(GpuBufferSlice buffer) {
        multiDrawIndexedIndirect(buffer, 0);
    }
    
    // ---------- Vanilla RenderPass ----------
    
    @Override
    default void draw(int firstVertex, int vertexCount) {
        draw(vertexCount, 1, firstVertex, 0);
    }
}
