package graphics.cinnabar.lib;

import graphics.cinnabar.api.threading.ThreadIndexRegistry;
import graphics.cinnabar.api.threading.WorkQueueBootstrapper;

import static graphics.cinnabar.lib.CinnabarLib.CINNABAR_LIB_LOG;

public class CinnabarLibBootstrapper {
    public static void bootstrap() {
        CINNABAR_LIB_LOG.info("Initializing CinnabarLib");
        if (Thread.currentThread().threadId() != 1) {
            throw new IllegalStateException("Cinnabar can only be bootstrapped from the main thread");
        }
        final var index = ThreadIndexRegistry.registerThisThread();
        if (index.index() != 0) {
            throw new IllegalStateException("Main thread failed to get thread index 0");
        }
        WorkQueueBootstrapper.bootstrap();
    }
}
