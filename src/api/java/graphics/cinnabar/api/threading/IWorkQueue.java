package graphics.cinnabar.api.threading;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import graphics.cinnabar.api.annotations.ThreadSafety;

import java.util.List;
import java.util.function.Consumer;

@API
public interface IWorkQueue {
    
    @API(note = """
            Work and callbacks will always only ever be called by the main thread
            FIFO execution order as its inherently single threaded
            no guarantee that execution will be this frame
            """)
    IWorkQueue MAIN_THREAD = Bootstrapper.MAIN_THREAD;
    
    @API(note = """
            Work and callbacks may run on any thread including the main and cleanup thread
            May be the same queue as MAIN_THREAD
            Work is started in order, but may complete out of order
            """)
    IWorkQueue BACKGROUND_THREADS = Bootstrapper.BACKGROUND_THREADS;
    
    @API(note = """
            Work and callbacks will run from cleanup thread
            May be run immediately, will be run before AFTER_END_OF_CPU_FRAME
            """)
    IWorkQueue BACKGROUND_CLEANUP = Bootstrapper.BACKGROUND_CLEANUP;
    
    @API(note = """
            Work and callbacks will run from cleanup thread
            Anything enqueued this CPU frame will be run after the CPU has finished the current frame
            
            CPU frame end is defined as right after the vkQueuePresent call is made (or skipped, as that can happen)
            """)
    IWorkQueue AFTER_END_OF_CPU_FRAME = Bootstrapper.AFTER_END_OF_CPU_FRAME;
    
    @API(note = """
            Work and callbacks will run from cleanup thread
            Anything enqueued this GPU frame will be run after the GPU has finished the current frame
            Any work enqueued for end of the same CPU frame will be executed first
            
            GPU frame end is defined as after the GPU signals the semaphore for present, but does not include the present itself
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
    
    void wait(ISemaphore semaphore, long value);
    
    void enqueue(Work work);
    
    @API(note = "For best performance, use a random access list (ArrayList)")
    void enqueue(List<Work> work);
    
    void signal(ISemaphore semaphore, long value);
    
    @Internal
    @SuppressWarnings("DataFlowIssue")
    class Bootstrapper {
        static IWorkQueue MAIN_THREAD = null;
        static IWorkQueue BACKGROUND_CLEANUP = null;
        static IWorkQueue BACKGROUND_THREADS = null;
        static IWorkQueue AFTER_END_OF_CPU_FRAME = null;
        static IWorkQueue AFTER_END_OF_GPU_FRAME = null;
    }
}
