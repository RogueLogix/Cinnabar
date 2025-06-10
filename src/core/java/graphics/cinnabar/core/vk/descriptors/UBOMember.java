package graphics.cinnabar.core.vk.descriptors;

import graphics.cinnabar.api.memory.MagicMemorySizes;

public class UBOMember {
    
    public final String name;
    
    public final String type;
    public final long offset;
    public final long size;
    
    public UBOMember(String name, String type, long offset) {
        this(name, type, offset, MagicMemorySizes.sizeofGLSLType(type));
    }
    
    public UBOMember(String name, String type, long offset, long size) {
        this.name = name;
        this.type = type;
        this.offset = offset;
        this.size = size;
    }
    
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UBOMember otherMember)) {
            return false;
        }
        // name isn't considered
        return otherMember.offset == offset && otherMember.size == size && otherMember.type.equals(type);
    }
}
