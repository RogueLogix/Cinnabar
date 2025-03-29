package graphics.cinnabar.core.vk;

import graphics.cinnabar.api.CinnabarAPI;
import graphics.cinnabar.api.CinnabarGpuDevice;
import graphics.cinnabar.api.annotations.Constant;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.util.Destroyable;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDebugMarkerObjectNameInfoEXT;

import static org.lwjgl.vulkan.EXTDebugMarker.vkDebugMarkerSetObjectNameEXT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

public interface VulkanObject extends Destroyable, VulkanNameable {
    @Constant
    @Contract(pure = true)
    @ThreadSafety.Many
    long handle();
    
    @Constant
    @Contract(pure = true)
    @ThreadSafety.Many
    int objectType();
    
    @Contract
    @ThreadSafety.VulkanObjectHandle
    default void setVulkanName(@Nullable String name) {
        // null handle is valid in some cases, so just early out
        if (!CinnabarAPI.Internals.DEBUG_MARKER_ENABLED || handle() == VK_NULL_HANDLE || name == null) {
            return;
        }
        try (final var stack = MemoryStack.stackPush()) {
            final var nameInfo = VkDebugMarkerObjectNameInfoEXT.calloc(stack).sType$Default();
            nameInfo.objectType(objectType());
            nameInfo.object(handle());
            nameInfo.pObjectName(stack.UTF8(name));
            vkDebugMarkerSetObjectNameEXT(CinnabarGpuDevice.get().vkDevice(), nameInfo);
        }
    }
}
