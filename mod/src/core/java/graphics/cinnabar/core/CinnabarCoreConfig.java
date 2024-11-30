package graphics.cinnabar.core;

import graphics.cinnabar.lib.config.ConfigValue;
import graphics.cinnabar.lib.vulkan.VulkanDebug;
import net.neoforged.fml.loading.FMLLoader;

public class CinnabarCoreConfig {
    
    @ConfigValue(comment = "Enables additional runtime debugging features\nEXPECT LARGE PERFORMANCE IMPACT")
    public final boolean Debug;
    @ConfigValue(comment = "Always enabled with debug mode\nEXPECT LARGE PERFORMANCE IMPACT")
    public final boolean EnableValidationLayers;
    @ConfigValue
    public final VulkanDebug.MessageSeverity[] MessageSeverities;
    @ConfigValue
    public final VulkanDebug.MessageType[] MessageTypes;
    @ConfigValue(comment = "Enables VK_LAYER_MESA_overlay if available")
    public final boolean EnableMesaOverlay;
    
    {
        // yes, debug defaults to ON for development environments
        // turn it off for performance testing
        Debug = !FMLLoader.isProduction();
        EnableValidationLayers = false;
        MessageSeverities = new VulkanDebug.MessageSeverity[]{VulkanDebug.MessageSeverity.ERROR, VulkanDebug.MessageSeverity.WARNING};
        MessageTypes = new VulkanDebug.MessageType[]{VulkanDebug.MessageType.GENERAL, VulkanDebug.MessageType.VALIDATION, VulkanDebug.MessageType.PERFORMANCE};
        EnableMesaOverlay = false;
    }
    
    @ConfigValue(comment = "Shows a popup when iterating through devices to allow manual selection, only shows capable devices")
    public final boolean ManualDeviceSelection;
    @ConfigValue(comment = "Skips all other devices except the index selected here, use 'vulkaninfo' command to find devices")
    public final int ForcedVulkanDeviceIndex;
    
    {
        ManualDeviceSelection = false;
        ForcedVulkanDeviceIndex = -1;
    }
}
