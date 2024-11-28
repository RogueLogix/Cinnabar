package graphics.cinnabar.internal.vulkan.util;

import graphics.cinnabar.internal.CinnabarDebug;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import org.jetbrains.annotations.Nullable;

import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

public class LiveHandles {
    private static final Long2ReferenceMap<@Nullable Exception> liveHandles = new Long2ReferenceOpenHashMap<>();
    private static final Reference2ReferenceMap<Object, @Nullable Exception> liveObjects = new Reference2ReferenceOpenHashMap<>();
    
    
    public static void create(long handle) {
        if (!CinnabarDebug.DEBUG) {
            return;
        }
        createSynced(handle, null);
    }
    
    public static void create(long handle, Exception e) {
        if (!CinnabarDebug.DEBUG) {
            return;
        }
        createSynced(handle, e);
    }
    
    
    private static synchronized void createSynced(long handle, @Nullable Exception e) {
        final var old = liveHandles.put(handle, new RuntimeException(e));
        if(old != null){
            throw new IllegalStateException(old);
        }
    }
    
    public static void create(Object object) {
        if (!CinnabarDebug.DEBUG) {
            return;
        }
        createSynced(object, null);
    }
    
    public static void create(Object object, Exception e) {
        if (!CinnabarDebug.DEBUG) {
            return;
        }
        createSynced(object, e);
    }
    
    private static synchronized void createSynced(Object object, @Nullable Exception e) {
        final var old = liveObjects.put(object, new RuntimeException(e));
        if(old != null){
            throw new IllegalStateException(old);
        }
    }
    
    public static void destroy(long handle) {
        if (!CinnabarDebug.DEBUG) {
            return;
        }
        destroySynced(handle);
    }
    
    private static synchronized void destroySynced(long handle) {
        if(handle == VK_NULL_HANDLE){
            return;
        }
        final var old = liveHandles.remove(handle);
        if (old == null) {
            throw new IllegalStateException("Double free!");
        }
    }
    
    public static void destroy(Object object) {
        if (!CinnabarDebug.DEBUG) {
            return;
        }
        destroySynced(object);
    }
    
    private static synchronized void destroySynced(Object object) {
        final var old = liveObjects.remove(object);
        if (old == null) {
            throw new IllegalStateException("Double free!");
        }
    }
    
    public static void printLeaks() {
        //noinspection CallToPrintStackTrace
        liveObjects.forEach((k, v) -> v.printStackTrace());
        liveHandles.forEach((k, v) -> v.printStackTrace());
    }
}
