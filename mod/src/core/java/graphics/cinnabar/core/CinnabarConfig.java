package graphics.cinnabar.core;

import graphics.cinnabar.lib.config.*;
import org.jetbrains.annotations.Contract;

public class CinnabarConfig {
    
    @RegisterConfig(name = "cinnabar", type = ConfigType.CLIENT, format = ConfigFormat.JSON5, comment = """
            Hey, you! Yes, you, the person reading this.
            You probably shouldn't be editing this config.
            The main purpose of this config is for debugging purposes, and it shouldn't be effecting compatability
            if changing a value in this fixes an issue you are having, REPORT IT
            """)
    public static final CinnabarConfig CONFIG = new CinnabarConfig();
    
    @ConfigValue(comment = "Enables VK_LAYER_MESA_overlay if available")
    public final boolean EnableMesaOverlay = defaultVal(false);
    
    @ConfigValue(comment = "Shows a popup when iterating through devices to allow manual selection, only shows capable devices")
    public final boolean ManualDeviceSelection = defaultVal(false);
    @ConfigValue(comment = "Skips all other devices except the index selected here, use 'vulkaninfo' command to find devices")
    public final int ForcedVulkanDeviceIndex = defaultVal(-1);
    
    // tricks the compiler into thinking its not a constant value, so it cant inline it elsewhere
    public static <T> T defaultVal(T t) {
        return t;
    }
    
    @Contract("_ -> param1") // tricks IDEA into thinking this might return something else
    public static boolean defaultVal(boolean t) {
        return t;
    }
    
    @Contract("_ -> param1") // tricks IDEA into thinking this might return something else
    public static byte defaultVal(byte t) {
        return t;
    }
    
    @Contract("_ -> param1") // tricks IDEA into thinking this might return something else
    public static short defaultVal(short t) {
        return t;
    }
    
    @Contract("_ -> param1") // tricks IDEA into thinking this might return something else
    public static int defaultVal(int t) {
        return t;
    }
    
    @Contract("_ -> param1") // tricks IDEA into thinking this might return something else
    public static long defaultVal(long t) {
        return t;
    }
    
    static {
        try {
            ConfigManager.registerConfig(CONFIG, CinnabarConfig.class.getField("CONFIG").getAnnotation(RegisterConfig.class));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }
}
