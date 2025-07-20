package graphics.cinnabar.loader.services;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.mojang.logging.LogUtils;
import graphics.cinnabar.loader.earlywindow.GLFWClassloadHelper;
import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import graphics.cinnabar.loader.earlywindow.vulkan.BasicSwapchain;
import joptsimple.OptionParser;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class CinnabarEarlyWindowProvider implements ImmediateWindowProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    static {
        LOGGER.trace("CinnabarEarlyWindowProvider loaded!");
    }
    
    public static final String EARLY_WINDOW_NAME = "CinnabarEarlyWindow";
    
    private static boolean nameQueried = false;
    private static boolean configInjected = false;
    
    private static final Method SET_CONFIG_VALUE;
    private static final Field CONFIG_DATA;
    private static final Field CONFIG_INSTANCE;
    
    static {
        try {
            CONFIG_DATA = FMLConfig.class.getDeclaredField("configData");
            CONFIG_DATA.setAccessible(true);
            CONFIG_INSTANCE = FMLConfig.class.getDeclaredField("INSTANCE");
            CONFIG_INSTANCE.setAccessible(true);
            SET_CONFIG_VALUE = FMLConfig.ConfigValue.class.getDeclaredMethod("setConfigValue", CommentedConfig.class, Object.class);
            SET_CONFIG_VALUE.setAccessible(true);
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
    
    public static void attemptConfigInit() {
        if (nameQueried || configInjected) {
            return;
        }
        if (!glfwInit()) {
            final var msg = """
                    Unrecoverable error
                    Unable to initialize graphics system
                    glfwInit failed
                    """;
            TinyFileDialogs.tinyfd_messageBox("Minecraft: Cinnabar", msg, "ok", "error", false);
            System.exit(1);
        }
        final var value = FMLConfig.getConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER);
        if (value.equals("fmlearlywindow") && VulkanStartup.isSupported()) {
            LOGGER.trace("Injecting CinnabarEarlyWindow into FML config for early window provider");
            try {
                // overwrite it being fmlearlywindow, but only in memory
                //noinspection RedundantCast
                SET_CONFIG_VALUE.invoke(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER, (CommentedConfig) CONFIG_DATA.get(CONFIG_INSTANCE.get(null)), EARLY_WINDOW_NAME);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            configInjected = true;
        }
    }
    
    private int winWidth;
    private int winHeight;
    private boolean maximized;
    
    private long window;
    private long surface;
    
    private VulkanStartup.Instance instance;
    private VulkanStartup.Device device;
    private BasicSwapchain swapchain;
    private long commandPool;
    private VkCommandBuffer commandBuffer;
    
    private ScheduledExecutorService renderScheduler;
    private ScheduledFuture<?> windowTick;
    
    @Override
    public String name() {
        if (nameQueried) {
            // on subsequent name queries, we are "fmlearlywindow" to ensure the config gets reset correctly
            // FML only ever queries it twice
            // once to filter them
            // and once to update the config
            return "fmlearlywindow";
        }
        nameQueried = true;
        return EARLY_WINDOW_NAME;
    }
    
    @Override
    public Runnable initialize(String[] arguments) {
        if (!configInjected) {
            return () -> {
            };
        }
        final OptionParser parser = new OptionParser();
        var mcversionopt = parser.accepts("fml.mcVersion").withRequiredArg().ofType(String.class);
        var forgeversionopt = parser.accepts("fml.neoForgeVersion").withRequiredArg().ofType(String.class);
        var widthopt = parser.accepts("width")
                               .withRequiredArg().ofType(Integer.class)
                               .defaultsTo(FMLConfig.getIntConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_WIDTH));
        var heightopt = parser.accepts("height")
                                .withRequiredArg().ofType(Integer.class)
                                .defaultsTo(FMLConfig.getIntConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_HEIGHT));
        var maximizedopt = parser.accepts("earlywindow.maximized");
        parser.allowsUnrecognizedOptions();
        var parsed = parser.parse(arguments);
        winWidth = parsed.valueOf(widthopt);
        winHeight = parsed.valueOf(heightopt);
        FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_WIDTH, winWidth);
        FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_HEIGHT, winHeight);
        this.maximized = parsed.has(maximizedopt) || FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_MAXIMIZED);
        
        var forgeVersion = parsed.valueOf(forgeversionopt);
        StartupNotificationManager.modLoaderConsumer().ifPresent(c -> c.accept("NeoForge loading " + forgeVersion));
        
        final var mcVersion = parsed.valueOf(mcversionopt);
        if (mcVersion != null) {
            // this emulates what we would get without early progress window
            // as vanilla never sets these, so GLFW uses the first window title
            // set them explicitly to avoid it using "FML early loading progress" as the class
            String vanillaWindowTitle = "Minecraft* " + mcVersion;
            glfwWindowHintString(GLFW_X11_CLASS_NAME, vanillaWindowTitle);
            glfwWindowHintString(GLFW_X11_INSTANCE_NAME, vanillaWindowTitle);
        }
        
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        window = glfwCreateWindow(winWidth, winHeight, "Cinnabar Early Loading", 0, 0);
        
        if (this.maximized) {
            glfwMaximizeWindow(window);
        }
        
        instance = VulkanStartup.createVkInstance(false, false, null);
        device = VulkanStartup.createLogicalDeviceAndQueues(instance.instance(), VulkanStartup.selectPhysicalDevice(instance.instance(), false, -1, instance.enabledInsanceExtensions()), instance.enabledInsanceExtensions());
        
        try (final var stack = MemoryStack.stackPush()) {
            final var surfacePtr = stack.longs(0);
            GLFWClassloadHelper.glfwExtCreateWindowSurface(instance.instance(), window, null, surfacePtr);
            surface = surfacePtr.get(0);
        }
        
        swapchain = new BasicSwapchain(device, surface, 0);
        
        try (final var stack = MemoryStack.stackPush()) {
            final var createInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default();
            createInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            createInfo.queueFamilyIndex(device.queues().getFirst().queueFamily());
            final var ptr = stack.mallocLong(1);
            vkCreateCommandPool(device.device(), createInfo, null, ptr);
            commandPool = ptr.get(0);
            
            final var allocInfo = VkCommandBufferAllocateInfo.calloc(stack).sType$Default();
            allocInfo.commandPool(commandPool);
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(1);
            final var cb = stack.pointers(0);
            vkAllocateCommandBuffers(device.device(), allocInfo, cb);
            commandBuffer = new VkCommandBuffer(cb.get(0), device.device());
        }
        
        renderScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final var thread = Executors.defaultThreadFactory().newThread(r);
            thread.setDaemon(true);
            return thread;
        });
        windowTick = renderScheduler.scheduleAtFixedRate(this::drawLoop, 50, 50, TimeUnit.MILLISECONDS);
        return org.lwjgl.glfw.GLFW::glfwPollEvents;
    }
    
    void recreateSwapchain() {
        final var oldChain = swapchain;
        swapchain = new BasicSwapchain(device, surface, oldChain.swapchainHandle);
        oldChain.destroy();
    }
    
    public void textureLayoutTransition(VkCommandBuffer commandBuffer, long image, int srcLayout, int dstLayout) {
        try (final var stack = MemoryStack.stackPush()) {
            final var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.oldLayout(srcLayout);
            barrier.newLayout(dstLayout);
            barrier.srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(image);
            final var subresourceRange = barrier.subresourceRange();
            subresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            subresourceRange.baseMipLevel(0);
            subresourceRange.levelCount(1);
            subresourceRange.baseArrayLayer(0);
            subresourceRange.layerCount(1);
            
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barrier);
        }
    }
    
    synchronized void drawLoop() {
        while (!swapchain.acquire()) {
            recreateSwapchain();
        }
        
        try (final var stack = MemoryStack.stackPush()) {
            vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default());
            textureLayoutTransition(commandBuffer, swapchain.acquiredImage(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            final var clearColor = VkClearColorValue.calloc(stack).float32(0, ((float) 0xA4) / 0xFF).float32(1, ((float) 0x1E) / 0xFF).float32(2, ((float) 0x22) / 0xFF).float32(3, 1);
            final var subresourceRange = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            vkCmdClearColorImage(commandBuffer, swapchain.acquiredImage(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearColor, subresourceRange);
            textureLayoutTransition(commandBuffer, swapchain.acquiredImage(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            vkEndCommandBuffer(commandBuffer);
            final var submitInfo = VkSubmitInfo.calloc(stack).sType$Default();
            submitInfo.pCommandBuffers(stack.pointers(commandBuffer));
            vkQueueSubmit(device.queues().getFirst().queue(), submitInfo, 0);
        }
        
        while (!swapchain.present()) {
            recreateSwapchain();
        }
    }
    
    @Override
    public long takeOverGlfwWindow() {
        windowTick.cancel(false);
        renderScheduler.shutdown();
        try {
            renderScheduler.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        try (final var stack = MemoryStack.stackPush()) {
            vkFreeCommandBuffers(device.device(), commandPool, stack.pointers(commandBuffer));
        }
        vkDestroyCommandPool(device.device(), commandPool, null);
        swapchain.destroy();
        vkDestroySurfaceKHR(instance.instance(), surface, null);
        device.destroy();
        instance.destroy();
        return window;
    }
    
    @Override
    public void periodicTick() {
        
    }
    
    @Override
    public void updateProgress(String label) {
        
    }
    
    @Override
    public void completeProgress() {
        
    }
    
    @Override
    public void crash(String message) {
        glfwDestroyWindow(window);
        glfwTerminate();
        System.exit(1);
    }
}
