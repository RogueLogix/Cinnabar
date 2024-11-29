package graphics.cinnabar.lib.threading;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.threading.IWorkQueue;
import graphics.cinnabar.lib.datastructures.ResizingRingBuffer;

import java.util.concurrent.atomic.AtomicLong;

public abstract class WorkQueue implements IWorkQueue {
    
    private final ResizingRingBuffer<Work> workRing = new ResizingRingBuffer<>(0);
    private final AtomicLong enqueueingThreads = new AtomicLong();
    
    @API
    @Override
    @ThreadSafety.Many
    public void beginEnqueueing() {
        enqueueingThreads.getAndIncrement();
    }
    
    @API
    @Override
    @ThreadSafety.Many
    public void endEnqueueing() {
        enqueueingThreads.getAndDecrement();
        
    }
    
    @API
    @Override
    @ThreadSafety.Many
    public void enqueue(Work work) {
        workRing.put(work);
    }
    
    @API
    @Override
    @ThreadSafety.Many
    public ICounter createCounter() {
        return new Counter(this);
    }
    
    @Internal
    @ThreadSafety.Many
    abstract void wakeThreads(boolean all);
    
    public static class MainThread extends WorkQueue {
        
        @ThreadSafety.Many
        void wakeThreads(boolean all) {
        }
    }
    
    public static class BackgroundThreads extends WorkQueue {
        
        public BackgroundThreads(int threadCount) {
        
        }
        
        void threadFunc() {
        
        }
        
        @ThreadSafety.Many
        void wakeThreads(boolean all) {
        }
    }
    
    public static class Cleanup extends WorkQueue {
        
        public Cleanup() {
        
        }
        
        @ThreadSafety.Many
        void wakeThreads(boolean all) {
        }
    }
}
