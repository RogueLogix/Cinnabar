package graphics.cinnabar.lib.threading;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.threading.ISemaphore;
import graphics.cinnabar.api.threading.IWorkQueue;
import graphics.cinnabar.api.threading.ThreadIndex;
import graphics.cinnabar.api.threading.ThreadIndexRegistry;
import graphics.cinnabar.lib.datastructures.RingQueue;
import it.unimi.dsi.fastutil.longs.LongReferencePair;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

public abstract class WorkQueue implements IWorkQueue {
    
    protected record SemaphoreOp(ISemaphore semaphore, long value, boolean signal) {
        
        void doSignal() {
            assert !signal;
            semaphore.signal(value);
        }
        
        boolean isSignaled() {
            assert !signal;
            return semaphore.value() >= value;
        }
        
    }
    
    private static final VarHandle LONG_ARRAY_VAR_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);
    
    protected final RingQueue<Object> workRing = new RingQueue<>(16);
    private final IntConsumer threadWake;
    
    protected WorkQueue(IntConsumer threadWake) {
        this.threadWake = threadWake;
    }
    
    @API
    @Override
    @ThreadSafety.Many
    public void wait(ISemaphore semaphore, long value) {
        workRing.forceEnqueue(new SemaphoreOp(semaphore, value, false));
    }
    
    @API
    @Override
    @ThreadSafety.Many
    public void enqueue(Work work) {
        workRing.forceEnqueue(work);
        threadWake.accept(1);
    }
    
    @API
    @Override
    @ThreadSafety.Many
    public void enqueue(List<Work> work) {
        if (work.isEmpty()) {
            return;
        }
        // its fine, probably
        //noinspection unchecked
        workRing.forceEnqueueMany((List<Object>) (Object) work);
        threadWake.accept(work.size());
    }
    
    @API
    @Override
    @ThreadSafety.Many
    public void signal(ISemaphore semaphore, long value) {
        workRing.forceEnqueue(new SemaphoreOp(semaphore, value, true));
    }
    
    public static class SingleThread extends WorkQueue {
        
        private final ThreadIndex index;
        
        // starting at -1 allows for things to be enqueued for before the end of frame 0
        protected long breakpointCounter = -1;
        protected long nextBreakpoint = 0;
        @Nullable
        private SemaphoreOp pendingWait;
        
        SingleThread(ThreadIndex index, IntConsumer wakeThread) {
            super(wakeThread);
            this.index = index;
        }
        
        @ThreadSafety.Any
        private void incrementBreakpointCounter(ThreadIndex index) {
            breakpointCounter++;
        }
        
        @API
        @ThreadSafety.Many
        public long insertBreakpoint() {
            enqueue(this::incrementBreakpointCounter);
            return nextBreakpoint++;
        }
        
        private boolean runOne() {
            assert ThreadIndex.currentThreadIndex() == index;
            if (pendingWait != null) {
                if (!pendingWait.isSignaled()) {
                    return false;
                }
                pendingWait = null;
            }
            @Nullable
            final var item = workRing.dequeue();
            if (item == null) {
                return false;
            }
            switch (item) {
                case Work work -> work.accept(index);
                case SemaphoreOp op -> {
                    if (op.signal) {
                        op.doSignal();
                    } else {
                        if (op.isSignaled()) {
                            return true;
                        }
                        pendingWait = op;
                        return false;
                    }
                }
                default -> throw new IllegalStateException("Unexpected value: " + item);
            }
            return true;
        }
        
        @API
        @ThreadSafety.Any
        public boolean runUntilBreakpointNumber(final long breakValue) {
            if (!ThreadIndex.currentThreadIndex().equals(index)) {
                throw new IllegalThreadStateException();
            }
            boolean anyRan = false;
            while (breakpointCounter < breakValue) {
                if (!runOne()) {
                    // didn't hit the breakpoint, but end of queue (or stalled on a semaphore) reached
                    // close enough
                    break;
                }
                anyRan = true;
            }
            return anyRan;
        }
        
        @API
        @ThreadSafety.Any
        public boolean runAllCurrentlyEnqueued() {
            return runUntilBreakpointNumber(insertBreakpoint());
        }
        
        @API
        @ThreadSafety.Any
        public boolean runUntilStalled() {
            return runUntilBreakpointNumber(Long.MAX_VALUE);
        }
    }
    
    public static class MultiThreaded extends WorkQueue {
        
        private final long[] executingIndex = new long[ThreadIndexRegistry.totalThreads];
        private final RingQueue<LongReferencePair<SemaphoreOp>> pendingSignals = new RingQueue<>(8);
        
        MultiThreaded(IntConsumer wakeThread) {
            super(wakeThread);
            Arrays.fill(executingIndex, Long.MAX_VALUE);
        }
        
        private static boolean waitConditionCheck(@Nullable Object item) {
            // a signal doesn't need to wait on anything for being dequeued, only waits do
            return !(item instanceof SemaphoreOp semaphoreOp) || !semaphoreOp.signal || semaphoreOp.isSignaled();
        }
        
        private boolean signalConditionCheck(@Nullable LongReferencePair<SemaphoreOp> entry) {
            if (entry == null) {
                return true;
            }
            VarHandle.acquireFence();
            for (int i = 0; i < ThreadIndexRegistry.totalThreads; i++) {
                if (executingIndex[i] < entry.firstLong()) {
                    return false;
                }
            }
            return true;
        }
        
        private void processPendingSignals() {
            @Nullable
            LongReferencePair<LongReferencePair<SemaphoreOp>> signal;
            while ((signal = pendingSignals.conditionalDequeue(this::signalConditionCheck)) != null) {
                signal.value().value().doSignal();
            }
        }
        
        private boolean runOne(ThreadIndex index) {
            assert ThreadIndex.currentThreadIndex() == index;
            if (!index.valid()) {
                throw new IllegalStateException();
            }
            processPendingSignals();
            // prevent signaling a semaphore
            LONG_ARRAY_VAR_HANDLE.setRelease(executingIndex, index.index(), 0);
            @Nullable
            final var entry = workRing.conditionalDequeue(MultiThreaded::waitConditionCheck);
            if (entry == null) {
                LONG_ARRAY_VAR_HANDLE.setRelease(executingIndex, index.index(), Long.MAX_VALUE);
                return false;
            }
            LONG_ARRAY_VAR_HANDLE.setRelease(executingIndex, index.index(), entry.firstLong());
            
            @Nullable
            final var item = entry.value();
            switch (item) {
                case null -> {
                    LONG_ARRAY_VAR_HANDLE.setRelease(executingIndex, index.index(), Long.MAX_VALUE);
                    return false;
                }
                case SemaphoreOp semaphoreOp -> {
                    if (semaphoreOp.signal) {
                        //noinspection unchecked
                        pendingSignals.forceEnqueue((LongReferencePair<SemaphoreOp>) (Object) entry);
                    }
                    // waits don't actually execute anything
                }
                case Work work -> work.accept(index);
                default -> {
                }
            }
            
            LONG_ARRAY_VAR_HANDLE.setRelease(executingIndex, index.index(), Long.MAX_VALUE);
            processPendingSignals();
            return true;
        }
        
        @API
        @ThreadSafety.Any
        public boolean runUntilStalled(ThreadIndex threadIndex) {
            boolean anyRan = false;
            while (runOne(threadIndex)) {
                anyRan = true;
            }
            return anyRan;
        }
    }
}
