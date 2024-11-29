package graphics.cinnabar.internal;

import graphics.cinnabar.Cinnabar;

import static graphics.cinnabar.core.CinnabarCore.CONFIG;

public class CinnabarDebug {
    // this lets the JIT assume this value wont change, while still making it able to be modified before launch
    public static final boolean DEBUG = CONFIG.Debug;
}
