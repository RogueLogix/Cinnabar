package graphics.cinnabar.internal.util;

import graphics.cinnabar.api.annotations.NotNullDefault;
import graphics.cinnabar.lib.util.Pair;

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
        if (size > this.size) {
            throw new IllegalArgumentException("Cannot split allocation to larger size");
        }
        if (size == this.size) {
            return new Pair<>(this, null);
        }
        return new Pair<>(new MemoryRange(this.offset, size), new MemoryRange(this.offset + size, this.size - size));
    }
}
