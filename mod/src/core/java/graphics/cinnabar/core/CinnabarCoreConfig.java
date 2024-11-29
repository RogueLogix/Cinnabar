package graphics.cinnabar.core;

import graphics.cinnabar.lib.config.ConfigValue;
import graphics.cinnabar.lib.vulkan.VulkanDebug;
import net.neoforged.fml.loading.FMLLoader;

public class CinnabarCoreConfig {
    
    @ConfigValue
    public final boolean Debug;
    @ConfigValue(comment = "Always enabled with debug mode")
    public final boolean EnableValidationLayers;
    @ConfigValue
    public final VulkanDebug.MessageSeverity[] MessageSeverities;
    @ConfigValue
    public final VulkanDebug.MessageType[] MessageTypes;
    @ConfigValue
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
    
    @ConfigValue
    public final boolean ManualDeviceSelection;
    @ConfigValue
    public final int ForcedVulkanDeviceIndex;
    
    {
        ManualDeviceSelection = false;
        ForcedVulkanDeviceIndex = -1;
    }
}
