package graphics.cinnabar.lib.threading;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.threading.IWorkQueue;
import graphics.cinnabar.api.threading.ThreadIndex;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

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
    
    
    @API
    @Override
    @ThreadSafety.Many
    public void setCallback(IWorkQueue.Work callback) {
        this.callback = callback;
    }
    
    @API
    @Override
    @ThreadSafety.Any
    public void beginEnqueuing() {
        enqueuingItems = true;
        if (callbackCount.get() != maximumCallbackCount.get()) {
            throw new IllegalStateException("Cannot begin enqueuing items until counter has been completed");
        }
    }
    
    @API
    @Override
    @ThreadSafety.Any
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
    
    @ThreadSafety.Many
    private void increment() {
        if (!enqueuingItems) {
            throw new IllegalStateException("Must be enqueuing items to increment counter");
        }
        if (value.incrementAndGet() <= 0) {
            throw new IllegalStateException("Counter over decremented");
        }
    }
    
    @Internal
    @Override
    @ThreadSafety.Many
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
    
    @API
    @Override
    @ThreadSafety.Many
    public void enqueue(Item work) {
        if (work.counter() != this) {
            throw new IllegalArgumentException("Cannot enqueue work item for a different counter");
        }
        increment();
        queue.enqueue(work);
    }
    
    @API
    @Override
    @ThreadSafety.Many
    public boolean isComplete() {
        return value.get() == 0;
    }
    
    @Override
    public void waitComplete() {
        final var currentThreadIndex = ThreadIndex.currentThreadIndex();
        final var isMainQueueAndMainThread = queue instanceof WorkQueue.SingleThread.Main && currentThreadIndex.isMainThread();
        final var isBackgroundQueue = queue instanceof WorkQueue.BackgroundThreads && currentThreadIndex.valid();
        // TODO: may want to prohibit the main thread from running work on the background queue
        if (isMainQueueAndMainThread || isBackgroundQueue) {
            // this thread is allowed to execute work on this queue, so execute work while we wait
            while (!isComplete()) {
                while (true) {
                    if (!queue.runOne(currentThreadIndex)) {
                        break;
                    }
                }
                // yield to allow other threads to use this core
                Thread.yield();
            }
        } else {
            // this thread cannot execute work on this queue, just wait for the work to complete
            // this can lead to a deadlock if the execution thread is waiting on this work to complete
            while (!isComplete()) {
                // yield to allow other threads to use this core
                // TODO: potentially wait on a lock?
                //       that requires the waking thread to grab that lock though, which is less than ideal
                Thread.yield();
            }
        }
    }
}
