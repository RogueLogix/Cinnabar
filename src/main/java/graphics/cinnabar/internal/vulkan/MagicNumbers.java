package graphics.cinnabar.internal.vulkan;

import net.roguelogix.phosphophyllite.util.NonnullDefault;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;

@NonnullDefault
public class MagicNumbers {
    // chosen due to universal support, and matching what MC normally gets from GL anyway
    public static final int FramebufferColorFormat = VK_FORMAT_B8G8R8A8_UNORM;
    public static final int FramebufferDepthFormat = VK_FORMAT_D32_SFLOAT;
    public static final int SwapchainColorFormat = VK_FORMAT_B8G8R8A8_UNORM;
    public static final int SwapchainColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    
    public static final int[] VSyncPresentModeOrder = new int[]{
            VK_PRESENT_MODE_MAILBOX_KHR, // triple buffering, should add a toggle for this
            VK_PRESENT_MODE_FIFO_RELAXED_KHR, // preferred if supported in case sync is just missed
            VK_PRESENT_MODE_FIFO_KHR // guaranteed by spec
    };
    
    public static final int[] NoSyncPresentModeOrder = new int[]{
            VK_PRESENT_MODE_IMMEDIATE_KHR,
            VK_PRESENT_MODE_FIFO_KHR // because this is the only one the VK spec guarantees, it's the fallback for nosync
    };
}
