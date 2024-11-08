package graphics.cinnabar;

import net.roguelogix.phosphophyllite.config.ConfigValue;
import net.roguelogix.phosphophyllite.util.NonnullDefault;

import static org.lwjgl.vulkan.EXTDebugUtils.*;

@NonnullDefault
public class CinnabarConfig {
    
    @ConfigValue
    public final boolean Debug;
    @ConfigValue
    public final boolean EnableValidationLayers;
    
    {
        Debug = false;
        EnableValidationLayers = false;
    }
    
    @NonnullDefault
    public enum VkDebugMessageSeverities {
        ERROR(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT),
        WARNING(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT),
        INFO(VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT),
        VERBOSE(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT),
        ;
        
        public final int bit;
        
        VkDebugMessageSeverities(int bit) {
            this.bit = bit;
        }
    }
    
    @NonnullDefault
    public enum VkDebugMessageTypes {
        GENERAL(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT),
        VALIDATION(VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT),
        PERFORMANCE(VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT),
        ;
        
        public final int bit;
        
        VkDebugMessageTypes(int bit) {
            this.bit = bit;
        }
    }
    
    @ConfigValue
    public final VkDebugMessageSeverities[] MessageSeverities = new VkDebugMessageSeverities[]{VkDebugMessageSeverities.ERROR, VkDebugMessageSeverities.WARNING};
    @ConfigValue
    public final VkDebugMessageTypes[] MessageTypes = new VkDebugMessageTypes[]{VkDebugMessageTypes.GENERAL, VkDebugMessageTypes.VALIDATION, VkDebugMessageTypes.PERFORMANCE};
}
