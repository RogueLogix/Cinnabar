package graphics.cinnabar.core.hg3d;

import com.mojang.logging.LogUtils;
import graphics.cinnabar.api.hg.Hg;
import org.slf4j.Logger;

public class Hg3D {
    public static final Logger HG3D_LOG = LogUtils.getLogger();
    public static final boolean TRACE_LOGGING = Hg.traceLogging();
    public static final boolean DEBUG_LOGGING = Hg.debugLogging() || TRACE_LOGGING;
    public static final boolean USE_REVERSE_Z = Hg3D.class.getClassLoader().getResource("page/langeweile/longview/api/LongviewDevice.class") != null;
}
