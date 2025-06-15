package graphics.cinnabar.lib.threading;

import graphics.cinnabar.api.threading.ISemaphore;
import graphics.cinnabar.api.threading.ThreadIndex;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class AtomicQueueSemaphore implements ISemaphore {
    
    private static final VarHandle VALUE_VAR_HANDLE;
    
    static {
        try {
            VALUE_VAR_HANDLE = MethodHandles.lookup().findVarHandle(AtomicQueueSemaphore.class, "value", long.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    
    // its used, indirectly, thx VarHandle
    @SuppressWarnings("unused")
    private long value;
    
    @Override
    public long value() {
        return (long) VALUE_VAR_HANDLE.getAcquire(this);
    }
    
    @Override
    public void wait(long value, long timeout) {
        if (value() >= value) {
            return;
        }
        final var threadIndex = ThreadIndex.currentThreadIndex();
        while (value() < value) {
            QueueSystem.onSemaphoreSpinWait(threadIndex);
        }
    }
    
    @Override
    public void signal(long value) {
        VALUE_VAR_HANDLE.setRelease(this, value);
        // wake workers, this may have unblocked a queue
        // TODO: is there a better way to handle this?
        QueueSystem.wakeWorkers(Integer.MAX_VALUE);
    }
}
