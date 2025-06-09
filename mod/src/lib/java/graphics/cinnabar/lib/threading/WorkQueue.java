package graphics.cinnabar.lib.threading;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.threading.IWorkQueue;
import graphics.cinnabar.api.threading.ThreadIndex;
import graphics.cinnabar.api.threading.ThreadIndexRegistry;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.lib.datastructures.RingQueue;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

import static graphics.cinnabar.lib.CinnabarLib.CINNABAR_LIB_LOG;

public abstract class WorkQueue implements IWorkQueue {
    
    private final RingQueue<Work> workRing = new RingQueue<>(16);
    private final AtomicLong enqueueingThreads = new AtomicLong();
    
    @API
    @Override
    @ThreadSafety.Many
    public void beginEnqueueing() {
        enqueueingThreads.getAndIncrement();
    }
    
    @API
    @Override
    @ThreadSafety.Many
    public void endEnqueueing() {
        enqueueingThreads.getAndDecrement();
        wakeThreads(true);
    }
    
    @API
    @Override
    @ThreadSafety.Many
    public void enqueue(Work work) {
        workRing.ForceEnqueue(work);
        if (enqueueingThreads.get() == 0) {
            wakeThreads(false);
        }
    }
    
    @API
    @Override
    @ThreadSafety.Many
    public ICounter createCounter() {
        return new Counter(this);
    }
    
    @Internal
    @ThreadSafety.Many
    abstract void wakeThreads(boolean all);
    
    @ThreadSafety.Many
    boolean runOne(ThreadIndex index) {
        @Nullable
        final var work = workRing.Dequeue();
        if (work == null) {
            return false;
        }
        work.accept(index);
        return true;
    }
    
    public static class SingleThread extends WorkQueue {
        
        public static class Main extends SingleThread {
            public boolean runOne(ThreadIndex index) {
                if (index != ThreadIndex.MAIN) {
                    return false;
                }
                return super.runOne(ThreadIndex.MAIN);
            }
            
            public void runAllCurrentlyEnqueued() {
                insertBreakpoint();
                runUntilBreakpointNumber(breakpointCounter);
            }
        }
        
        // starting at -1 allows for things to be enqueued for before the end of frame 0
        protected long breakpointCounter = -1;
        
        @ThreadSafety.Many
        void wakeThreads(boolean all) {
        }
        
        @ThreadSafety.Any
        private void incrementBreakpointCounter(ThreadIndex index) {
            breakpointCounter++;
        }
        
        @ThreadSafety.Many
        public void insertBreakpoint() {
            enqueue(this::incrementBreakpointCounter);
        }
        
        @ThreadSafety.Any
        public void runUntilBreakpointNumber(final long breakValue) {
            final var threadIndex = ThreadIndex.currentThreadIndex();
            while (breakpointCounter < breakValue) {
                if (!runOne(threadIndex)) {
                    // no breakpoint hit, but end of queue reached
                    // close enough
                    break;
                }
            }
        }
    }
    
    public static class BackgroundThreads extends WorkQueue implements Destroyable {
        
        private final Thread[] threads;
        private volatile boolean running = true;
        
        public BackgroundThreads(int threadCount) {
            threads = new Thread[threadCount];
            Thread.UncaughtExceptionHandler handler = (thread, exception) -> {
                CINNABAR_LIB_LOG.error(exception.toString());
                exception.printStackTrace();
                System.exit(1);
            };
            for (int i = 0; i < threadCount; i++) {
                final var thread = new Thread(this::threadFunc);
                thread.setUncaughtExceptionHandler(handler);
                thread.setDaemon(true);
                thread.setName("CinnabarWorker" + i);
                thread.start();
                threads[i] = thread;
            }
        }
        
        @Override
        public void destroy() {
            running = false;
            wakeThreads(true);
            for (int i = 0; i < threads.length; i++) {
                final var thread = threads[i];
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        
        void threadFunc() {
            final var threadIndex = ThreadIndexRegistry.registerThisThread();
            int spinCount = 0;
            while (running) {
                if(runOne(threadIndex)) {
                    spinCount = 0;
                    continue;
                }
                spinCount++;
                if (spinCount <= 100) {
                    Thread.yield();
                    continue;
                }
                threadWait();
                spinCount = 0;
            }
        }
        
        @ThreadSafety.Many
        synchronized void wakeThreads(boolean all) {
            if (all) {
                notifyAll();
            } else {
                notify();
            }
        }
        
        synchronized void threadWait() {
            try {
                wait(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
