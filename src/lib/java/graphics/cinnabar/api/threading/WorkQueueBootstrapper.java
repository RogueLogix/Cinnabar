package graphics.cinnabar.api.threading;

import graphics.cinnabar.lib.threading.WorkQueue;

public class WorkQueueBootstrapper {
    public static void bootstrap() {
        IWorkQueue.Bootstrapper.MAIN_THREAD = new WorkQueue.SingleThread();
        IWorkQueue.Bootstrapper.BACKGROUND_THREADS = new WorkQueue.BackgroundThreads(ThreadIndexRegistry.backgroundWorkThreadCount);
    }
}
