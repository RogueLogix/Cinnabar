package graphics.cinnabar.api.threading;

import graphics.cinnabar.api.annotations.Internal;
import org.jetbrains.annotations.Nullable;


@Internal
public class ThreadIndexRegistry {
    
    // THE main thread, mostly just to name it
    public static final int mainThreadCount = 1;
    // reserve one for the render thread, server thread, and OS
    // granted, i cant control other mods making threads too
    public static final int backgroundWorkThreadCount = Math.max(0, Runtime.getRuntime().availableProcessors() - 3);
    // a single cleaner thread should be more than enough, this is where queued destroys will be called from
    public static final int backgroundCleanerThreadCount = 1;
    public static final int totalThreads = mainThreadCount + backgroundWorkThreadCount + backgroundCleanerThreadCount;
    
    static {
        ThreadIndex.Bootstrapper.threadCount = totalThreads;
        ThreadIndex.registryLookupFunc = ThreadIndexRegistry::currentThreadIndex;
    }
    
    private static final ThreadLocal<@Nullable ThreadIndex> threadIndexThreadLocal = new ThreadLocal<>();
    private static int nextVal = 0;
    
    public static ThreadIndex currentThreadIndex() {
        @Nullable final var threadIndex = threadIndexThreadLocal.get();
        if(threadIndex == null){
            return ThreadIndex.INVALID;
        }
        return threadIndex;
    }
    
    public static synchronized ThreadIndex registerThisThread() {
        if (nextVal == totalThreads){
            throw new IllegalStateException("Too many threads attempted to register a threadIndex");
        }
        final var index = new ThreadIndex(nextVal++);
        threadIndexThreadLocal.set(index);
        return index;
    }
}
