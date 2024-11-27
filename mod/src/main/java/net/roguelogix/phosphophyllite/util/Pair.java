package net.roguelogix.phosphophyllite.util;


import java.util.Map;

public record Pair<F, S>(F first, S second) {
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
