package graphics.cinnabar.loader.earlywindow.vulkan;

import com.mojang.logging.LogUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.slf4j.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.VK_FALSE;

public class VulkanDebug extends VkDebugUtilsMessengerCallbackEXT {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static VkDebugUtilsMessengerCreateInfoEXT getCreateInfo(MemoryStack stack, MessageSeverity[] severities, MessageType[] messageTypes) {
        final var dbgCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.malloc(stack);
        dbgCreateInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
        dbgCreateInfo.pNext(0);
        dbgCreateInfo.flags(0);
        int messageSeverityBits = 0;
        for (var messageSeverity : severities) {
            messageSeverityBits |= messageSeverity.bit;
        }
        dbgCreateInfo.messageSeverity(messageSeverityBits);
        int messageTypeBits = 0;
        for (var messageType : messageTypes) {
            messageTypeBits |= messageType.bit;
        }
        dbgCreateInfo.messageType(messageTypeBits);
        dbgCreateInfo.pfnUserCallback(new VulkanDebug());
        dbgCreateInfo.pUserData(0);
        return dbgCreateInfo;
    }
    
    private VulkanDebug() {
    }
    
    @Override
    public int invoke(int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
        // IMPL takes care of it
        //noinspection resource
        final var callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
        final var prefix = new StringBuilder();
        boolean printStackTrace = false;
        if ((messageTypes & VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT) != 0) {
            prefix.append("GENERAL ");
        }
        if ((messageTypes & VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT) != 0) {
            prefix.append("VALIDATION ");
            printStackTrace = true;
        }
        if ((messageTypes & VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT) != 0) {
            prefix.append("PERFORMANCE ");
        }
        if (prefix.isEmpty()) {
            prefix.append("UNKNOWN ");
        }
        
        if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
            prefix.append("ERROR ");
        }
        if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
            prefix.append("WARNING ");
        }
        if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) != 0) {
            prefix.append("INFO ");
            printStackTrace = false;
        }
        if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT) != 0) {
            prefix.append("VERBOSE ");
            printStackTrace = false;
        }
        
        final var messageString = callbackData.pMessageString();
        LOGGER.warn("{}: {}", prefix, messageString);
        // vkDestroyDevice may print a lot of messages, and the stack is not helpful
        if (printStackTrace && !messageString.contains("vkDestroyDevice") && !messageString.contains("Attempting to enable")) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            new Exception().printStackTrace(pw);
            LOGGER.warn(sw.toString());
            // force a segfault, aka crash the JVM immediately
            MemoryUtil.memSet(0L, 0, 1L);
        }
        return VK_FALSE;
    }
    
    public enum MessageSeverity {
        ERROR(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT),
        WARNING(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT),
        INFO(VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT),
        VERBOSE(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT),
        ;
        
        public final int bit;
        
        MessageSeverity(int bit) {
            this.bit = bit;
        }
    }
    
    public enum MessageType {
        GENERAL(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT),
        VALIDATION(VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT),
        PERFORMANCE(VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT),
        ;
        
        public final int bit;
        
        MessageType(int bit) {
            this.bit = bit;
        }
    }
}
