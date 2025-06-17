package graphics.cinnabar.lib.threading;

import graphics.cinnabar.api.threading.ThreadIndex;
import graphics.cinnabar.api.threading.ThreadIndexRegistry;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceLongImmutablePair;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;

import static org.lwjgl.vulkan.VK12.*;

public final class QueueSystem {
    private static int nextMainThreadQueue = 0;
    private static final ReferenceArrayList<WorkQueue.SingleThread> mainThreadQueues = new ReferenceArrayList<>();
    private static int nextCleanupQueue = 0;
    private static final ReferenceArrayList<WorkQueue.SingleThread> cleanupThreadQueues = new ReferenceArrayList<>();
    private static int nextBackgroundQueue = 0;
    private static final ReferenceArrayList<WorkQueue.MultiThreaded> backgroundQueues = new ReferenceArrayList<>();
    private static final ReferenceArrayList<ReferenceLongImmutablePair<VulkanSemaphore>> vkSemaphores = new ReferenceArrayList<>();
    
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
    
    public static void wakeThreadsOnSinal(VulkanSemaphore semaphore, long value) {
        synchronized (vkSemaphores) {
            vkSemaphores.add(new ReferenceLongImmutablePair<>(semaphore, value));
            vkSemaphores.notify();
        }
    }
    
    public static void vkSemaphoreWaitThread() {
        try {
            
            while (true) {
                tryVulkanWait:
                try (final var stack = MemoryStack.stackPush()) {
                    final var count = vkSemaphores.size();
                    if (count == 0) {
                        break tryVulkanWait;
                    }
                    final var vkDevice = vkSemaphores.getFirst().first().device();
                    
                    final var handles = stack.callocLong(count);
                    final var values = stack.callocLong(count);
                    for (int i = 0; i < count; i++) {
                        final var semaphore = vkSemaphores.get(i);
                        if (vkDevice != semaphore.left().device()) {
                            System.exit(-1);
                        }
                        handles.put(i, semaphore.key().handle());
                        values.put(i, semaphore.valueLong());
                    }
                    
                    final var waitInfo = VkSemaphoreWaitInfo.calloc(stack).sType$Default();
                    waitInfo.flags(VK_SEMAPHORE_WAIT_ANY_BIT);
                    waitInfo.semaphoreCount(count);
                    waitInfo.pSemaphores(handles);
                    waitInfo.pValues(values);
                    vkWaitSemaphores(vkDevice, waitInfo, -1);
                    
                    wakeWorkers(-1);
                    wakeCleanupThread(-1);
                    
                    synchronized (vkSemaphores) {
                        final var valuePtr = stack.longs(0);
                        for (int i = 0; i < vkSemaphores.size(); i++) {
                            final var pair = vkSemaphores.get(i);
                            vkGetSemaphoreCounterValue(vkDevice, pair.key().handle(), valuePtr);
                            if (pair.valueLong() <= valuePtr.get(0)) {
                                // this semaphore signaled, remove it from the list
                                vkSemaphores.remove(i);
                                i--;
                            }
                        }
                    }
                }
                synchronized (vkSemaphores) {
                    try {
                        vkSemaphores.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
