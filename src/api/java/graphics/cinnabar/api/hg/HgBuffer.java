package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.annotations.Constant;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.hg.enums.HgFormat;
import graphics.cinnabar.api.memory.PointerWrapper;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.NonExtendable
@ThreadSafety.VulkanObjectHandle
public interface HgBuffer extends HgObject {
    @Constant
    @ThreadSafety.Many
    long size();
    
    @Constant
    @ThreadSafety.Many
    boolean deviceLocal();
    
    @Constant
    @ThreadSafety.Many
    boolean mappable();
    
    @ThreadSafety.Many
    @ThreadSafety.VulkanObjectHandle
    PointerWrapper map();
    
    @ThreadSafety.Many
    @ThreadSafety.VulkanObjectHandle
    void unmap();
    
    default Slice slice() {
        return new Slice(this, 0, size());
    }
    
    default Slice slice(long offset, long size) {
        return new Slice(this, offset, size);
    }
    
    default ImageSlice imageSlice(Slice slice, int width, int height) {
        return new ImageSlice(this, slice.offset, slice.size, width, height);
    }
    
    default ImageSlice imageSlice(long offset, long size, int width, int height) {
        return new ImageSlice(this, offset, size, width, height);
    }
    
    default View view(HgFormat format) {
        return view(format, 0, size());
    }
    
    View view(HgFormat format, long offset, long size);
    
    enum MemoryType {
        // mappable memory, always host memory
        MAPPABLE,
        // mappable memory, preferably on the device
        MAPPABLE_PREF_DEVICE,
        // memory that is on the device, mappable if possible
        DEVICE_PREF_MAPPABLE,
        // memory that is on the device, not mapped
        DEVICE,
        // less likely to fail allocation, may fall back to system memory
        AUTO_PREF_DEVICE
    }
    
    interface View extends HgObject {
    }
    
    record Slice(HgBuffer buffer, long offset, long size) {
        @ThreadSafety.Many
        @ThreadSafety.VulkanObjectHandle
        public PointerWrapper map() {
            return buffer.map().slice(offset, size);
        }
        
        @ThreadSafety.Many
        @ThreadSafety.VulkanObjectHandle
        public void unmap() {
            buffer.unmap();
        }
        
        public Slice slice(long offset, long size) {
            return new Slice(buffer, this.offset + offset, size);
        }
        
        public ImageSlice image(int width, int height) {
            return new ImageSlice(buffer, offset, size, width, height);
        }
    }
    
    record ImageSlice(HgBuffer buffer, long offset, long size, int width, int height) {
    }
}
