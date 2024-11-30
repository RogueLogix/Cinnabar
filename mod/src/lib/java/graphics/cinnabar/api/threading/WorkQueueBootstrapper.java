package graphics.cinnabar.api.threading;

import graphics.cinnabar.lib.threading.CleanupThread;
import graphics.cinnabar.lib.threading.WorkQueue;

public class WorkQueueBootstrapper {
    public static void bootstrap() {
        CleanupThread.startup();
        IWorkQueue.Bootstrapper.MAIN_THREAD = new WorkQueue.SingleThread();
        IWorkQueue.Bootstrapper.BACKGROUND_THREADS = new WorkQueue.BackgroundThreads(ThreadIndexRegistry.backgroundWorkThreadCount);
        IWorkQueue.Bootstrapper.AFTER_END_OF_CPU_FRAME = CleanupThread.EndOfCPUFrameQueue;
        IWorkQueue.Bootstrapper.AFTER_END_OF_GPU_FRAME = CleanupThread.EndOfGPUFrameQueue;
    }
}
