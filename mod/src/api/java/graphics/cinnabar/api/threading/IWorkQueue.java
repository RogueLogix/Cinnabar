package graphics.cinnabar.api.threading;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import graphics.cinnabar.api.annotations.ThreadSafety;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

@API
public interface IWorkQueue {
    
    @API(note = """
            Work and callbacks will always only ever be called by the main thread
            FIFO execution order as its inherently single threaded
            """)
    IWorkQueue MAIN_THREAD = Bootstrapper.MAIN_THREAD;
    
    @API(note = """
            Work and callbacks may run on any thread including the main thread
            May be the same queue as MAIN_THREAD
            Work is started in order, but may complete out of order
            """)
    IWorkQueue BACKGROUND_THREADS = Bootstrapper.BACKGROUND_THREADS;
    
    @API(note = """
            Work and callbacks will run from cleaner thread
            Anything enqueued this CPU frame will be run after the CPU has finished the current frame
            
            CPU frame end is defined as main window present, this runs
            """)
    IWorkQueue AFTER_END_OF_CPU_FRAME = Bootstrapper.AFTER_END_OF_CPU_FRAME;
    
    @API(note = """
            Work and callbacks will run from cleaner thread
            Anything enqueued this GPU frame will be run after the GPU has finished the current frame
            
            GPU frame end is defined as after the GPU signals the present semaphore, and does not include the present itself
            """)
    IWorkQueue AFTER_END_OF_GPU_FRAME = Bootstrapper.AFTER_END_OF_GPU_FRAME;
    
    interface Work extends Consumer<ThreadIndex> {
        @Override
        @ThreadSafety.ItDepends(note = """
                Main thread work is only required to be MainGraphics safe
                Background threads work is required to be Any safe, or Many safe if enqueued multiple times before waiting for completion
                """)
        void accept(ThreadIndex threadIndex);
    }
    
    @API
    @ThreadSafety.Many
    void beginEnqueueing();
    
    @API
    @ThreadSafety.Many
    void endEnqueueing();
    
    @API
    record EnqueueingScope(IWorkQueue queue) implements AutoCloseable{
        
        @API
        @ThreadSafety.Any
        public EnqueueingScope(IWorkQueue queue){
            this.queue = queue;
            queue.beginEnqueueing();
        }
        
        @API
        @ThreadSafety.Many
        public void enqueue(Work work) {
            queue.enqueue(work);
        }
        
        @API
        @ThreadSafety.Any
        @Override
        public void close() throws Exception {
            queue.endEnqueueing();
        }
    }
    
    @API
    @ThreadSafety.Many
    void enqueue(Work work);
    
    @API
    interface ICounter {
        
        @API
        record Item(Work work, ICounter counter) implements Work {
            @Internal
            @Override
            public void accept(ThreadIndex threadIndex) {
                work.accept(threadIndex);
                counter.decrement();
            }
            
            @API
            public void enqueue() {
                counter.enqueue(this);
            }
        }
        
        /**
         * @param callback: callback on work completion, may be used to enqueue more work
         */
        @ThreadSafety.Any
        void setCallback(Work callback);
        
        @API
        @ThreadSafety.Any
        void beginEnqueuing();
        
        @API
        @ThreadSafety.Any
        void endEnqueuing();
        
        @API
        @ThreadSafety.Many
        default void enqueue(Work work) {
            enqueue(new Item(work, this));
        }
        
        @API
        @ThreadSafety.Many
        void enqueue(Item item);
        
        @API
        @ThreadSafety.Many
        boolean isComplete();
        
        @API
        @ThreadSafety.Many
        void waitComplete();
        
        @Internal
        @ThreadSafety.Many
        void decrement();
    }
    
    @API
    @ThreadSafety.Any
    ICounter createCounter();
    
    @Internal
    @SuppressWarnings("DataFlowIssue")
    class Bootstrapper {
        static IWorkQueue MAIN_THREAD = null;
        static IWorkQueue BACKGROUND_THREADS = null;
        static IWorkQueue AFTER_END_OF_CPU_FRAME = null;
        static IWorkQueue AFTER_END_OF_GPU_FRAME = null;
    }
}
