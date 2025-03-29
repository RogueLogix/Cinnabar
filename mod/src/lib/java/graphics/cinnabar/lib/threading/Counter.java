package graphics.cinnabar.lib.threading;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.threading.IWorkQueue;
import graphics.cinnabar.api.threading.ThreadIndex;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

public class Counter implements IWorkQueue.ICounter {
    
    private final WorkQueue queue;
    
    @Nullable
    private Counter parent;
    private final AtomicLong value = new AtomicLong();
    @Nullable
    private IWorkQueue.Work callback;
    private boolean callbackPending = false;
    private boolean runningCallback = false;
    
    public Counter(WorkQueue queue) {
        this.queue = queue;
    }
    
    
    @API
    @Override
    @ThreadSafety.Many
    public void setCallback(@Nullable IWorkQueue.Work callback) {
        if (!isComplete()) {
            throw new IllegalStateException("Cannot change callback while pending");
        }
        this.callback = callback;
    }
    
    @Override
    public void setParent(IWorkQueue.ICounter iCounter) {
        if (!isComplete()) {
            throw new IllegalStateException("Cannot change parent while pending");
        }
        if (!(iCounter instanceof Counter counter)) {
            throw new IllegalArgumentException();
        }
        this.parent = counter;
    }
    
    @API
    @Override
    @ThreadSafety.Any
    public void beginEnqueuing() {
        // no check is done on beginEnqueuing to allow multiple calls to it
        // ie: begin begin end begin end end, is a valid pattern, and a callback will only be run once
        callbackPending = true;
        increment();
    }
    
    @API
    @Override
    @ThreadSafety.Any
    public void endEnqueuing() {
        queue.wakeThreads(true);
        decrement();
    }
    
    @ThreadSafety.Many
    private void increment() {
        // increment without pending callback is allowed, for aggregation counters
        final var newValue = value.incrementAndGet();
        if (newValue == 1 && parent != null) {
            // first increment, also increment the parent (if we have one)
            parent.increment();
        }
        if (newValue <= 0) {
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
            if (callbackPending && callback != null) {
                runningCallback = true;
                callbackPending = false;
                callback.accept(ThreadIndex.currentThreadIndex());
            }
            if (parent != null) {
                // last decrement, also decrement parent
                // this is done after the callback so that if this counter was re-incremented, the parent will never hit 0 until this counter is fully complete
                parent.decrement();
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
        return value.get() == 0 && !callbackPending && !runningCallback;
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
