package graphics.cinnabar.core.mercury;

import com.mojang.logging.LogUtils;
import graphics.cinnabar.api.annotations.UsedFromReflection;
import graphics.cinnabar.api.hg.HgDevice;
import org.slf4j.Logger;

import static graphics.cinnabar.lib.util.Constants.constant;

public class Mercury {
    public static final Logger MERCURY_LOG = LogUtils.getLogger();
    public static final boolean TRACE_LOGGING = Config.traceLogging;
    public static final boolean DEBUG_LOGGING = Config.debugLogging || TRACE_LOGGING;

    static {
        MERCURY_LOG.info("Config loaded");
    }
    
    @UsedFromReflection
    public static HgDevice createDevice() {
        return new MercuryDevice();
    }
    
    public static class Config {
        @UsedFromReflection
        private static boolean traceLogging = constant(false);
        @UsedFromReflection
        private static boolean debugLogging = constant(false);
    }
}
