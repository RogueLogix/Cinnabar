package graphics.cinnabar.core.b3d.buffers;

import com.mojang.blaze3d.buffers.Std140Builder;
import graphics.cinnabar.api.annotations.RewriteHierarchy;

import java.nio.ByteBuffer;

@RewriteHierarchy
public class Std140ArrayBuilder extends Std140Builder {
    
    private int firstElementAlignment = -1;
    
    public Std140ArrayBuilder(ByteBuffer buffer) {
        super(buffer);
    }
    
    @Override
    public ByteBuffer get() {
        arrayAlign();
        return super.get();
    }
    
    @Override
    public Std140Builder align(int alignment) {
        if (firstElementAlignment == -1) {
            firstElementAlignment = alignment;
        }
        return super.align(alignment);
    }
    
    public Std140ArrayBuilder arrayAlign() {
        align(firstElementAlignment);
        return this;
    }
}
