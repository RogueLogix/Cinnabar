package graphics.cinnabar.api.threading;

import graphics.cinnabar.lib.threading.WorkQueue;

public class WorkQueueBootstrapper {
    public static void bootstrap() {
        IWorkQueue.Bootstrapper.MAIN_THREAD = new WorkQueue.MainThread();
        IWorkQueue.Bootstrapper.BACKGROUND_THREADS = new WorkQueue.BackgroundThreads(ThreadIndexRegistry.backgroundWorkThreadCount);
        IWorkQueue.Bootstrapper.AFTER_END_OF_CPU_FRAME = new WorkQueue.Cleanup();
        IWorkQueue.Bootstrapper.AFTER_END_OF_GPU_FRAME = new WorkQueue.Cleanup();
    }
}
