package net.roguelogix.phosphophyllite.threading;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.roguelogix.phosphophyllite.registry.OnModLoad;

public class Queues {
    // these all have pre-specified runners, so, the thread safety only applies to *adding* work, not running work
    @ThreadSafety.Many
    public static final WorkQueue serverThread;
    @ThreadSafety.Many
    public static final WorkQueue clientThread;
    @ThreadSafety.Many
    public static final WorkQueue backgroundSingleThread;
    @ThreadSafety.Many
    public static final WorkQueue backgroundSingleThread2;
    @Deprecated(forRemoval = true)
    public static final WorkQueue offThread;
    @ThreadSafety.Many
    public static final WorkQueue backgroundMultiThread;
    
    @OnModLoad
    private static void onModLoad() {
    }
    
    static {
        WorkQueue serverThread1 = null;
        WorkQueue clientThread1 = null;
        int threads = Runtime.getRuntime().availableProcessors();
        threads = Math.max(1, threads - 1); // if possible, leave a core for the main server threads
        try {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                threads = Math.max(1, threads - 1); // if possible, leave a core for the main client thread too
                clientThread1 = new WorkQueue(false, true);
            }
            serverThread1 = new WorkQueue(false, true);
        }catch (NoClassDefFoundError ignored){
            // happens when forge isn't loaded
        }
        serverThread = serverThread1;
        clientThread = clientThread1;
        
        // single thread background worker
        // guarantees that multiple work items wont overlap, without requiring event tracking
        backgroundSingleThread = new WorkQueue(false, true);
        backgroundSingleThread.addProcessingThread("Phosphophyllite Background Queue Single Thread Worker 1");
        // for more single-thread capacity
        backgroundSingleThread2 = new WorkQueue(false, true);
        backgroundSingleThread2.addProcessingThread("Phosphophyllite Background Queue Single Thread Worker 2");
        
        // blocking on this queue is ok,
        backgroundMultiThread = new WorkQueue();
        backgroundMultiThread.addProcessingThreads(threads, "Phosphophyllite Background Queue Multi Thread Worker #");
        offThread = backgroundMultiThread;
    }
}
