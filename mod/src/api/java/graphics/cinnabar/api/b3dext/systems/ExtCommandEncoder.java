package graphics.cinnabar.api.b3dext.systems;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

public interface ExtCommandEncoder extends CommandEncoder {
    
    record BufferCopy(long srcOffset, long dstOffset, long size) {
        public BufferCopy(long size) {
            this(0, 0, size);
        }
        
        public BufferCopy(long srcOffset, long dstOffset) {
            // special value to mean the remaining slice, VK_WHOLE_SIZE
            this(srcOffset, dstOffset, -1);
        }
        
        public BufferCopy() {
            this(0, 0);
        }
    }
    
    /**
     * @param copies: no ranges is equivalent to passing a single BufferCopy() 
     */
    void copyBufferToBuffer(GpuBufferSlice src, GpuBufferSlice dst, BufferCopy... copies);
    
    default void copyBufferToBuffer(GpuBufferSlice src, GpuBufferSlice dst, BufferCopy copy) {
        copyBufferToBuffer(src, dst, new BufferCopy[]{copy});
    }
    
    default void copyBufferToBuffer(GpuBufferSlice src, GpuBufferSlice dst, long srcOffset, long dstOffset, long size) {
        copyBufferToBuffer(src, dst, new BufferCopy(srcOffset, dstOffset, size));
    }
    
    default void copyBufferToBuffer(GpuBufferSlice src, GpuBufferSlice dst, long size) {
        copyBufferToBuffer(src, dst, new BufferCopy(size));
    }
    
    default void copyBufferToBuffer(GpuBuffer src, GpuBuffer dst, BufferCopy... copies) {
        copyBufferToBuffer(src.slice(), dst.slice(), copies);
    }
    
    default void copyBufferToBuffer(GpuBuffer src, GpuBuffer dst, BufferCopy copy) {
        copyBufferToBuffer(src, dst, new BufferCopy[]{copy});
    }
    
    default void copyBufferToBuffer(GpuBuffer src, GpuBuffer dst, long srcOffset, long dstOffset, long size) {
        copyBufferToBuffer(src, dst, new BufferCopy(srcOffset, dstOffset, size));
    }
    
    default void copyBufferToBuffer(GpuBuffer src, GpuBuffer dst, long size) {
        copyBufferToBuffer(src, dst, new BufferCopy(size));
    }
    
    // ---------- Overrides for return type, function unmodified ----------
    
    ExtRenderPass createRenderPass(Supplier<String> debugGroup, GpuTextureView colorAttachment, OptionalInt colorClear);
    
    ExtRenderPass createRenderPass(Supplier<String> debugGroup, GpuTextureView colorAttachment, OptionalInt colorClear, @Nullable GpuTextureView depthAttachment, OptionalDouble depthClear);
}
