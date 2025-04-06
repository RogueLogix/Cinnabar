package graphics.cinnabar.services;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;

import static org.lwjgl.glfw.GLFW.*;

public class CinnabarEarlyWindowProvider implements ImmediateWindowProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    static {
        LOGGER.trace("CinnabarEarlyWindowProvider loaded!");
    }
    
    public static final String EARLY_WINDOW_NAME = "CinnabarEarlyWindow";
    
    private static boolean nameQueried = false;
    private static boolean configInjected = false;
    
    public static void attemptConfigInit() {
        if (nameQueried || configInjected) {
            return;
        }
        final var value = FMLConfig.getConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER);
        if (value.equals("fmlearlywindow")) {
            // overwrite it being fmlearlywindow
            LOGGER.trace("Injecting CinnabarEarlyWindow into FML config for early window provider");
            FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER, CinnabarEarlyWindowProvider.EARLY_WINDOW_NAME);
            configInjected = true;
        }
    }
    
    @Override
    public String name() {
        if (!nameQueried && configInjected) {
            // write fmlearlywindow back to the config
            // this keeps it so that if Cinnabar is removed, the config is back to what it was
            LOGGER.trace("Reverting CinnabarEarlyWindow injection, config has been queried by this point");
            FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER, "fmlearlywindow");
        }
        nameQueried = true;
        return EARLY_WINDOW_NAME;
    }
    
    @Override
    public Runnable initialize(String[] arguments) {
        if (!glfwInit()) {
            final var msg = """
                    Unrecoverable error
                    Unable to initialize graphics system
                    glfwInit failed
                    """;
            TinyFileDialogs.tinyfd_messageBox("Minecraft: Cinnabar", msg, "ok", "error", false);
            System.exit(1);
        }
        return () -> {
        };
    }
    
    @Override
    public void updateModuleReads(final ModuleLayer layer) {
        // bootstrap Cinnabar
//        final var cinnabarModule = layer.findModule("cinnabar");
//        if (cinnabarModule.isPresent()) {
//            final var clazz = Class.forName(cinnabarModule.orElse(null), "graphics.cinnabar.core.CinnabarCore");
//            try {
//                final var startupFunc = clazz.getDeclaredMethod("startup");
//                startupFunc.invoke(null);
//            } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
//                throw new RuntimeException(e);
//            }
//        }
    }
    
    @Override
    public long takeOverGlfwWindow() {
        if (CinnabarLaunchPlugin.initCompleted()) {
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        }
        return glfwCreateWindow(854, 480, "Cinnabar", 0, 0);
    }
    
    @Override
    public void periodicTick() {
    
    }
    
    @Override
    public void updateProgress(String label) {
    
    }
    
    @Override
    public void completeProgress() {
    
    }
    
    @Override
    public void crash(String message) {
    
    }
}
