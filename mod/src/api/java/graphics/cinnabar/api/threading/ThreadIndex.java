package graphics.cinnabar.api.threading;

import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.exceptions.InvalidThread;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/*
 * Thread block ordering is
 * main render thread, always index 0
 * cleaner thread, always index 1
 * background work threads remainder of indices
 */
@API
public record ThreadIndex(@API(note = "will be in range [0, threadCount)") int index) {
    
    @API
    public static final ThreadIndex MAIN = new ThreadIndex(0);
    
    @API
    public static final ThreadIndex CLEANUP = new ThreadIndex(1);
    
    @Internal
    static final ThreadIndex INVALID = new ThreadIndex(-1);
    
    @API(note = "total number of threads with a registered index, this is decided at startup, will always be at least one")
    public static int threadCount = Bootstrapper.threadCount;
    
    @Internal
    static Supplier<@Nullable ThreadIndex> registryLookupFunc = () -> INVALID;
    
    @Internal
    public ThreadIndex {
    }
    
    @API
    @ThreadSafety.Many
    public static ThreadIndex currentThreadIndex() {
        @Nullable
        final var threadIndex = registryLookupFunc.get();
        if (threadIndex == null) {
            throw new InvalidThread();
        }
        return threadIndex;
    }
    
    @API
    @ThreadSafety.Many
    public boolean isMainThread() {
        return this.index == MAIN.index;
    }
    
    @API
    @ThreadSafety.Many
    public boolean valid() {
        return 0 <= index && index < threadCount;
    }
    
    @Internal
    static class Bootstrapper {
        static int threadCount;
    }
}
