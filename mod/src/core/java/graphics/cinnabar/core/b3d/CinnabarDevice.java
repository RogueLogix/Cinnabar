package graphics.cinnabar.core.b3d;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.api.CinnabarGpuDevice;
import graphics.cinnabar.api.exceptions.NotImplemented;
import graphics.cinnabar.api.memory.MagicMemorySizes;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.api.util.Triple;
import graphics.cinnabar.core.b3d.buffers.PersistentWriteBuffer;
import graphics.cinnabar.core.b3d.buffers.TransientWriteBuffer;
import graphics.cinnabar.core.b3d.command.CinnabarCommandEncoder;
import graphics.cinnabar.core.b3d.pipeline.CinnabarPipeline;
import graphics.cinnabar.core.b3d.texture.CinnabarGpuTexture;
import graphics.cinnabar.core.b3d.window.CinnabarWindow;
import graphics.cinnabar.core.util.MagicNumbers;
import graphics.cinnabar.core.vk.VulkanSampler;
import graphics.cinnabar.core.vk.VulkanStartup;
import graphics.cinnabar.core.vk.memory.VkMemoryPool;
import graphics.cinnabar.core.vk.memory.pools.PersistentPool;
import graphics.cinnabar.core.vk.memory.pools.TransientPool;
import graphics.cinnabar.lib.util.MathUtil;
import graphics.cinnabar.lib.vulkan.VulkanDebug;
import it.unimi.dsi.fastutil.ints.IntReferencePair;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static graphics.cinnabar.core.CinnabarCore.CINNABAR_CORE_LOG;
import static org.lwjgl.vulkan.EXTDebugMarker.VK_EXT_DEBUG_MARKER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.VK13.*;

public class CinnabarDevice implements CinnabarGpuDevice {
    
    public final VkInstance vkInstance;
    private final long debugCallback;
    private final List<String> enabledLayersAndInstanceExtensions;
    
    public final VkPhysicalDevice vkPhysicalDevice;
    public final VkPhysicalDeviceProperties physicalDeviceProperties = VkPhysicalDeviceProperties.calloc();
    public final VkPhysicalDeviceLimits limits = physicalDeviceProperties.limits();
    
    public final String vendorString;
    public final String apiVersionUsed;
    public final String driverVersion;
    public final String renderer;
    
    public final VkDevice vkDevice;
    
    public final VkQueue graphicsQueue;
    public final int graphicsQueueFamily;
    @Nullable
    public final VkQueue computeQueue;
    public final int computeQueueFamily;
    @Nullable
    public final VkQueue transferQueue;
    public final int transferQueueFamily;
    
    public final List<String> enabledDeviceExtensions;
    public final boolean debugMarkerEnabled;
    
    private int currentFrame = 0;
    
    public final VkMemoryPool devicePersistentMemoryPool;
    public final VkMemoryPool.Transient deviceTransientMemoryPool;
    public final VkMemoryPool.CPU hostPersistentMemoryPool;
    public final List<VkMemoryPool.Transient.CPU> hostTransientMemoryPools;
    
    private final BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider;
    
    public CinnabarDevice(long windowHandle, int debugLevel, boolean syncDebug, BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider, boolean debugLabels) {
        this.shaderSourceProvider = shaderSourceProvider;
        CINNABAR_CORE_LOG.info("Initializing CinnabarDevice");
        final var instanceAndDebugCallback = VulkanStartup.createVkInstance(true, new VulkanDebug.MessageSeverity[]{VulkanDebug.MessageSeverity.ERROR, VulkanDebug.MessageSeverity.WARNING, VulkanDebug.MessageSeverity.INFO}, new VulkanDebug.MessageType[]{VulkanDebug.MessageType.GENERAL, VulkanDebug.MessageType.VALIDATION});
        vkInstance = instanceAndDebugCallback.first();
        debugCallback = instanceAndDebugCallback.second();
        enabledLayersAndInstanceExtensions = instanceAndDebugCallback.third();
        
        try {
            vkPhysicalDevice = VulkanStartup.selectPhysicalDevice(vkInstance);
        } catch (Exception e) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
            vkDestroyInstance(vkInstance, null);
            throw e;
        }
        
        vkGetPhysicalDeviceProperties(vkPhysicalDevice, physicalDeviceProperties);
        
        final Triple<VkDevice, List<IntReferencePair<VkQueue>>, List<String>> deviceAndQueues;
        try {
            deviceAndQueues = VulkanStartup.createLogicalDeviceAndQueues(vkInstance, vkPhysicalDevice, enabledLayersAndInstanceExtensions);
        } catch (Exception e) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
            vkDestroyInstance(vkInstance, null);
            throw e;
        }
        
        vkDevice = deviceAndQueues.first();
        final var queues = deviceAndQueues.second();
        graphicsQueue = queues.get(0).second();
        graphicsQueueFamily = queues.get(0).firstInt();
        computeQueue = queues.get(1).second();
        computeQueueFamily = queues.get(1).firstInt();
        transferQueue = queues.get(2).second();
        transferQueueFamily = queues.get(2).firstInt();
        
        enabledDeviceExtensions = deviceAndQueues.third();
        debugMarkerEnabled = enabledDeviceExtensions.contains(VK_EXT_DEBUG_MARKER_EXTENSION_NAME);
        
        final var APIVersionEncoded = physicalDeviceProperties.apiVersion();
        final var driverVersionEncoded = physicalDeviceProperties.driverVersion();
        vendorString = String.format("%x", physicalDeviceProperties.vendorID()); // TODO: pcie id to string map/switch
        apiVersionUsed = String.format("Vulkan %d.%d.%d", VK_VERSION_MAJOR(APIVersionEncoded), VK_VERSION_MINOR(APIVersionEncoded), VK_VERSION_PATCH(APIVersionEncoded));
        driverVersion = String.format("%d.%d.%d", VK_VERSION_MAJOR(driverVersionEncoded), VK_VERSION_MINOR(driverVersionEncoded), VK_VERSION_PATCH(driverVersionEncoded));
        renderer = String.format("%s", physicalDeviceProperties.deviceNameString());
        
        devicePersistentMemoryPool = createMemoryPool(false, VK_MEMORY_HEAP_DEVICE_LOCAL_BIT, 0, 0);
        deviceTransientMemoryPool = (VkMemoryPool.Transient) createMemoryPool(true, VK_MEMORY_HEAP_DEVICE_LOCAL_BIT, 0, 0);
        hostPersistentMemoryPool = (VkMemoryPool.CPU) createMemoryPool(false, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0, 0);
        final var hostTransientPools = new VkMemoryPool.Transient.CPU[MagicNumbers.MaximumFramesInFlight];
        for (int i = 0; i < hostTransientPools.length; i++) {
            hostTransientPools[i] = (VkMemoryPool.Transient.CPU) createMemoryPool(true, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0, 0);
        }
        hostTransientMemoryPools = List.of(hostTransientPools);
        
        commandEncoder = new CinnabarCommandEncoder(this);
        ((CinnabarWindow)Minecraft.getInstance().getWindow()).attachDevice(this);
    }
    
    @Override
    public void close() {
        clearPipelineCache();
        
        ((CinnabarWindow)Minecraft.getInstance().getWindow()).detachDevice();
        
        VulkanSampler.shutdown();
        
        commandEncoder.destroy();
        hostTransientMemoryPools.forEach(Destroyable::destroy);
        hostPersistentMemoryPool.destroy();
        deviceTransientMemoryPool.destroy();
        devicePersistentMemoryPool.destroy();
        
        toDestroy.forEach(Destroyable::destroy);
        toDestroy.clear();
        shutdownDestroy.forEach(Destroyable::destroy);
        shutdownDestroy.clear();
        vkDestroyDevice(vkDevice, null);
        vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
        vkDestroyInstance(vkInstance, null);
        CINNABAR_CORE_LOG.info("CinnabarDevice Shutdown");
    }
    
    ReferenceArrayList<Destroyable> toDestroy = new ReferenceArrayList<>();
    ReferenceArrayList<Destroyable> shutdownDestroy = new ReferenceArrayList<>();
    
    public void newFrame() {
        currentFrame++;
        currentFrame %= MagicNumbers.MaximumFramesInFlight;
        vkDeviceWaitIdle(vkDevice);
        toDestroy.forEach(Destroyable::destroy);
        toDestroy.clear();
    }
    
    public int currentFrameIndex() {
        return currentFrame;
    }
    
    public <T extends Destroyable> T destroyEndOfFrame(T destroyable) {
        toDestroy.add(destroyable);
        return destroyable;
    }
    
    public <T extends Destroyable> T destroyOnShutdown(T destroyable) {
        shutdownDestroy.add(destroyable);
        return destroyable;
    }
    
    public VkMemoryPool createMemoryPool(boolean transientPool, int requiredProperties, int preferredProperties, long blockSize) {
        if (blockSize <= 0) {
            blockSize = MagicMemorySizes.VK_PERSISTENT_BLOCK_SIZE;
        }
        blockSize = MathUtil.roundUpPo2(blockSize);
        final int selectedMemoryType;
        final int selectedMemoryTypeBits;
        preferredProperties |= requiredProperties;
        try (var stack = MemoryStack.stackPush()) {
            int memoryType = -1;
            int memoryTypeBits = -1;
            final var properties = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default();
            vkGetPhysicalDeviceMemoryProperties2(vkPhysicalDevice, properties);
            final var memoryProperties = properties.memoryProperties();
            final var types = memoryProperties.memoryTypes();
            for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
                types.position(i);
                final var propertyFlags = types.propertyFlags();
                if ((propertyFlags & preferredProperties) == preferredProperties) {
                    memoryType = i;
                    memoryTypeBits = propertyFlags;
                    break;
                }
                if ((propertyFlags & requiredProperties) == requiredProperties && memoryType == -1) {
                    memoryType = i;
                    memoryTypeBits = propertyFlags;
                }
            }
            if (memoryType == -1) {
                throw new IllegalStateException("Unable to find memory type");
            }
            selectedMemoryType = memoryType;
            selectedMemoryTypeBits = memoryTypeBits;
        }
        boolean mapped = (selectedMemoryTypeBits & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0;
        if (transientPool) {
            if (mapped) {
                return new TransientPool.CPU(this, selectedMemoryType, blockSize);
            } else {
                return new TransientPool(this, selectedMemoryType, blockSize);
            }
        } else {
            if (mapped) {
                return new PersistentPool.CPU(this, selectedMemoryType, blockSize);
            } else {
                return new PersistentPool(this, selectedMemoryType, blockSize);
            }
        }
    }
    
    public VkMemoryPool.Transient.CPU hostTransientMemoryPool() {
        return hostTransientMemoryPools.get(currentFrameIndex());
    }
    
    // --------- CinnabarGpuDevice ---------
    
    
    @Override
    public VkInstance vkInstance() {
        return vkInstance;
    }
    
    @Override
    public VkDevice vkDevice() {
        return vkDevice;
    }
    
    // --------- NeoGpuDevice ---------
    
    // --------- Vanilla GpuDevice ---------
    
    private final CinnabarCommandEncoder commandEncoder;
    
    @Override
    public CommandEncoder createCommandEncoder() {
        return commandEncoder;
    }
    
    @Override
    public GpuTexture createTexture(@Nullable Supplier<String> textureNameSupplier, TextureFormat format, int width, int height, int mips) {
        return createTexture(debugMarkerEnabled && textureNameSupplier != null ? textureNameSupplier.get() : null, format, width, height, mips);
    }
    
    @Override
    public GpuTexture createTexture(@Nullable String textureName, TextureFormat format, int width, int height, int mips) {
        if (textureName == null) {
            textureName = "Texture";
        }
        final var texture = new CinnabarGpuTexture(this, textureName, format, width, height, mips);
        commandEncoder.setupTexture(texture);
        return texture;
    }
    
    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<@Nullable String> bufferNameSupplier, BufferType bufferType, BufferUsage bufferUsage, int bufferSize) {
        @Nullable
        final var name = bufferNameSupplier != null ? bufferNameSupplier.get() : null;
        return switch (bufferUsage) {
            // single GPU buffer, barriers mid-frame
            case STATIC_WRITE -> new PersistentWriteBuffer(this, bufferType, bufferUsage, bufferSize, name);
            // multiple gpu buffers, uploads at beginning of frame
            case DYNAMIC_WRITE, STREAM_WRITE -> new PersistentWriteBuffer(this, bufferType, bufferUsage, bufferSize, name);
            // TODO: readback buffer
            case STATIC_READ, DYNAMIC_READ, STREAM_READ -> throw new NotImplemented(); // reading is equally shit regardless of hints, so same buffer type for them all
            // these are unused, and idk how they should get used really
            case DYNAMIC_COPY -> throw new NotImplemented();
            case STATIC_COPY -> throw new NotImplemented();
            case STREAM_COPY -> throw new NotImplemented();
        };
    }
    
    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> bufferNameSupplier, BufferType bufferType, BufferUsage bufferUsage, ByteBuffer bufferData) {
        final var buffer = createBuffer(bufferNameSupplier, bufferType, bufferUsage, bufferData.remaining());
        commandEncoder.writeToBuffer(buffer, bufferData, 0);
        return buffer;
    }
    
    @Override
    public String getImplementationInformation() {
        return getBackendName() + ", " + apiVersionUsed + ", " + renderer;
    }
    
    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }
    
    @Override
    public boolean isDebuggingEnabled() {
        return debugMarkerEnabled;
    }
    
    @Override
    public String getVendor() {
        return vendorString;
    }
    
    @Override
    public String getBackendName() {
        return "Cinnabar VK";
    }
    
    @Override
    public String getVersion() {
        return apiVersionUsed;
    }
    
    @Override
    public String getRenderer() {
        return physicalDeviceProperties.deviceNameString();
    }
    
    @Override
    public int getMaxTextureSize() {
        return physicalDeviceProperties.limits().maxImageDimension2D();
    }
    
    private final Map<RenderPipeline, CinnabarPipeline> pipelineCache = new IdentityHashMap<>();
    
    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline renderPipeline, @Nullable BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider) {
        return getPipeline(renderPipeline, shaderSourceProvider);
    }
    
    public CinnabarPipeline getPipeline(RenderPipeline renderPipeline) {
        return getPipeline(renderPipeline, null);
    }
    
    public CinnabarPipeline getPipeline(RenderPipeline renderPipeline, @Nullable BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider) {
        return pipelineCache.computeIfAbsent(renderPipeline, __ -> new CinnabarPipeline(this, shaderSourceProvider == null ? this.shaderSourceProvider : shaderSourceProvider, renderPipeline));
    }
    
    @Override
    public void clearPipelineCache() {
        vkDeviceWaitIdle(vkDevice);
        pipelineCache.values().forEach(CinnabarPipeline::destroy);
        pipelineCache.clear();
    }
    
    @Override
    public List<String> getEnabledExtensions() {
        return enabledDeviceExtensions;
    }
}
