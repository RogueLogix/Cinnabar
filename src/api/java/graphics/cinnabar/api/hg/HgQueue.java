package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.annotations.ThreadSafety;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.RandomAccess;

@ApiStatus.NonExtendable
public interface HgQueue extends HgObject {
    
    @ThreadSafety.Many
    Submission submit();
    
    @ThreadSafety.Many
    default void submit(Item... items) {
        try (final var submission = submit()) {
            submission.enqueue(items);
        }
    }
    
    @ThreadSafety.Many
    default void submit(List<Item> items) {
        try (final var submission = submit()) {
            submission.enqueue(items);
        }
    }
    
    @ThreadSafety.Many
    HgCommandBuffer.Pool createCommandPool(boolean commandBufferReset, boolean oneTimeSubmit);
    
    boolean needsOwnershipTransfer(HgQueue otherQueue);
    
    enum Type {
        GRAPHICS,
        COMPUTE,
        TRANSFER,
    }
    
    @ApiStatus.NonExtendable
    interface Submission extends AutoCloseable {
        
        @ThreadSafety.Any
        void wait(HgSemaphore semaphore, long value, long stages);
        
        @ThreadSafety.Any
        void execute(HgCommandBuffer commandBuffer);
        
        @ThreadSafety.Any
        void execute(HgCommandBuffer... commandBuffers);
        
        @ThreadSafety.Any
        void execute(List<HgCommandBuffer> commandBuffers);
        
        @ThreadSafety.Any
        void signal(HgSemaphore semaphore, long value, long stages);
        
        @ThreadSafety.Any
        default void enqueue(Item item) {
            switch (item.type) {
                case WAIT -> {
                    assert item.semaphore != null;
                    wait(item.semaphore, item.semaphoreValue, item.stages());
                }
                case EXECUTE -> {
                    assert item.commandBuffer != null;
                    execute(item.commandBuffer);
                }
                case SIGNAL -> {
                    assert item.semaphore != null;
                    signal(item.semaphore, item.semaphoreValue, item.stages());
                }
            }
        }
        
        @ThreadSafety.Any
        default void enqueue(Item... items) {
            for (int i = 0; i < items.length; i++) {
                enqueue(items[i]);
            }
        }
        
        @ThreadSafety.Any
        default void enqueue(List<Item> items) {
            if (items instanceof RandomAccess) {
                final var size = items.size();
                for (int i = 0; i < size; i++) {
                    enqueue(items.get(i));
                }
            } else {
                for (final var item : items) {
                    enqueue(item);
                }
            }
        }
        
        @Override
        @ThreadSafety.Any
        void close();
    }
    
    record Item(
            Type type,
            @Nullable
            HgSemaphore semaphore,
            long semaphoreValue,
            long stages,
            @Nullable
            HgCommandBuffer commandBuffer
    ) {
        @ApiStatus.Internal
        public Item {
        }
        
        @ThreadSafety.Many
        
        public static Item wait(HgSemaphore semaphore, long value, long stages) {
            return new Item(Type.WAIT, semaphore, value, stages, null);
        }
        
        @ThreadSafety.Many
        public static Item execute(HgCommandBuffer commandBuffer) {
            return new Item(Type.WAIT, null, 0, 0, commandBuffer);
        }
        
        @ThreadSafety.Many
        public static Item signal(HgSemaphore semaphore, long value, long stages) {
            return new Item(Type.SIGNAL, semaphore, value, stages, null);
        }
        
        @ApiStatus.Internal
        enum Type {
            WAIT,
            EXECUTE,
            SIGNAL,
        }
    }
}
