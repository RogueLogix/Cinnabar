package graphics.cinnabar.lib.threading;

import graphics.cinnabar.api.hg.HgSemaphore;
import graphics.cinnabar.api.threading.ThreadIndex;
import graphics.cinnabar.api.threading.ThreadIndexRegistry;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

public final class QueueSystem {
    private static int nextMainThreadQueue = 0;
    private static final ReferenceArrayList<WorkQueue.SingleThread> mainThreadQueues = new ReferenceArrayList<>();
    private static int nextCleanupQueue = 0;
    private static final ReferenceArrayList<WorkQueue.SingleThread> cleanupThreadQueues = new ReferenceArrayList<>();
    private static int nextBackgroundQueue = 0;
    private static final ReferenceArrayList<WorkQueue.MultiThreaded> backgroundQueues = new ReferenceArrayList<>();
    private static final ReferenceArrayList<HgSemaphore.Op> hgSemaphores = new ReferenceArrayList<>();
    
    public static WorkQueue createMainThreadQueue() {
        synchronized (mainThreadQueues) {
            final var newQueue = new WorkQueue.SingleThread(ThreadIndex.MAIN, i -> {
            });
            mainThreadQueues.add(newQueue);
            return newQueue;
        }
    }
    
    public static WorkQueue createCleanupThreadQueue() {
        synchronized (cleanupThreadQueues) {
            final var newQueue = new WorkQueue.SingleThread(ThreadIndex.CLEANUP, QueueSystem::wakeCleanupThread);
            cleanupThreadQueues.add(newQueue);
            return newQueue;
        }
    }
    
    public static WorkQueue createBackgroundThreadsQueue() {
        synchronized (backgroundQueues) {
            final var newQueue = new WorkQueue.MultiThreaded(QueueSystem::wakeWorkers);
            backgroundQueues.add(newQueue);
            return newQueue;
        }
    }
    
    // TODO: expose to API, this is to allow a waiting thread to do work
    //       also, maybe return as soon as the semaphore signals, rather than after a queue that had work runs out of work?
    public static void onSemaphoreSpinWait(ThreadIndex threadIndex) {
        if (!threadIndex.valid()) {
            // don't know what thread this is, just loop
            Thread.onSpinWait();
            return;
        }
        switch (threadIndex.index()) {
            case 0 -> {
                for (int i = 0; i < mainThreadQueues.size(); i++) {
                    final var queue = mainThreadQueues.get(nextMainThreadQueue++);
                    nextMainThreadQueue %= mainThreadQueues.size();
                    if (queue.runUntilStalled()) {
                        return;
                    }
                }
                // if all main thread work done, try and run some background work
                for (int i = 0; i < backgroundQueues.size(); i++) {
                    // yes there is a race condition with `nextBackgroundQueue++`, 
                    // it doesn't matter because this is just trying to keep it from always only executing the first one  
                    final var queue = backgroundQueues.get(nextBackgroundQueue++ % backgroundQueues.size());
                    if (queue.runUntilStalled(threadIndex)) {
                        return;
                    }
                }
            }
            case 1 -> {
                for (int i = 0; i < cleanupThreadQueues.size(); i++) {
                    final var queue = cleanupThreadQueues.get(nextCleanupQueue++);
                    nextCleanupQueue %= cleanupThreadQueues.size();
                    if (queue.runUntilStalled()) {
                        return;
                    }
                }
            }
            default -> {
                for (int i = 0; i < backgroundQueues.size(); i++) {
                    // yes there is a race condition with `nextBackgroundQueue++`, 
                    // it doesn't matter because this is just trying to keep it from always only executing the first one  
                    final var queue = backgroundQueues.get(nextBackgroundQueue++ % backgroundQueues.size());
                    if (queue.runUntilStalled(threadIndex)) {
                        return;
                    }
                }
            }
        }
    }
    
    public static void startThreads() {
        ThreadIndexRegistry.registerThisThread();
        
        final var waitThread = new Thread(QueueSystem::vkSemaphoreWaitThread);
        waitThread.setDaemon(true);
        waitThread.setPriority(Thread.MAX_PRIORITY);
        waitThread.setName("CinnabarSemaphoreWait");
        waitThread.start();
        
        final var cleanupThread = new Thread(QueueSystem::cleanupThreadFunc);
        cleanupThread.setDaemon(true);
        cleanupThread.setPriority(7);
        cleanupThread.setName("CinnabarCleanup");
        cleanupThread.start();
        
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        
        for (int i = 0; i < ThreadIndexRegistry.backgroundWorkThreadCount; i++) {
            final var workerThread = new Thread(QueueSystem::workerThreadFunc);
            workerThread.setDaemon(true);
            workerThread.setName("CinnabarWorker" + i);
            workerThread.start();
        }
        
    }
    
    private static void cleanupThreadFunc() {
        try {
            ThreadIndexRegistry.registerThisThread();
            mainLoop:
            while (true) {
                for (int i = 0; i < cleanupThreadQueues.size(); i++) {
                    final var queue = cleanupThreadQueues.get(nextCleanupQueue++);
                    nextCleanupQueue %= cleanupThreadQueues.size();
                    if (queue.runUntilStalled()) {
                        continue mainLoop;
                    }
                }
                
                synchronized (cleanupThreadQueues) {
                    try {
                        cleanupThreadQueues.wait(1);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    public static void wakeCleanupThread(int ignored) {
        synchronized (cleanupThreadQueues) {
            cleanupThreadQueues.notify();
        }
    }
    
    private static void workerThreadFunc() {
        try {
            final var threadIndex = ThreadIndexRegistry.registerThisThread();
            while (true) {
                boolean ranAny = false;
                for (int i = 0; i < backgroundQueues.size(); i++) {
                    final var queue = backgroundQueues.get(i);
                    if (queue.runUntilStalled(threadIndex)) {
                        ranAny = true;
                    }
                }
                if (ranAny) {
                    continue;
                }
                
                synchronized (backgroundQueues) {
                    try {
                        backgroundQueues.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    public static void wakeWorkers(int count) {
        synchronized (backgroundQueues) {
            if (count == 1) {
                backgroundQueues.notify();
            } else {
                backgroundQueues.notifyAll();
            }
        }
    }
    
    public static void wakeThreadsOnSinal(HgSemaphore semaphore, long value) {
        synchronized (hgSemaphores) {
            hgSemaphores.add(new HgSemaphore.Op(semaphore, value));
            hgSemaphores.notify();
        }
    }
    
    public static void vkSemaphoreWaitThread() {
        try {
            
            while (true) {
                final var count = hgSemaphores.size();
                if (count == 0) {
                    synchronized (hgSemaphores) {
                        try {
                            hgSemaphores.wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                    continue;
                }
                final var hgDevice = hgSemaphores.getFirst().semaphore().device();
                
                for (int i = 0; i < count; i++) {
                    final var semaphore = hgSemaphores.get(i);
                    if (hgDevice != semaphore.semaphore().device()) {
                        System.exit(-1);
                    }
                }
                
                hgDevice.waitSemaphores(hgSemaphores, -1, true);
                
                wakeWorkers(-1);
                wakeCleanupThread(-1);
                
                synchronized (hgSemaphores) {
                    for (int i = 0; i < hgSemaphores.size(); i++) {
                        final var op = hgSemaphores.get(i);
                        if (op.value() <= op.semaphore().value()) {
                            // this semaphore signaled, remove it from the list
                            hgSemaphores.remove(i);
                            i--;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
