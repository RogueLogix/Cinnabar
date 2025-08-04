package graphics.cinnabar.api.hg;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

public class Hg {
    private static final VarHandle traceLoggingFieldHandle;
    private static final VarHandle debugLoggingFieldHandle;
    private static final MethodHandle createMethodHandle;
    
    static {
        try {
            final var classLoader = Hg.class.getClassLoader();
            final var mercuryConfigClass = classLoader.loadClass("graphics.cinnabar.core.mercury.Mercury$Config");
            traceLoggingFieldHandle = MethodHandles.lookup().findStaticVarHandle(mercuryConfigClass, "traceLogging", boolean.class);
            debugLoggingFieldHandle = MethodHandles.lookup().findStaticVarHandle(mercuryConfigClass, "debugLogging", boolean.class);
            
            final var mercuryClass = classLoader.loadClass("graphics.cinnabar.core.mercury.Mercury");
            createMethodHandle = MethodHandles.lookup().findStatic(mercuryClass, "createDevice", MethodType.methodType(HgDevice.class, HgDevice.CreateInfo.class));
        } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }
    
    public static void enableTraceLogging() {
        traceLoggingFieldHandle.set(true);
    }
    
    public static boolean traceLogging() {
        return (boolean) traceLoggingFieldHandle.get();
    }
    
    public static void enableDebugLogging() {
        debugLoggingFieldHandle.set(true);
    }
    
    public static boolean debugLogging() {
        return (boolean) debugLoggingFieldHandle.get();
    }
    
    public static HgDevice createDevice(HgDevice.CreateInfo createInfo) {
        try {
            return (HgDevice) createMethodHandle.invoke(createInfo);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
