package graphics.cinnabar.earlywindow;

import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.NativeType;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.LongBuffer;

import static org.lwjgl.glfw.GLFWVulkan.nglfwCreateWindowSurface;
import static org.lwjgl.system.Checks.CHECKS;
import static org.lwjgl.system.Checks.check;
import static org.lwjgl.system.JNI.invokePPI;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memAddressSafe;

/*
 * org.lwjgl.glfw _cannot_ reference any org.lwjgl.vulkan class
 * this is a limitation of how modlauncher handles loading modules, there is no way around it
 *
 * luckily, the native function pointers are public, so i can copy the calls in GLFWVulkan into my own module, that _can_ read org.lwjgl.vulkan without issue
 */
public class GLFWClassloadHelper {
    @NativeType("int")
    public static boolean glfwExtGetPhysicalDevicePresentationSupport(VkInstance instance, VkPhysicalDevice device, @NativeType("uint32_t") int queuefamily) {
        long __functionAddress = GLFWVulkan.Functions.GetPhysicalDevicePresentationSupport;
        return invokePPI(instance.address(), device.address(), queuefamily, __functionAddress) != 0;
    }
    
    @NativeType("VkResult")
    public static int glfwExtCreateWindowSurface(VkInstance instance, @NativeType("GLFWwindow *") long window, @org.jetbrains.annotations.Nullable @NativeType("VkAllocationCallbacks const *") VkAllocationCallbacks allocator, @NativeType("VkSurfaceKHR *") LongBuffer surface) {
        if (CHECKS) {
            check(surface, 1);
        }
        return nglfwCreateWindowSurface(instance.address(), window, memAddressSafe(allocator), memAddress(surface));
    }
}
