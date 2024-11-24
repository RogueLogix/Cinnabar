package graphics.cinnabar.internal.vulkan;

import graphics.cinnabar.Cinnabar;
import graphics.cinnabar.internal.vulkan.exceptions.VkException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.VK_FALSE;
import static graphics.cinnabar.Cinnabar.LOGGER;

public class VulkanDebug extends VkDebugUtilsMessengerCallbackEXT {
    public static VkDebugUtilsMessengerCreateInfoEXT getCreateInfo(MemoryStack stack) {
        final var dbgCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.malloc(stack);
        dbgCreateInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
        dbgCreateInfo.pNext(0);
        dbgCreateInfo.flags(0);
        int messageSeverityBits = 0;
        for (var messageSeverity : Cinnabar.CONFIG.MessageSeverities) {
            messageSeverityBits |= messageSeverity.bit;
        }
        dbgCreateInfo.messageSeverity(messageSeverityBits);
        int messageTypeBits = 0;
        for (var messageType : Cinnabar.CONFIG.MessageTypes) {
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
        if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
            prefix.append("ERROR ");
        }
        if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
            prefix.append("WARNING ");
        }
        if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) != 0) {
            prefix.append("INFO ");
        }
        if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT) != 0) {
            prefix.append("VERBOSE ");
        }
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
        
        final var messageString = callbackData.pMessageString();
        LOGGER.warn(String.format("%s: %s", prefix, messageString));
        // vkDestroyDevice may print a lot of messages, and the stack is not helpful
        if (printStackTrace && !messageString.contains("vkDestroyDevice")) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            new VkException().printStackTrace(pw);
            LOGGER.warn(sw.toString());
        }
        return VK_FALSE;
    }
}
