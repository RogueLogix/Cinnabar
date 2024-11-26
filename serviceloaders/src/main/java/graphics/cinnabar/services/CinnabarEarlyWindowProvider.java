package graphics.cinnabar.services;

import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.*;
import java.util.stream.Collectors;

import static org.lwjgl.glfw.GLFW.*;

public class CinnabarEarlyWindowProvider implements ImmediateWindowProvider {
    public static final String EARLY_WINDOW_NAME = "CinnabarEarlyWindow2";

    private static boolean nameQueried = false;

    public static void attemptConfigInit() {
        if (nameQueried) {
            return;
        }
        final var value = FMLConfig.getConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER);
        if (value.equals("fmlearlywindow")) {
            // overwrite it being fmlearlywindow
            FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER, CinnabarEarlyWindowProvider.EARLY_WINDOW_NAME);
        } else if (!value.equals(EARLY_WINDOW_NAME)) {
            // its neither, consider the name queried and dont override it
            nameQueried = true;
        }
    }

    private static Method NV_HANDOFF;
    private static Method NV_POSITION;
    private static Method NV_OVERLAY;
    private static Method NV_VERSION;

    @Override
    public String name() {
        if (!nameQueried) {
            // write fmlearlywindow back to the config
            // this keeps it so that if Cinnabar is removed, the config is back to what it was
            FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER, "fmlearlywindow");
        }
        nameQueried = true;
        return EARLY_WINDOW_NAME;
    }

    @Override
    public Runnable initialize(String[] arguments) {
        return () -> {};
    }

    @Override
    public void updateFramebufferSize(IntConsumer width, IntConsumer height) {

    }

    @Override
    public long setupMinecraftWindow(IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitor) {
        // VK requires GLFW_NO_API
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        return glfwCreateWindow(width.getAsInt(), height.getAsInt(), title.get(), monitor.getAsLong(), 0);
    }

    @Override
    public boolean positionWindow(Optional<Object> monitor, IntConsumer widthSetter, IntConsumer heightSetter, IntConsumer xSetter, IntConsumer ySetter) {
        return false;
    }

    @Override
    public <T> Supplier<T> loadingOverlay(Supplier<?> mc, Supplier<?> ri, Consumer<Optional<Throwable>> ex, boolean fade) {
        try {
            //noinspection unchecked
            return (Supplier<T>) NV_OVERLAY.invoke(null, mc, ri, ex, fade);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updateModuleReads(final ModuleLayer layer) {
        var fm = layer.findModule("neoforge");
        if (fm.isPresent()) {
            getClass().getModule().addReads(fm.get());
            var clz = fm.map(l -> Class.forName(l, "net.neoforged.neoforge.client.loading.NoVizFallback")).orElseThrow();
            var methods = Arrays.stream(clz.getMethods()).filter(m -> Modifier.isStatic(m.getModifiers())).collect(Collectors.toMap(Method::getName, Function.identity()));
            NV_HANDOFF = methods.get("windowHandoff");
            NV_OVERLAY = methods.get("loadingOverlay");
            NV_POSITION = methods.get("windowPositioning");
            NV_VERSION = methods.get("glVersion");
        }
    }

    @Override
    public void periodicTick() {

    }

    @Override
    public String getGLVersion() {
        return "VK 1.3";
    }

    @Override
    public void crash(String message) {

    }
}
