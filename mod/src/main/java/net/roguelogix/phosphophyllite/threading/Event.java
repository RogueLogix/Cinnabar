package net.roguelogix.phosphophyllite.threading;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@NonnullDefault
public class Event {
    public static final Event TRIGGERED = new Event();
    
    static {
        TRIGGERED.trigger();
    }
    
    @Nullable
    private ReferenceArrayList<Runnable> callbacks = null;
    @Nullable
    private Runnable callback = null;
    private volatile boolean wasTriggered = false;
    
    public boolean ready() {
        return wasTriggered;
    }
    
    public void join() {
        if (wasTriggered) {
            return;
        }
        synchronized (this) {
            if (wasTriggered) {
                return;
            }
            try {
                wait();
            } catch (InterruptedException ignored) {
            }
        }
    }
    
    public boolean join(int timeout) {
        if (wasTriggered) {
            return true;
        }
        synchronized (this) {
            if (wasTriggered) {
                return true;
            }
            try {
                wait(timeout);
            } catch (InterruptedException ignored) {
            }
        }
        
        return wasTriggered;
    }
    
    public synchronized void trigger() {
        if (wasTriggered) {
            return;
        }
        if (callbacks != null) {
            callbacks.forEach(Runnable::run);
        } else if (callback != null) {
            callback.run();
        }
        wasTriggered = true;
        notifyAll();
    }
    
    public synchronized void registerCallback(Runnable runnable) {
        if (wasTriggered) {
            runnable.run();
            return;
        }
        // often, there will be *exactly* one callback
        // to avoid allocating an object to allocate an object to hold a single reference, just directly hold that reference
        if (callback != null) {
            if (callbacks == null) {
                callbacks = new ReferenceArrayList<>(2);
                callbacks.add(callback);
            }
            callbacks.add(runnable);
        } else {
            callback = runnable;
        }
    }
    
    @SuppressWarnings("removal")
    @Override
    protected void finalize() {
        trigger();
    }
    
    public static Event aggregate(List<@Nullable Event> waitEvents) {
        if (waitEvents.size() == 1) {
            @Nullable final var singleEvent = waitEvents.get(0);
            if (singleEvent == null) {
                return TRIGGERED;
            }
            return singleEvent;
        }
        boolean allReady = true;
        for (int i = 0; i < waitEvents.size(); i++) {
            @Nullable final var event = waitEvents.get(i);
            if (event != null && !event.ready()) {
                allReady = false;
                break;
            }
        }
        // all non-null source events are already ready, dont wait on or create anything, just return TRIGGERED
        if (allReady) {
            return TRIGGERED;
        }
        final var readyEvent = new Event();
        final AtomicLong unTriggeredWaitEvents = new AtomicLong(Long.MAX_VALUE);
        unTriggeredWaitEvents.set(waitEvents.size());
        final Runnable callback = () -> {
            if (unTriggeredWaitEvents.decrementAndGet() == 0) {
                readyEvent.trigger();
            }
        };
        for (@Nullable final var event : waitEvents) {
            if (event == null) {
                callback.run();
                continue;
            }
            event.registerCallback(callback);
        }
        return readyEvent;
    }
}
