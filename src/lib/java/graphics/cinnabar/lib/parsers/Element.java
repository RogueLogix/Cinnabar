package graphics.cinnabar.lib.parsers;

import org.jetbrains.annotations.Nullable;

// TODO: 8/25/22 element tree safeties would be great, as is, these things are fragile AF
public class Element {
    
    public Element(Type type, @Nullable String comment, @Nullable String name, Object value) {
        this.type = type;
        this.comment = comment;
        this.name = name;
        this.value = value;
        subArray = null;
    }
    
    public Element(Type type, @Nullable String comment, @Nullable String name, Element[] value) {
        this.type = type;
        this.comment = comment;
        this.name = name;
        this.value = null;
        subArray = value;
    }
    
    public enum Type {
        String,
        Number,
        Boolean,
        Array,
        Map
    }
    
    public final Type type;
    
    @Nullable
    public final String comment;
    
    @Nullable
    public final String name;
    
    @Nullable
    public final Object value;
    @Nullable
    public final Element[] subArray;
    
    public String asString() {
        assert value != null;
        return value.toString();
    }
    
    public boolean asBool() {
        assert value != null;
        return (boolean) value;
    }
    
    public long asLong() {
        assert value != null;
        return ((Number) value).longValue();
    }
    
    public double asDouble() {
        assert value != null;
        return ((Number) value).doubleValue();
    }
}
