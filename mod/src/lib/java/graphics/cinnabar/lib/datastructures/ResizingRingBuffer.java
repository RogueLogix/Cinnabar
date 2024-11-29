package graphics.cinnabar.lib.datastructures;

import graphics.cinnabar.api.annotations.ThreadSafety;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class ResizingRingBuffer<T> {
    
    // 8192 items per block
    // 64k pointer array allocation per block, ie: one page
    private static final int BLOCK_ID_BITSHIFT = 13;
    private static final int BLOCK_SIZE = 1 << BLOCK_ID_BITSHIFT;
    private static final int BLOCK_LAST_INDEX = BLOCK_SIZE - 1;
    
    private static class Block<T> {
        final int index;
        boolean newBlock = true;
        volatile boolean unmarkNextPutBlock = false;
        @SuppressWarnings("unchecked")
        final T[] items = (T[]) new Object[BLOCK_SIZE];
        int nextPutBlockIndex = 0;
        int nextTakeBlockIndex = 0;
        
        
        private Block(int index) {
            this.index = index;
        }
    }
    
    // stored separate from blocks.size, because this informs the creation of new blocks, rather than their storage
    private int nextBlockIndex = 0;
    private final ReferenceList<Block<T>> blocks = new ReferenceArrayList<>();
    
    private final AtomicLong lastAcquiredPutIndex = new AtomicLong();
    private final AtomicLong lastFinishedPutIndex = new AtomicLong();
    private final AtomicLong lastAcquiredTakeIndex = new AtomicLong();
    private final AtomicLong lastFinishedTakeIndex = new AtomicLong();
    private final AtomicLong items = new AtomicLong();
    
    public ResizingRingBuffer() {
        this(0);
    }
    
    public ResizingRingBuffer(int minimumInitialCapacity) {
        // round up to next multiple of block size
        final var initialCapacity = minimumInitialCapacity <= 0 ? BLOCK_SIZE : (minimumInitialCapacity + (BLOCK_SIZE - 1)) & (~BLOCK_SIZE);
        // need at least three blocks to ensure that prevBlock != thisBlock != nextBlock, so adding to the chain works correctly
        final var initialBlockCount = Math.max(initialCapacity >> BLOCK_ID_BITSHIFT, 3);
        for (int i = 0; i < initialBlockCount; i++) {
            final var block = new Block<T>(i);
            block.newBlock = false;
            block.nextPutBlockIndex = (i + 1) % initialBlockCount;
            block.nextTakeBlockIndex = block.nextPutBlockIndex;
            blocks.add(block);
            nextBlockIndex++;
        }
    }
    
    @ThreadSafety.Many
    public void put(T item) {
        long lastIndex = -1;
        long putIndex = -1;
        while (putIndex == -1) {
            final long lastPutIndex = lastAcquiredPutIndex.get();
            final int lastPutBlockIndex = (int) ((lastPutIndex >> 32) & Integer.MAX_VALUE);
            final int lastPutSubBlockIndex = (int) ((lastPutIndex) & Integer.MAX_VALUE);
            // if we are "eating" the tail, but there aren't any items, that instead means the tail caught back up to the head
            // it is safe to advance anyway
            // if this is still marked as a new block, and it is full, we can't advance to its next block, the tail still hasn't gotten to it yet, a new one must be allocated instead
            if ((lastPutIndex == lastFinishedTakeIndex.get() && items.get() != 0) || (blocks.get(lastPutBlockIndex).newBlock && lastPutSubBlockIndex == BLOCK_LAST_INDEX)) {
                // head caught up to tail, allocate a new block
                final var expectedIndex = this.nextBlockIndex;
                synchronized (this) {
                    if (blocks.size() > expectedIndex) {
                        // block was already allocated by another thread
                        // attempt normal index acquisition again
                        continue;
                    }
                    // first thread to try and allocate this index, actually allocate a new block
                    
                    assert expectedIndex == blocks.size();
                    final var newBlock = new Block<T>(expectedIndex);
                    blocks.add(newBlock);
                    // always start at the beginning of a new block
                    final var newIndex = ((long) newBlock.index << 32);
                    putIndex = newIndex;
                    
                    // the take position could advance and move us out of the block that being attached in front of
                    // to handle that case, attempt to update the put position and attach in front of the (now previous) put block
                    while (true) {
                        lastIndex = lastAcquiredPutIndex.get();
                        final var lastBlock = (int) ((lastIndex >> 32) & Integer.MAX_VALUE);
                        final var prevBlock = blocks.get(lastBlock);
                        // allocation is only ever done from put
                        final var nextBlock = blocks.get(prevBlock.nextPutBlockIndex);
                        
                        // orphan entrance to the loop
                        newBlock.nextPutBlockIndex = nextBlock.index;
                        newBlock.nextTakeBlockIndex = nextBlock.index;
                        // attempt to set the put index into that entrance;
                        final var actualLastIndex = lastAcquiredPutIndex.compareAndExchange(lastIndex, newIndex);
                        if (actualLastIndex == lastIndex) {
                            // success, set the prev block's next to the new block
                            //
                            // concurrency note on the potential for lapping before this attach fully finishes
                            // its impossible for the put position to fully lap the ring buffer
                            // lastAcquiredTakeIndex will not advance past lastFinishedPutIndex,
                            // lastFinishedTakeIndex will not advance past lastAcquiredTakeIndex
                            // lastAcquiredPutIndex will not advance past lastFinishedTakeIndex,
                            // this would require > BLOCK_SIZE threads to be attempting to add stuff at the same time (as they cant exit until finishing, and finishing is in-order)
                            // in practice, even this situation is impossible, but would still resolve itself next this thread is scheduled
                            prevBlock.nextPutBlockIndex = newBlock.index;
                            prevBlock.unmarkNextPutBlock = true;
                            if (prevBlock.newBlock) {
                                newBlock.nextTakeBlockIndex = prevBlock.nextTakeBlockIndex;
                                prevBlock.nextTakeBlockIndex = newBlock.index;
                            }
                            // position was actually acquired, return it so that it can be used
                            nextBlockIndex++;
                            break;
                        }
                        // put index changed, try again
                    }
                }
                // if allocation succeeds this put index will have a valid index
                // if its fails on this thread, it shouldn't be necessary it succeeded on another and acquisition will be possible again
                continue;
            }
            long nextPutIndex = lastPutIndex + 1;
            if (lastPutSubBlockIndex == BLOCK_LAST_INDEX) {
                // end of block, get next one
                final var lastBlock = blocks.get(lastPutBlockIndex);
                final var nextBlock = blocks.get(lastBlock.nextPutBlockIndex);
                nextPutIndex = (long) nextBlock.index << 32;
            }
            if (lastAcquiredPutIndex.compareAndExchange(lastPutIndex, nextPutIndex) == lastPutIndex) {
                putIndex = nextPutIndex;
                lastIndex = lastPutIndex;
                break;
            }
        }
        // put index acquired, set the item
        final int currentPutBlockIndex = (int) ((putIndex >> 32) & Integer.MAX_VALUE);
        final int currentPutSubBlockIndex = (int) ((putIndex) & Integer.MAX_VALUE);
        final var putBlock = blocks.get(currentPutBlockIndex);
        if (putBlock.items[currentPutSubBlockIndex] != null) {
            throw new IllegalStateException("Attempt to overwrite position in ring buffer, THIS IS A BUG");
        }
        putBlock.items[currentPutSubBlockIndex] = item;
        final var itemCount = items.incrementAndGet();
        assert itemCount <= (long) blocks.size() * BLOCK_SIZE;
        // finish the index to allow the take thread to pick it up
        while (true) {
            if (lastFinishedPutIndex.compareAndExchange(lastIndex, putIndex) == lastIndex) {
                break;
            }
        }
    }
    
    @Nullable
    @ThreadSafety.Many
    public T poll() {
        if (items.get() == 0) {
            // no items, early out
            // other checks would catch this condition as well
            return null;
        }
        // there is (or was) an item, attempt to acquire its index
        boolean updateBlockTakeIndex = false;
        long lastTakeIndex = -1;
        long takeIndex = -1;
        while (true) {
            updateBlockTakeIndex = false;
            lastTakeIndex = lastAcquiredTakeIndex.get();
            if (lastTakeIndex == lastFinishedPutIndex.get()) {
                // caught up to finished head, no items can be read yet
                return null;
            }
            final int lastTakeBlockIndex = (int) ((lastTakeIndex >> 32) & Integer.MAX_VALUE);
            final int lastTakeSubBlockIndex = (int) ((lastTakeIndex) & Integer.MAX_VALUE);
            takeIndex = lastTakeIndex + 1;
            if (lastTakeSubBlockIndex == BLOCK_LAST_INDEX) {
                // last index, will advance to the next take block
                final var lastTakeBlock = blocks.get(lastTakeBlockIndex);
                takeIndex = (long) lastTakeBlock.nextTakeBlockIndex << 32;
                updateBlockTakeIndex = lastTakeBlock.nextTakeBlockIndex != lastTakeBlock.nextPutBlockIndex;
            }
            if (lastAcquiredTakeIndex.compareAndExchange(lastTakeIndex, takeIndex) == lastTakeIndex) {
                break;
            }
        }
        if (updateBlockTakeIndex) {
            // this index acquire advanced the block
            // update it for next time around the ring
            final int lastTakeBlockIndex = (int) ((lastTakeIndex >> 32) & Integer.MAX_VALUE);
            final var lastTakeBlock = blocks.get(lastTakeBlockIndex);
            // unmark next put block as new, this allows its next put chain to be followed again instead of forcing new block allocations
            if (lastTakeBlock.unmarkNextPutBlock) {
                // lock with allocation, so a new block cant be attached while we are unsetting the newblock requirement
                synchronized (this) {
                    lastTakeBlock.unmarkNextPutBlock = false;
                    var nextUnsetBlockIndex = lastTakeBlock.nextPutBlockIndex;
                    // unset the entire chain until we get to the block we are advancing too
                    do {
                        final var unsetBlock = blocks.get(nextUnsetBlockIndex);
                        unsetBlock.newBlock = false;
                        nextUnsetBlockIndex = unsetBlock.nextPutBlockIndex;
                    } while (nextUnsetBlockIndex != lastTakeBlock.nextTakeBlockIndex);
                }
            }
            lastTakeBlock.nextTakeBlockIndex = lastTakeBlock.nextPutBlockIndex;
        }
        final int takeBlockIndex = (int) ((takeIndex >> 32) & Integer.MAX_VALUE);
        final int takeSubBlockIndex = (int) ((takeIndex) & Integer.MAX_VALUE);
        final var takeBlock = blocks.get(takeBlockIndex);
        @Nullable final var toReturn = takeBlock.items[takeSubBlockIndex];
        takeBlock.items[takeSubBlockIndex] = null;
        if (toReturn != null) {
            // null items cant be counted against the counter, bubbles in the ring can exist for many reasons
            items.decrementAndGet();
        }
        while (true) {
            if (lastFinishedTakeIndex.compareAndExchange(lastTakeIndex, takeIndex) == lastTakeIndex) {
                break;
            }
        }
        return toReturn;
    }
    
    @ThreadSafety.Many
    public boolean empty() {
        return items.get() == 0;
    }
}
