package net.roguelogix.phosphophyllite.threading;

public interface RingBuffer<T> {
    
    static <T> RingBuffer<T> create(Class<T> clazz) {
        return create(clazz, 0, true, true);
    }
    
    static <T> RingBuffer<T> create(Class<T> clazz, int minimumInitialCapacity) {
        return create(clazz, minimumInitialCapacity, true, true);
    }
    
    static <T> RingBuffer<T> create(Class<T> clazz, boolean multiProducer, boolean multiConsumer) {
        return create(clazz, 0, multiProducer, multiConsumer);
    }
    
    static <T> RingBuffer<T> create(Class<T> clazz, int minimumInitialCapacity, boolean multiProducer, boolean multiConsumer) {
        if (multiProducer) {
            if (multiConsumer) {
                return new ResizingRingBuffer.MultiProducerMultiConsumer<>(minimumInitialCapacity);
            } else {
                return new ResizingRingBuffer.MultiProducer<>(minimumInitialCapacity);
            }
        } else {
            if (multiConsumer) {
                return new ResizingRingBuffer.MultiConsumer<>(minimumInitialCapacity);
            } else {
                return new ResizingRingBuffer<>(minimumInitialCapacity);
            }
        }
    }
    
    @ThreadSafety.Any(lockGroups = "1")
    void put(T item);
    
    @ThreadSafety.Any(lockGroups = "2")
    T take();
    
    @ThreadSafety.Any(lockGroups = "2")
    T poll();
    
    interface MultiProducer<T> extends RingBuffer<T> {
        @Override
        @ThreadSafety.Many
        void put(T item);
    }
    
    interface MultiConsumer<T> extends RingBuffer<T> {
        @Override
        @ThreadSafety.Many
        T take();
        
        @Override
        @ThreadSafety.Many
        T poll();
    }
    
    interface MultiProducerMultiConsumer<T> extends MultiConsumer<T>, MultiProducer<T> {
    }
}
