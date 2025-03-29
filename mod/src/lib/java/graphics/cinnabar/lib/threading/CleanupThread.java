package graphics.cinnabar.lib.threading;

public class CleanupThread {
    
    public static WorkQueue.SingleThread EndOfCPUFrameQueue = new WorkQueue.SingleThread();
    public static WorkQueue.SingleThread EndOfGPUFrameQueue = new WorkQueue.SingleThread();
    
    private static final Thread thread = new Thread(CleanupThread::threadFunc);
    private static boolean running = true;
    private static long lastFinishedCPUFrame = -1;
    private static long lastFinishedGPUFrame = -1;
    
    public static void startup() {
        thread.setDaemon(true);
        thread.start();
//        CINNABAR_EVENT_BUS.addListener(CleanupThread::endOfCPUFrameEndEvent);
//        CINNABAR_EVENT_BUS.addListener(CleanupThread::endOfGPUFrameEndEvent);
    }
    
    public static void shutdown() {
        running = false;
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
    
//    @ThreadSafety.MainGraphics
//    private static void endOfCPUFrameEndEvent(Frame.End.CPU event) {
//        EndOfCPUFrameQueue.insertBreakpoint();
//        lastFinishedCPUFrame = event.frameNum;
//        wakeThread();
//    }
//
//    @ThreadSafety.MainGraphics
//    private static void endOfGPUFrameEndEvent(Frame.End.GPU event) {
//        EndOfGPUFrameQueue.insertBreakpoint();
//        lastFinishedGPUFrame = event.frameNum;
//        wakeThread();
//    }
    
    private static void threadFunc() {
        while (running) {
            final long lastProcessedCPUFrame = lastFinishedCPUFrame;
            EndOfCPUFrameQueue.runUntilBreakpointNumber(lastProcessedCPUFrame);
            // while executing CPU EOF events, another frame may have finished, including GPU frame
            // because CPU events must be processed first, we cannot execute a GPU frame greater than the CPU frame that was just executed
            final var gpuFrameToProcessTo = Math.min(lastProcessedCPUFrame, lastFinishedGPUFrame);
            EndOfCPUFrameQueue.runUntilBreakpointNumber(gpuFrameToProcessTo);
            if(lastFinishedCPUFrame > lastProcessedCPUFrame || lastFinishedGPUFrame > gpuFrameToProcessTo){
                // there is more work to be done immediately, do it
                continue;
            }
            // all work has been finished, wait until the next signal
            threadWait();
        }
    }
    
    private static void wakeThread() {
        synchronized (thread) {
            thread.notify();
        }
    }
    
    private static void threadWait() {
        synchronized (thread) {
            try {
                thread.wait(100);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
