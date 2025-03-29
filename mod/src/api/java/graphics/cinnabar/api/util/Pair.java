package graphics.cinnabar.api.util;


import org.jetbrains.annotations.UnknownNullability;

import java.util.Map;

public record Pair<F, S>(@UnknownNullability F first, @UnknownNullability S second) {
    public Pair(com.mojang.datafixers.util.Pair<F, S> other){
        this(other.getFirst(), other.getSecond());
    }
    
    public Pair(it.unimi.dsi.fastutil.Pair<F, S> other){
        this(other.left(), other.right());
    }
    
    public Pair(Map.Entry<F, S> other){
        this(other.getKey(), other.getValue());
    }
    
    public F left() {
        return first;
    }
    
    public S right() {
        return second;
    }
}
