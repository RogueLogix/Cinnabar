package graphics.cinnabar.api.memory;

import graphics.cinnabar.api.CinnabarAPI;
import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import static graphics.cinnabar.api.CinnabarAPI.Internals.CINNABAR_API_LOG;

public class LeakDetection {
    
    private static class MemoryLeak extends Exception {
        private final PointerWrapper allocated;
        
        private MemoryLeak(PointerWrapper allocated) {
            super("ptr: " + allocated.pointer() + " size: " + allocated.size());
            this.allocated = allocated;
        }
    }
    
    private static final Long2ObjectMap<MemoryLeak> liveAllocations = new Long2ObjectOpenHashMap<>();
    
    @Internal
    static PointerWrapper trackPointer(PointerWrapper wrapper) {
        if (!CinnabarAPI.DEBUG_MODE) {
            return wrapper;
        }
        synchronized (liveAllocations) {
            liveAllocations.put(wrapper.pointer(), new MemoryLeak(wrapper));
        }
        addAccessibleLocation(wrapper);
        return wrapper;
    }
    
    @Internal
    static void untrackPointer(PointerWrapper wrapper) {
        if (!CinnabarAPI.DEBUG_MODE) {
            return;
        }
        synchronized (liveAllocations) {
            if (liveAllocations.remove(wrapper.pointer()) == null) {
                for (final var value : liveAllocations.values()) {
                    if (value.allocated.contains(wrapper)) {
                        throw new IllegalStateException("Attempt to free sub pointer, source pointer originally allocated at cause", value);
                    }
                }
                throw new IllegalStateException("Attempt to free pointer not currently live, potential double free?");
            }
        }
        removeAccessibleLocation(wrapper);
    }
    
    static {
        if (CinnabarAPI.DEBUG_MODE) {
            Runtime.getRuntime().addShutdownHook(new Thread(LeakDetection::logLeakedMemory));
        }
    }
    
    @Internal
    private static void logLeakedMemory() {
        if (CinnabarAPI.DEBUG_MODE) {
            System.out.println("Logging memory leaks");
            for (final var value : liveAllocations.values()) {
                CINNABAR_API_LOG.warn(value.getMessage());
            }
            System.out.println("Memory leaks logged");
        }
    }
    
    private static final ObjectArraySet<PointerWrapper> validReadLocations = new ObjectArraySet<>();
    private static final ReentrantReadWriteLock readLocationsLock = new ReentrantReadWriteLock();
    private static final ObjectArraySet<PointerWrapper> validWriteLocations = new ObjectArraySet<>();
    private static final ReentrantReadWriteLock writeLocationsLock = new ReentrantReadWriteLock();
    
    @API
    public static void addAccessibleLocation(PointerWrapper wrapper) {
        if (!CinnabarAPI.DEBUG_MODE) {
            return;
        }
        addReadableLocation(wrapper);
        addWritableLocation(wrapper);
    }
    
    @API
    public static void removeAccessibleLocation(PointerWrapper wrapper) {
        if (!CinnabarAPI.DEBUG_MODE) {
            return;
        }
        removeReadableLocation(wrapper);
        removeWritableLocation(wrapper);
    }
    
    @API
    public static void addReadableLocation(PointerWrapper wrapper) {
        if (!CinnabarAPI.DEBUG_MODE) {
            return;
        }
        
        final var lock = readLocationsLock.writeLock();
        lock.lock();
        try {
            validReadLocations.add(wrapper);
        } finally {
            lock.unlock();
        }
    }
    
    @API
    public static void removeReadableLocation(PointerWrapper wrapper) {
        if (!CinnabarAPI.DEBUG_MODE) {
            return;
        }
        final var lock = readLocationsLock.writeLock();
        lock.lock();
        try {
            validReadLocations.remove(wrapper);
        } finally {
            lock.unlock();
        }
    }
    
    @API
    public static void addWritableLocation(PointerWrapper wrapper) {
        if (!CinnabarAPI.DEBUG_MODE) {
            return;
        }
        
        final var lock = writeLocationsLock.writeLock();
        lock.lock();
        try {
            validWriteLocations.add(wrapper);
        } finally {
            lock.unlock();
        }
    }
    
    @API
    public static void removeWritableLocation(PointerWrapper wrapper) {
        if (!CinnabarAPI.DEBUG_MODE) {
            return;
        }
        final var lock = writeLocationsLock.writeLock();
        lock.lock();
        try {
            validWriteLocations.remove(wrapper);
        } finally {
            lock.unlock();
        }
    }
    
    @API
    public static void verifyCanAccessLocation(long ptr, long size, boolean read) {
        if (!CinnabarAPI.DEBUG_MODE) {
            return;
        }
        final var validLocations = read ? validReadLocations : validWriteLocations;
        final var lock = read ? readLocationsLock.readLock() : writeLocationsLock.readLock();
        lock.lock();
        try {
            for (final var value : validLocations) {
                // doesnt start in this range
                if (value.pointer() > ptr || value.pointer() + value.size() <= ptr) {
                    continue;
                }
                if (value.pointer() + value.size() < ptr + size) {
                    throw new IllegalAccessError("Attempt to access past end of native buffer");
                }
                // starts in a known range, and doesnt attempt to access past the end, its valid
                return;
            }
        } finally {
            lock.unlock();
        }
        throw new IllegalAccessError("Unable to find a valid location for attempted access " + (read ? "(reading)" : "(writing)"));
    }
}
