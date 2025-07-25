package graphics.cinnabar.api.threading;

import graphics.cinnabar.lib.threading.QueueSystem;

public class WorkQueueBootstrapper {
    public static void bootstrap() {
        IWorkQueue.Bootstrapper.MAIN_THREAD = QueueSystem.createMainThreadQueue();
        IWorkQueue.Bootstrapper.BACKGROUND_CLEANUP = QueueSystem.createCleanupThreadQueue();
        IWorkQueue.Bootstrapper.BACKGROUND_THREADS = QueueSystem.createBackgroundThreadsQueue();
        IWorkQueue.Bootstrapper.AFTER_END_OF_GPU_FRAME = QueueSystem.createCleanupThreadQueue();
        
        QueueSystem.startThreads();
    }
}
