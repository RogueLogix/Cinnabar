package graphics.cinnabar.api.memory;

import graphics.cinnabar.api.CinnabarAPI;
import graphics.cinnabar.api.annotations.NotNullDefault;
import graphics.cinnabar.api.util.Pair;

@NotNullDefault
public record MemoryRange(long offset, long size) implements Comparable<MemoryRange> {
    public MemoryRange(MemoryRange a, MemoryRange b) {
        this(a.offset, a.size + b.size);
        if (a.offset + a.size != b.offset) {
            throw new IllegalStateException("Cannot combine non-consecutive alloc infos");
        }
    }
    
    @Override
    public int compareTo(MemoryRange info) {
        return Long.compare(offset, info.offset);
    }
    
    public Pair<MemoryRange, MemoryRange> split(long size) {
        if (CinnabarAPI.DEBUG_MODE) {
            if (size > this.size) {
                throw new IllegalArgumentException("Cannot split allocation to larger size");
            }
        }
        if (size == this.size) {
            return new Pair<>(this, null);
        }
        return new Pair<>(new MemoryRange(this.offset, size), new MemoryRange(this.offset + size, this.size - size));
    }
    
    public MemoryRange slice(long offset, long size) {
        if (CinnabarAPI.DEBUG_MODE) {
            if (size <= 0) {
                throw new IllegalArgumentException("Attempt to slice memory range to invalid size: " + size);
            }
            if (offset < 0) {
                throw new IllegalArgumentException("Attempt to slice memory range to invalid offset: " + offset);
            }
            if (offset >= this.size) {
                throw new IllegalArgumentException("Attempt to slice memory range to offset past end. offset: " + offset + ", source size: " + this.size);
            }
            if ((offset + size) > this.size) {
                throw new IllegalArgumentException("Attempt to slice memory range to offset and size past end. offset: " + offset + ", size: " + size + ", source size: " + this.size);
            }
        }
        return new MemoryRange(this.offset + offset, size);
    }
}
