package net.roguelogix.phosphophyllite.util;

public interface TriConsumer<K, V, S> {
    
    void accept(K k, V v, S s);
    
    interface WithException<K, V, S, E extends Exception> {
        void accept(K k, V v, S s) throws E;
    }
}