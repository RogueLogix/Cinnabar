package graphics.cinnabar.core;

import com.mojang.logging.LogUtils;
import graphics.cinnabar.api.annotations.CinnabarRedirectIMPL;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

public class CinnabarCore {
    public static final Logger CINNABAR_CORE_LOG = LogUtils.getLogger();
    
    @Nullable
    public static CinnabarDevice cinnabarDeviceSingleton;
    
    public static CinnabarDevice device() {
        if (cinnabarDeviceSingleton == null) {
            throw new IllegalStateException("Cannot get CinnabarDevice before initialization has started");
        }
        return cinnabarDeviceSingleton;
    }
    
    @CinnabarRedirectIMPL.Dst("graphics/cinnabar/api/CinnabarAPI$Internals/fetchDebugMode")
    public static boolean fetchDebugMode() {
        return device().cinnabarDebugModeEnabled();
    }
    
    @CinnabarRedirectIMPL.Dst("graphics/cinnabar/api/CinnabarAPI$Internals/fetchDebugMarkerEnabled")
    public static boolean fetchDebugMarkerEnabled() {
        return device().debugMarkerEnabled();
    }
}
