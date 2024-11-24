package graphics.cinnabar.api.threading;

import graphics.cinnabar.internal.util.threading.ResizingRingBuffer;
import graphics.cinnabar.internal.vulkan.Destroyable;
import net.roguelogix.phosphophyllite.util.API;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;

@API
public class CounterQueue implements Destroyable {
    
    public class Counter {
        // TODO: use unsafe direct to avoid extra objects?
        private final AtomicLong value = new AtomicLong();
        private final AtomicLong maximumCallbackCount = new AtomicLong();
        private final AtomicLong callbackCount = new AtomicLong();
        public Runnable callback;
        private boolean enqueuingItems = false;
        
        
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
            wakeThreads(0);
            final var expectedCallCount = maximumCallbackCount.getAndIncrement();
            enqueuingItems = false;
            if (value.decrementAndGet() == 0) {
                // if we hit zero, attempt to call the callback
                if (callbackCount.compareAndExchange(expectedCallCount, expectedCallCount + 1) == expectedCallCount) {
                    // jobs finished before pushing completed, call callback here
                    callback.run();
                }
            }
        }
        
        private void decrement() {
            if (value.decrementAndGet() == 0) {
                // if we hit zero, attempt to call the callback
                // if the atomic compare exchange fails, then items are still being added and will catch if all items completed before adding finished
                final var expectedCallCount = maximumCallbackCount.get() - 1;
                if (callbackCount.compareAndExchange(expectedCallCount, expectedCallCount + 1) == expectedCallCount) {
                    // counter finished, run callback
                    callback.run();
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
        
        public void enqueue(IntConsumer toRun) {
            this.enqueue(new Item(toRun, this));
        }
        
        public void enqueue(Item toRun) {
            if (toRun.counter != this) {
                throw new IllegalArgumentException();
            }
            increment();
            CounterQueue.this.enqueue(toRun);
        }
        
        public void complete() {
            complete(-1);
        }
        
        public void complete(int threadID) {
            while (value.get() != 0) {
                if (threadID != -1) {
                    if (CounterQueue.this.runOne(threadID)) {
                        continue;
                    }
                }
                // TODO better thread waiting, particularly for -1 threadID
                Thread.onSpinWait();
            }
        }
    }
    
    // public to allow these to be created once and re-used
    public record Item(IntConsumer toRun, Counter counter) implements IntConsumer {
        
        @Override
        public void accept(int threadID) {
            toRun.accept(threadID);
            counter.decrement();
        }
        
    }
    
    private final ResizingRingBuffer<IntConsumer> queue = new ResizingRingBuffer<>(0);
    private final ReentrantLock waitingLock = new ReentrantLock();
    private final Condition waitCondition = waitingLock.newCondition();
    private final Thread[] threads;
    private boolean shutdownThreads = false;
    
    public CounterQueue() {
        this(0, null);
    }
    
    public CounterQueue(int processingThreadCount, @Nullable String threadName) {
        threads = new Thread[processingThreadCount];
        for (int i = 0; i < threads.length; i++) {
            final int threadID = i;
            threads[i] = new Thread(() -> {
                threadLoop(threadID);
            });
            threads[i].setDaemon(true);
            if (threadName != null) {
                threads[i].setName(threadName.formatted(i));
            }
            threads[i].start();
        }
    }
    
    @Override
    public void destroy() {
        if (threads.length == 0) {
            return;
        }
        shutdownThreads = true;
        wakeThreads(-1);
        try {
            for (int i = 0; i < threads.length; i++) {
                threads[i].join();
            }
        } catch (InterruptedException e) {
            System.err.println("INTERRUPTED SHUTTING DOWN WORKER THREADS");
            throw new RuntimeException(e);
        }
    }
    
    public int threadCount() {
        return threads.length;
    }
    
    private void threadLoop(int threadID) {
        while (!shutdownThreads) {
            if (!runOne(threadID)) {
                threadWait();
            }
        }
    }
    
    private void wakeThreads(int count) {
        if (threads.length == 0) {
            return;
        }
        while (!waitingLock.tryLock()) {
            Thread.onSpinWait();
        }
        try {
            if (count == 1) {
                waitCondition.signal();
            } else {
                waitCondition.signalAll();
            }
        } catch (IllegalMonitorStateException e) {
            e.printStackTrace();
        }finally {
            waitingLock.unlock();
        }
    }
    
    private void threadWait() {
        if (threads.length == 0) {
            return;
        }
        while (!waitingLock.tryLock()) {
            Thread.onSpinWait();
        }
        try {
            waitCondition.awaitUninterruptibly();
        } finally {
            waitingLock.unlock();
        }
    }
    
    private void enqueue(IntConsumer toRun, boolean notify) {
        queue.put(toRun);
        if (notify) {
            wakeThreads(1);
        }
    }
    
    public void enqueue(IntConsumer toRun) {
        enqueue(toRun, true);
    }
    
    private void enqueue(IntConsumer... toRun) {
        for (int i = 0; i < toRun.length; i++) {
            enqueue(toRun[i], false);
        }
        wakeThreads(toRun.length);
    }
    
    private void enqueue(List<IntConsumer> toRun) {
        for (int i = 0; i < toRun.size(); i++) {
            enqueue(toRun.get(i), false);
        }
        wakeThreads(toRun.size());
    }
    
    public boolean runOne(int threadID) {
        final var toRun = queue.poll();
        if (toRun == null) {
            return false;
        }
        toRun.accept(threadID);
        return true;
    }
    
    public void runAll(int threadID) {
        while (true) {
            if (!runOne(threadID)) {
                break;
            }
        }
    }
}
