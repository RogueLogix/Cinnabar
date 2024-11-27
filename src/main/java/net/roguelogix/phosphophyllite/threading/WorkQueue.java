package net.roguelogix.phosphophyllite.threading;

import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.internal.util.threading.ResizingRingBuffer;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

@SuppressWarnings("unused")
@NonnullDefault
@ThreadSafety.ItDepends
public class WorkQueue {
    private final ResizingRingBuffer<Object> queue;
    
    private final ArrayList<DequeueThread> dequeueThreads = new ArrayList<>();
    
    private final AtomicReference<@Nullable RuntimeException> toRethrow = new AtomicReference<>();
    
    // not the most peformant, but the most compatible
    public WorkQueue() {
        this(false);
    }
    
    public WorkQueue(boolean singleProducerSingleConsumer) {
        this(singleProducerSingleConsumer, singleProducerSingleConsumer);
    }
    
    public WorkQueue(boolean singleProducer, boolean singleConsumer) {
        this(0, singleProducer, singleConsumer);
    }
    
    public WorkQueue(int initialCapacity) {
        this(initialCapacity, false);
    }
    
    public WorkQueue(int initialCapacity, boolean singleProducerSingleConsumer) {
        this(initialCapacity, singleProducerSingleConsumer, singleProducerSingleConsumer);
    }
    
    public WorkQueue(int initialCapacity, boolean singleProducer, boolean singleConsumer) {
        // workaround for FML issue
        // triggers the classloading here instead of the finalizer
        //noinspection RedundantOperationOnEmptyContainer
        dequeueThreads.forEach(DequeueThread::finish);
        queue = new ResizingRingBuffer<>(0);
    }
    
    private class DequeueThread implements Runnable {
        private final WeakReference<ResizingRingBuffer<Object>> queue;
        private final AtomicBoolean stop = new AtomicBoolean(false);
        private final int threadID;
        
        public DequeueThread(ResizingRingBuffer<Object> queue, @Nullable String name, int threadID) {
            this.queue = new WeakReference<>(queue);
            this.threadID = threadID;
            final var thread = new Thread(this);
            if (name != null) {
                thread.setName(name);
            }
            thread.setDaemon(true); // just, because, shouldn't be necessary, but just because
            thread.start();
        }
        
        public void run() {
            while (!stop.get()) {
                @Nullable var queue = this.queue.get();
                if (queue == null) {
                    return;
                }
                Object nextItem = queue.take();
                try {
                    if (nextItem instanceof Runnable runnable) {
                        runnable.run();
                    } else if (nextItem instanceof IntConsumer consumer) {
                        consumer.accept(threadID);
                    }
                } catch (RuntimeException e) {
                    toRethrow.set(e);
                    //noinspection CallToPrintStackTrace
                    e.printStackTrace();
                } catch (Throwable e) {
                    // this should be impossible, but just in case
                    //noinspection CallToPrintStackTrace
                    e.printStackTrace();
                    Minecraft.getInstance().emergencySaveAndCrash(new CrashReport("Exception rolled back to Phosphophyllite WorkQueue", e));
                }
            }
            
        }
        
        public void finish() {
            stop.set(true);
        }
    }
    
    public WorkQueue addProcessingThread() {
        return addProcessingThreads(1, null);
    }
    
    public WorkQueue addProcessingThread(String name) {
        return addProcessingThreads(1, name);
    }
    
    public WorkQueue addProcessingThreads(int threads) {
        return addProcessingThreads(threads, null);
    }
    
    public WorkQueue addProcessingThreads(int threads, @Nullable String name) {
        for (int i = 0; i < threads; i++) {
            dequeueThreads.add(new DequeueThread(queue, name == null ? null : (threads == 1 ? name : name + i), dequeueThreads.size()));
        }
        return this;
    }
    
    public int threadCount() {
        return dequeueThreads.size();
    }
    
    public void finish() {
        dequeueThreads.forEach(DequeueThread::finish);
        synchronized (queue) {
            queue.notifyAll();
        }
    }
    
    @SuppressWarnings("removal")
    @Override
    protected void finalize() {
        finish();
    }
    
    private static class WorkItem implements IntConsumer {
        private final Event waitEvent = new Event();
        private final Object work;
        
        WorkItem(final ResizingRingBuffer<Object> queue, final Object work, final Event[] waitEvents) {
            this.work = work;
            if (waitEvents.length == 0) {
                queue.put(work);
                return;
            }
            final Event readyEvent = new Event();
            final AtomicLong unTriggeredWaitEvents = new AtomicLong(Long.MAX_VALUE);
            unTriggeredWaitEvents.set(waitEvents.length);
            final Runnable callback = () -> {
                if (unTriggeredWaitEvents.decrementAndGet() == 0) {
                    synchronized (readyEvent) {
                        readyEvent.trigger();
                    }
                }
            };
            for (@Nullable final var event : waitEvents) {
                if (event == null) {
                    if (unTriggeredWaitEvents.decrementAndGet() == 0) {
                        readyEvent.trigger();
                    }
                    continue;
                }
                event.registerCallback(callback);
            }
            readyEvent.registerCallback(() -> queue.put(work));
        }
        
        @Override
        public void accept(int threadID) {
            try {
                if (work instanceof Runnable runnable) {
                    runnable.run();
                } else if (work instanceof IntConsumer consumer) {
                    consumer.accept(threadID);
                }
            } finally {
                waitEvent.trigger();
            }
        }
    }
    
    @Contract(value = "_, _ -> new")
    public Event enqueue(IntConsumer runnable, @Nullable Event... events) {
        if (events.length == 0) {
            return enqueue(runnable);
        }
        if (events.length == 1) {
            return enqueue(runnable, events[0]);
        }
        @Nullable final var rethrow = toRethrow.getAndSet(null);
        if (rethrow != null) {
            throw new RuntimeException(rethrow);
        }
        final var item = new WorkItem(queue, runnable, events);
        return item.waitEvent;
    }
    
    @Contract(value = "_, _ -> new")
    public Event enqueue(Runnable runnable, @Nullable Event... events) {
        if (events.length == 0) {
            return enqueue(runnable);
        }
        if (events.length == 1) {
            return enqueue(runnable, events[0]);
        }
        @Nullable final var rethrow = toRethrow.getAndSet(null);
        if (rethrow != null) {
            throw new RuntimeException(rethrow);
        }
        WorkItem item = new WorkItem(queue, runnable, events);
        return item.waitEvent;
    }
    
    @Contract(value = "_, _ -> new")
    public Event enqueue(IntConsumer runnable, @Nullable Event waitEvent) {
        if (waitEvent == null || waitEvent.ready()) {
            return enqueue(runnable);
        }
        final var event = new Event();
        waitEvent.registerCallback(() -> enqueueUntracked((threadID) -> {
            try {
                runnable.accept(threadID);
            } finally {
                event.trigger();
            }
        }));
        return event;
    }
    
    @Contract(value = "_, _ -> new")
    public Event enqueue(Runnable runnable, @Nullable Event waitEvent) {
        if (waitEvent == null || waitEvent.ready()) {
            return enqueue(runnable);
        }
        final var event = new Event();
        waitEvent.registerCallback(() -> enqueueUntracked(() -> {
            try {
                runnable.run();
            } finally {
                event.trigger();
            }
        }));
        return event;
    }
    
    @Contract(value = "_ -> new", pure = true)
    public Event enqueue(IntConsumer runnable) {
        final var event = new Event();
        enqueueUntracked((threadID) -> {
            try {
                runnable.accept(threadID);
            } finally {
                event.trigger();
            }
        });
        return event;
    }
    
    @Contract(value = "_ -> new", pure = true)
    public Event enqueue(Runnable runnable) {
        final var event = new Event();
        enqueueUntracked(() -> {
            try {
                runnable.run();
            } finally {
                event.trigger();
            }
        });
        return event;
    }
    
    public void enqueueUntracked(Runnable runnable) {
        @Nullable final var rethrow = toRethrow.getAndSet(null);
        if (rethrow != null) {
            throw new RuntimeException(rethrow);
        }
        queue.put(runnable);
    }
    
    public void enqueueUntracked(IntConsumer runnable) {
        @Nullable final var rethrow = toRethrow.getAndSet(null);
        if (rethrow != null) {
            throw new RuntimeException(rethrow);
        }
        queue.put(runnable);
    }
    
    public boolean runOne() {
        return runOne(0);
    }
    
    public boolean runOne(int threadID) {
        if (!dequeueThreads.isEmpty()) {
            return false;
        }
        @Nullable final var toRun = queue.poll();
        if (toRun != null) {
            if (toRun instanceof Runnable runnable) {
                runnable.run();
            } else if (toRun instanceof IntConsumer consumer) {
                consumer.accept(threadID);
            }
            return true;
        }
        return false;
    }
    
    public boolean runAll() {
        return runAll(0);
    }
    
    public boolean runAll(int threadID) {
        if (!dequeueThreads.isEmpty()) {
            return false;
        }
        @Nullable Object toRun;
        boolean ranSomething = false;
        while ((toRun = queue.poll()) != null) {
            if (toRun instanceof Runnable runnable) {
                runnable.run();
            } else if (toRun instanceof IntConsumer consumer) {
                consumer.accept(threadID);
            }
            ranSomething = true;
        }
        return ranSomething;
    }
}
