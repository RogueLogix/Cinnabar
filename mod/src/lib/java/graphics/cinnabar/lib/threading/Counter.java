package graphics.cinnabar.lib.threading;

import graphics.cinnabar.api.threading.IWorkQueue;
import graphics.cinnabar.api.threading.ThreadIndex;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class Counter implements IWorkQueue.ICounter {
    
    private final WorkQueue queue;
    
    private final AtomicLong value = new AtomicLong();
    private final AtomicLong maximumCallbackCount = new AtomicLong();
    private final AtomicLong callbackCount = new AtomicLong();
    private IWorkQueue.Work callback;
    private boolean enqueuingItems = false;
    
    public Counter(WorkQueue queue) {
        this.queue = queue;
    }
    
    
    @Override
    public void setCallback(IWorkQueue.Work callback) {
        this.callback = callback;
    }
    
    public void beginEnqueuing() {
        enqueuingItems = true;
        if (callbackCount.get() != maximumCallbackCount.get()) {
            throw new IllegalStateException("Cannot begin enqueuing items until counter has been completed");
        }
    }
    
    public void endEnqueuing() {
        if (!enqueuingItems) {
            return;
        }
        queue.wakeThreads(true);
        final var expectedCallCount = maximumCallbackCount.getAndIncrement();
        enqueuingItems = false;
        if (value.decrementAndGet() == 0) {
            // if we hit zero, attempt to call the callback
            if (callbackCount.compareAndExchange(expectedCallCount, expectedCallCount + 1) == expectedCallCount) {
                // jobs finished before pushing completed, call callback here
                callback.accept(Objects.requireNonNull(ThreadIndex.currentThreadIndex()));
            }
        }
    }
    
    private void increment() {
        if (!enqueuingItems) {
            throw new IllegalStateException("Must be enqueuing items to increment counter");
        }
        if (value.incrementAndGet() <= 0) {
            throw new IllegalStateException("Counter over decremented");
        }
    }
    
    @Override
    public void decrement() {
        if (value.decrementAndGet() == 0) {
            // if we hit zero, attempt to call the callback
            // if the atomic compare exchange fails, then items are still being added and will catch if all items completed before adding finished
            final var expectedCallCount = maximumCallbackCount.get() - 1;
            if (callbackCount.compareAndExchange(expectedCallCount, expectedCallCount + 1) == expectedCallCount) {
                // counter finished, run callback
                callback.accept(Objects.requireNonNull(ThreadIndex.currentThreadIndex()));
            }
        }
    }
    
    @Override
    public void enqueue(Item work) {
        if (work.counter() != this) {
            throw new IllegalArgumentException("Cannot enqueue work item for a different counter");
        }
    }
    
    
    @Override
    public boolean isComplete() {
        return false;
    }
    
    @Override
    public void waitComplete() {
    
    }
}
