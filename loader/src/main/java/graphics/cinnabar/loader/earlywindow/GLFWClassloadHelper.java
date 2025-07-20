package graphics.cinnabar.loader.earlywindow;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import javax.annotation.Nullable;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.APIUtil.apiLog;
import static org.lwjgl.system.Checks.CHECKS;
import static org.lwjgl.system.Checks.check;
import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryUtil.*;

/*
 * org.lwjgl.glfw _cannot_ reference any org.lwjgl.vulkan class
 * this is a limitation of how modlauncher handles loading modules, there is no way around it
 *
 * luckily, the native function pointers are public, so i can copy the calls in GLFWVulkan into my own module, that _can_ read org.lwjgl.vulkan without issue
 */
public class GLFWClassloadHelper {
    
    static {
        if (Platform.get() == Platform.MACOSX) {
            setPath(VK.getFunctionProvider());
        }
    }
    
    public static void setPath(FunctionProvider sharedLibrary) {
        if (!(sharedLibrary instanceof SharedLibrary)) {
            apiLog("GLFW Vulkan path override not set: function provider is not a shared library.");
            return;
        }
        
        String path = ((SharedLibrary) sharedLibrary).getPath();
        if (path == null) {
            apiLog("GLFW Vulkan path override not set: Could not resolve the shared library path.");
            return;
        }
        
        setPath(path);
    }
    
    public static void setPath(@Nullable String path) {
        long override = GLFW.getLibrary().getFunctionAddress("_glfw_vulkan_library");
        if (override == NULL) {
            apiLog("GLFW Vulkan path override not set: Could not resolve override symbol.");
            return;
        }
        
        long a = memGetAddress(override);
        if (a != NULL) {
            nmemFree(a);
        }
        memPutAddress(override, path == null ? NULL : memAddress(memUTF8(path)));
    }
    
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
        long __functionAddress = GLFWVulkan.Functions.CreateWindowSurface;
        if (CHECKS) {
            check(window);
        }
        return invokePPPPI(instance.address(), window, memAddressSafe(allocator), memAddress(surface), __functionAddress);
    }
    
    @Nullable
    @NativeType("char const **")
    public static PointerBuffer glfwGetRequiredInstanceExtensions() {
        MemoryStack stack = stackGet();
        int stackPointer = stack.getPointer();
        IntBuffer count = stack.callocInt(1);
        try {
            long __functionAddress = GLFWVulkan.Functions.GetRequiredInstanceExtensions;
            long __result = invokePP(memAddress(count), __functionAddress);
            return memPointerBufferSafe(__result, count.get(0));
        } finally {
            stack.setPointer(stackPointer);
        }
    }
}
