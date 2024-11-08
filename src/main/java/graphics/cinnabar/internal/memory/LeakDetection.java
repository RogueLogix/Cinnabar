package graphics.cinnabar.internal.memory;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import static graphics.cinnabar.internal.CinnabarDebug.DEBUG;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_MEMORY_WRITE_BIT;

public class LeakDetection {
    
    private static class MemoryLeak extends Exception {
        private final PointerWrapper allocated;
        
        private MemoryLeak(PointerWrapper allocated) {
            super("ptr: " + allocated.pointer() + " size: " + allocated.size());
            this.allocated = allocated;
        }
    }
    
    private static final Long2ObjectMap<MemoryLeak> liveAllocations = new Long2ObjectOpenHashMap<>();
    
    static PointerWrapper trackPointer(PointerWrapper wrapper) {
        if (!DEBUG) {
            return wrapper;
        }
        synchronized (liveAllocations) {
            liveAllocations.put(wrapper.pointer(), new MemoryLeak(wrapper));
        }
        addAccessibleLocation(wrapper);
        return wrapper;
    }
    
    static void untrackPointer(PointerWrapper wrapper) {
        if (!DEBUG) {
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
        Runtime.getRuntime().addShutdownHook(new Thread(LeakDetection::logLeakedMemory));
    }
    
    public static void logLeakedMemory() {
        if (DEBUG) {
            System.out.println("Logging memory leaks");
            for (final var value : liveAllocations.values()) {
                value.printStackTrace();
            }
            System.out.println("Memory leaks logged");
        }
    }
    
    private static final ObjectArraySet<PointerWrapper> validReadLocations = new ObjectArraySet<>();
    private static final ReentrantReadWriteLock readLocationsLock = new ReentrantReadWriteLock();
    private static final ObjectArraySet<PointerWrapper> validWriteLocations = new ObjectArraySet<>();
    private static final ReentrantReadWriteLock writeLocationsLock = new ReentrantReadWriteLock();
    private static final ObjectArraySet<ObjectIntImmutablePair<PointerWrapper>> specificUseFlags = new ObjectArraySet<>();
    private static final ReentrantReadWriteLock specificUseLock = new ReentrantReadWriteLock();
    
    
    public static void addAccessibleLocation(PointerWrapper wrapper) {
        if (!DEBUG) {
            return;
        }
        addReadableLocation(wrapper);
        addWritableLocation(wrapper);
    }
    
    public static void removeAccessibleLocation(PointerWrapper wrapper) {
        if (!DEBUG) {
            return;
        }
        removeReadableLocation(wrapper);
        removeWritableLocation(wrapper);
    }
    
    public static void addReadableLocation(PointerWrapper wrapper) {
        if (!DEBUG) {
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
    
    public static void removeReadableLocation(PointerWrapper wrapper) {
        if (!DEBUG) {
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
    
    
    public static void addWritableLocation(PointerWrapper wrapper) {
        if (!DEBUG) {
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
    
    public static void removeWritableLocation(PointerWrapper wrapper) {
        if (!DEBUG) {
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
    
    public static void setUseFlags(PointerWrapper wrapper, int useFlag) {
        if (!DEBUG) {
            return;
        }
        final var lock = specificUseLock.writeLock();
        lock.lock();
        try {
            specificUseFlags.add(new ObjectIntImmutablePair<>(wrapper, useFlag));
        } finally {
            lock.unlock();
        }
    }
    
    public static void clearUseFlags(PointerWrapper wrapper) {
        if (!DEBUG) {
            return;
        }
        final var lock = specificUseLock.writeLock();
        lock.lock();
        try {
            specificUseFlags.removeIf(a -> a.left().equals(wrapper));
        } finally {
            lock.unlock();
        }
    }
    
    public static void verifyCanAccessLocation(long ptr, long size, boolean read) {
        if (!DEBUG) {
            return;
        }
        final var validLocations = read ? validReadLocations : validWriteLocations;
        final var lock = read ? readLocationsLock.readLock() : writeLocationsLock.readLock();
        // this is fine
        // also, technically a readwrite lock would be better, as this is read only, but *meh*
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
        final var readLock = specificUseLock.readLock();
        readLock.lock();
        try {
            final var accessBit = read ? VK_ACCESS_MEMORY_READ_BIT : VK_ACCESS_MEMORY_WRITE_BIT;
            for (ObjectIntImmutablePair<PointerWrapper> specificUseFlag : specificUseFlags) {
                final var srcPtr = ptr;
                final var dstPtr = specificUseFlag.left().pointer();
                boolean overlaps = false;
                overlaps |= srcPtr < dstPtr && dstPtr < srcPtr + size;
                overlaps |= dstPtr < srcPtr && srcPtr < dstPtr + size;
                if (overlaps) {
                    if ((specificUseFlag.rightInt() & accessBit) == 0) {
                        throw new IllegalAccessError("Attempt to access memory for use not specified.");
                    }
                }
            }
        } finally {
            readLock.unlock();
        }
        throw new IllegalAccessError("Unable to find a valid location for attempted access " + (read ? "(reading)" : "(writing)"));
    }
}
