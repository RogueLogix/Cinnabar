package graphics.cinnabar.lib;

import graphics.cinnabar.api.threading.ThreadIndexRegistry;
import graphics.cinnabar.api.threading.WorkQueueBootstrapper;

import static graphics.cinnabar.lib.CinnabarLib.CINNABAR_LIB_LOG;

public class CinnabarLibBootstrapper {
    public static void bootstrap() {
        CINNABAR_LIB_LOG.info("Initializing CinnabarLib");
        final var threadName = Thread.currentThread().getName();
        if (!"Render thread".equals(threadName)) {
            throw new IllegalStateException("Cinnabar can only be bootstrapped from the render thread");
        }
        final var index = ThreadIndexRegistry.registerThisThread();
        if (index.index() != 0) {
            throw new IllegalStateException("Main thread failed to get thread index 0");
        }
        WorkQueueBootstrapper.bootstrap();
    }
}
