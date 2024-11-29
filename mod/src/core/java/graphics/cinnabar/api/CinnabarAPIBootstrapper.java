package graphics.cinnabar.api;

import graphics.cinnabar.api.annotations.Internal;
import graphics.cinnabar.core.CinnabarCore;

@Internal
public class CinnabarAPIBootstrapper {
    public static void boostrap() {
        CinnabarAPI.Bootstrapper.debugMode = CinnabarCore.DEBUG;
        CinnabarAPI.Bootstrapper.vkInstance = CinnabarCore.vkInstance;
        CinnabarAPI.Bootstrapper.vkDevice = CinnabarCore.vkDevice;
        
        if (CinnabarAPI.DEBUG_MODE != CinnabarAPI.Bootstrapper.debugMode) {
            throw new IllegalStateException("CinnabarAPI classloaded before bootstrap");
        }
    }
}
