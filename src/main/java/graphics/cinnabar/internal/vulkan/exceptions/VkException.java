package graphics.cinnabar.internal.vulkan.exceptions;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_INVALID_EXTERNAL_HANDLE;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

public class VkException extends RuntimeException {
    public VkException() {
    }
    
    public VkException(String errorString) {
        super(errorString);
    }
    
    public static void throwFromCode(int code) {
        switch (code) {
            case VK_SUCCESS, VK_NOT_READY, VK_TIMEOUT, VK_EVENT_SET, VK_EVENT_RESET, VK_INCOMPLETE -> {
                // these are all sucesss codes, so, dont do anything
            }
            case VK_ERROR_OUT_OF_HOST_MEMORY -> throw new OutOfMemoryError();
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> throw new VkOutOfDeviceMemory();
            case VK_ERROR_INITIALIZATION_FAILED -> throw new VkInitializationFailed();
            case VK_ERROR_DEVICE_LOST -> throw new VkDeviceLost();
            case VK_ERROR_MEMORY_MAP_FAILED -> throw new VkMapFailed();
            case VK_ERROR_LAYER_NOT_PRESENT -> throw new VkException("VK_ERROR_LAYER_NOT_PRESENT");
            case VK_ERROR_EXTENSION_NOT_PRESENT -> throw new VkException("VK_ERROR_EXTENSION_NOT_PRESENT");
            case VK_ERROR_FEATURE_NOT_PRESENT -> throw new VkException("VK_ERROR_FEATURE_NOT_PRESENT");
            case VK_ERROR_INCOMPATIBLE_DRIVER -> throw new VkException("VK_ERROR_INCOMPATIBLE_DRIVER");
            case VK_ERROR_TOO_MANY_OBJECTS -> throw new VkTooManyObjects();
            case VK_ERROR_FORMAT_NOT_SUPPORTED -> throw new VkException("VK_ERROR_FORMAT_NOT_SUPPORTED");
            case VK_ERROR_FRAGMENTED_POOL -> throw new VkException("VK_ERROR_FRAGMENTED_POOL");
            case -13 /* VK_ERROR_UNKNOWN */ -> throw new VkException("VK_ERROR_UNKNOWN");
            case VK_ERROR_OUT_OF_POOL_MEMORY -> throw new VkException("VK_ERROR_OUT_OF_POOL_MEMORY");
            case VK_ERROR_INVALID_EXTERNAL_HANDLE -> throw new VkException("VK_ERROR_INVALID_EXTERNAL_HANDLE");
            case -1000161000 /* VK_ERROR_FRAGMENTATION */ -> throw new VkException("VK_ERROR_FRAGMENTATION");
            case -1000257000 /* VK_ERROR_INVALID_OPAQUE_CAPTURE_ADDRESS */ -> throw new VkException("VK_ERROR_INVALID_OPAQUE_CAPTURE_ADDRESS");
            default -> {
                // unknown, should this be assumed ok or no?
                // for now, im going to assume its fine
            }
        }
    }
}
