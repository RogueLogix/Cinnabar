package dev.logix.cinnabar.internal;

import dev.logix.cinnabar.Cinnabar;

public class CinnabarDebug {
    // this lets the JIT assume this value wont change, while still making it able to be modified before launch
    public static final boolean DEBUG = Cinnabar.CONFIG.Debug;
}
