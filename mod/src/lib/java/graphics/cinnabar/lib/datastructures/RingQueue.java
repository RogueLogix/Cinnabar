package graphics.cinnabar.lib.datastructures;

import graphics.cinnabar.api.annotations.ThreadSafety;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class RingQueue<T> {
    
    private static final VarHandle LONG_ARRAY_VAR_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle OBJECT_ARRAY_VAR_HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);
    
    private final int RING_SIZE_BITS;
    private final int RING_SIZE;
    private final long RING_INDEX_MASK;
    
    private final Object[] ringObjects;
    private final long[] ringVersions;
    
    private static final int PUSH_ARRAY_INDEX = 0;
    private static final int POP_ARRAY_INDEX = 8;
    
    private final long[] pushPopIndices = new long[16];
    
    public RingQueue(int ringBits) {
        RING_SIZE_BITS = ringBits;
        RING_SIZE = 1 << ringBits;
        RING_INDEX_MASK = RING_SIZE - 1;
        ringObjects = new Object[RING_SIZE];
        ringVersions = new long[RING_SIZE];
    }
    
    @ThreadSafety.Many
    public void ForceEnqueue(@Nullable T data) {
        final var index = (long) LONG_ARRAY_VAR_HANDLE.getAndAddAcquire(pushPopIndices, PUSH_ARRAY_INDEX, 1);
        final var ringIndex = (int)(index & RING_INDEX_MASK);
        final var expectedVersion = (index >> RING_SIZE_BITS) * 2;
        
        while ((long) LONG_ARRAY_VAR_HANDLE.get(ringVersions, ringIndex) != expectedVersion) {
            VarHandle.acquireFence();
            Thread.onSpinWait();
        }
        VarHandle.releaseFence();
        OBJECT_ARRAY_VAR_HANDLE.setRelease(ringObjects, ringIndex, data);
        LONG_ARRAY_VAR_HANDLE.setRelease(ringVersions, ringIndex, expectedVersion + 1);
    }
    
    @Nullable
    @ThreadSafety.Many
    public T Dequeue() {
        VarHandle.acquireFence();
        final var index = (long) LONG_ARRAY_VAR_HANDLE.get(pushPopIndices, POP_ARRAY_INDEX);
        final var ringIndex = (int)(index & RING_INDEX_MASK);
        final var expectedVersion = (index >> RING_SIZE_BITS) * 2 + 1;
        
        VarHandle.acquireFence();
        final var elementVersion = (long) LONG_ARRAY_VAR_HANDLE.get(ringVersions, ringIndex);
        if (elementVersion != expectedVersion) {
            Thread.onSpinWait();
            return null;
        }
        VarHandle.acquireFence();
        if (index != (long) LONG_ARRAY_VAR_HANDLE.compareAndExchange(pushPopIndices, POP_ARRAY_INDEX, index, index + 1)) {
            Thread.onSpinWait();
            return null;
        }
        VarHandle.acquireFence();
        final var data = OBJECT_ARRAY_VAR_HANDLE.get(ringObjects, ringIndex);
        VarHandle.releaseFence();
        LONG_ARRAY_VAR_HANDLE.set(ringVersions, ringIndex, expectedVersion + 1);
        //noinspection unchecked
        return (T)data;
    }
}
