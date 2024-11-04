package net.roguelogix.phosphophyllite.threading;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@NonnullDefault
class ResizingRingBuffer<T> implements RingBuffer<T> {
    
    private static final int BLOCK_ID_BITSHIFT = 10;
    // 1024
    private static final int BLOCK_SIZE = 1 << BLOCK_ID_BITSHIFT;
    
    private final ReferenceList<T[]> blocks = new ReferenceArrayList<>();
    private final IntArrayList nextPutBlockIndices = new IntArrayList();
    private final IntArrayList nextTakeBlockIndices = new IntArrayList();
    
    private long currentPutIndex = 0;
    private long currentTakeIndex = 0;
    
    protected final ReentrantLock waitingLock = new ReentrantLock();
    protected final Condition notEmpty = waitingLock.newCondition();
    protected int waitingThreads = 0;
    
    ResizingRingBuffer(int minimumInitialCapacity) {
        // round up to next multiple of block size
        final var initialCapacity = minimumInitialCapacity <= 0 ? BLOCK_SIZE : (minimumInitialCapacity + (BLOCK_SIZE - 1)) & (~BLOCK_SIZE);
        final var initialBlockCount = initialCapacity >> BLOCK_ID_BITSHIFT;
        for (int i = 0; i < initialBlockCount; i++) {
            //noinspection unchecked
            blocks.add((T[]) new Object[BLOCK_SIZE]);
            nextPutBlockIndices.add(i + 1);
            nextTakeBlockIndices.add(i + 1);
        }
        // the above loop will create links past the end, make sure that loops back
        nextPutBlockIndices.set(nextPutBlockIndices.size() - 1, 0);
        nextTakeBlockIndices.set(nextTakeBlockIndices.size() - 1, 0);
    }
    
    @ThreadSafety.Any(lockGroups = "1")
    public void put(T t) {
        int currentPutBlockIndex = (int) ((currentPutIndex >> 32) & Integer.MAX_VALUE);
        int currentPutSubBlockIndex = (int) ((currentPutIndex) & Integer.MAX_VALUE);
        blocks.get(currentPutBlockIndex)[currentPutSubBlockIndex] = t;
        // tail *may* be waiting on head, check if we need to notify a thread
        if (++currentPutSubBlockIndex >= BLOCK_SIZE) {
            currentPutSubBlockIndex = 0;
            currentPutBlockIndex = nextPutBlockIndices.getInt(currentPutBlockIndex);
        }
        final int currentTakeBlockIndex = (int) ((currentTakeIndex >> 32) & Integer.MAX_VALUE);
        final int currentTakeSubBlockIndex = (int) ((currentTakeIndex) & Integer.MAX_VALUE);
        if (currentPutBlockIndex == currentTakeBlockIndex && currentPutSubBlockIndex == currentTakeSubBlockIndex) {
            // head eating tail, create a new block, and chain it in
            // it is ok, if we are in the middle of a block, null is treated as no-element, and will be skipped
            //noinspection unchecked
            blocks.add((T[]) new Object[BLOCK_SIZE]);
            nextPutBlockIndices.add(currentPutBlockIndex);
            nextPutBlockIndices.set(currentPutBlockIndex, nextPutBlockIndices.size() - 1);
            currentPutBlockIndex = nextPutBlockIndices.size() - 1;
            currentPutSubBlockIndex = 0;
        }
        // update index before signaling a waiting thread
        currentPutIndex = ((long)currentPutBlockIndex << 32) | ((long)currentPutSubBlockIndex);
        if (waitingThreads > 0) {
            while (!waitingLock.tryLock()) {
                Thread.onSpinWait();
            }
            try {
                notEmpty.signal();
            } finally {
                waitingLock.unlock();
            }
        }
    }
    
    @ThreadSafety.Any(lockGroups = "2")
    public T take() {
        @Nullable T toReturn = null;
        while (toReturn == null) {
            while (currentPutIndex == currentTakeIndex) {
                threadWait();
            }
            toReturn = poll();
        }
        return toReturn;
    }
    
    @SuppressWarnings("NullableProblems") // the annotations are correct, the inspection is wrong
    @Nullable
    @ThreadSafety.Any(lockGroups = "2")
    public T poll() {
        @Nullable T toReturn = null;
        while (toReturn == null) {
            if (currentPutIndex == currentTakeIndex) {
                return null;
            }
            int currentTakeBlockIndex = (int) ((currentTakeIndex >> 32) & Integer.MAX_VALUE);
            int currentTakeSubBlockIndex = (int) ((currentTakeIndex) & Integer.MAX_VALUE);
            final var block = blocks.get(currentTakeBlockIndex);
            toReturn = block[currentTakeSubBlockIndex];
            block[currentTakeSubBlockIndex] = null;
            if (++currentTakeSubBlockIndex >= BLOCK_SIZE) {
                currentTakeSubBlockIndex = 0;
                if (nextTakeBlockIndices.size() < nextPutBlockIndices.size()) {
                    nextTakeBlockIndices.ensureCapacity(nextPutBlockIndices.size());
                    nextTakeBlockIndices.size(nextPutBlockIndices.size());
                }
                final var newTakeBlockIndex = nextTakeBlockIndices.getInt(currentTakeBlockIndex);
                // update the chain, so we follow the order it was written in when re-reading
                nextTakeBlockIndices.set(currentTakeBlockIndex, nextPutBlockIndices.getInt(currentTakeBlockIndex));
                currentTakeBlockIndex = newTakeBlockIndex;
            }
            // updating this every time we loop through may allow the put thread to use these slots
            currentTakeIndex = ((long)currentTakeBlockIndex << 32) | ((long)currentTakeSubBlockIndex);
        }
        return toReturn;
    }
    
    protected void threadWait() {
        while (!waitingLock.tryLock()) {
            Thread.onSpinWait();
        }
        waitingThreads++;
        try {
            notEmpty.awaitUninterruptibly();
        } finally {
            waitingLock.unlock();
            waitingThreads--;
        }
    }
    
    static class MultiProducerMultiConsumer<T> extends ResizingRingBuffer<T> implements RingBuffer.MultiProducerMultiConsumer<T> {
        
        private final ReentrantLock putLock = new ReentrantLock();
        
        MultiProducerMultiConsumer(int initialCapacity) {
            super(initialCapacity);
        }
        
        @Override
        @ThreadSafety.Many
        public void put(T t) {
            final var lock = this.putLock;
            while (!lock.tryLock()) {
                Thread.onSpinWait();
            }
            try {
                super.put(t);
            } finally {
                lock.unlock();
            }
        }
        
        @Override
        @ThreadSafety.Many
        public T take() {
            final var lock = this.waitingLock;
            while (!lock.tryLock()) {
                Thread.onSpinWait();
            }
            try {
                return super.take();
            } finally {
                lock.unlock();
            }
        }
        
        @Override
        @Nullable
        @ThreadSafety.Many
        public T poll() {
            final var lock = this.waitingLock;
            while (!lock.tryLock()) {
                Thread.onSpinWait();
            }
            try {
                return super.poll();
            } finally {
                lock.unlock();
            }
        }
        
        @Override
        @ThreadSafety.Many
        protected void threadWait() {
            waitingThreads++;
            notEmpty.awaitUninterruptibly();
            waitingThreads--;
        }
    }
    
    static class MultiProducer<T> extends ResizingRingBuffer<T> implements RingBuffer.MultiProducer<T> {
        
        MultiProducer(int minimumInitialCapacity) {
            super(minimumInitialCapacity);
        }
        
        @Override
        @ThreadSafety.Many
        public synchronized void put(T t) {
            super.put(t);
        }
    }
    
    static class MultiConsumer<T> extends ResizingRingBuffer<T> implements RingBuffer.MultiConsumer<T> {
        
        MultiConsumer(int minimumInitialCapacity) {
            super(minimumInitialCapacity);
        }
        
        @Override
        @ThreadSafety.Many
        public synchronized T take() {
            return super.take();
        }
        
        @Override
        @Nullable
        @ThreadSafety.Many
        public synchronized T poll() {
            return super.poll();
        }
    }
}
