package graphics.cinnabar.core.b3d;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.api.b3dext.pipeline.ExtRenderPipeline;
import graphics.cinnabar.api.b3dext.systems.ExtCapabilities;
import graphics.cinnabar.api.b3dext.textures.ExtGpuTexture;
import graphics.cinnabar.api.cvk.buffers.CVKGpuBuffer;
import graphics.cinnabar.api.cvk.configuration.CVKDeviceFeatures;
import graphics.cinnabar.api.cvk.configuration.CVKDeviceProperties;
import graphics.cinnabar.api.cvk.pipeline.CVKCompiledRenderPipeline;
import graphics.cinnabar.api.cvk.systems.CVKGpuDevice;
import graphics.cinnabar.api.cvk.textures.CVKGpuTexture;
import graphics.cinnabar.api.cvk.textures.CVKGpuTextureView;
import graphics.cinnabar.api.threading.IWorkQueue;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.CinnabarCore;
import graphics.cinnabar.core.DriverWorkarounds;
import graphics.cinnabar.core.b3d.buffers.BufferPool;
import graphics.cinnabar.core.b3d.buffers.CinnabarIndividualGpuBuffer;
import graphics.cinnabar.core.b3d.command.CinnabarCommandEncoder;
import graphics.cinnabar.core.b3d.configuration.CinnabarGpuDeviceFeatures;
import graphics.cinnabar.core.b3d.configuration.CinnabarGpuDeviceProperties;
import graphics.cinnabar.core.b3d.configuration.ConfigureCinnabarDeviceEvent;
import graphics.cinnabar.core.b3d.pipeline.CinnabarRenderPipeline;
import graphics.cinnabar.core.b3d.texture.CinnabarGpuTexture;
import graphics.cinnabar.core.b3d.texture.CinnabarGpuTextureView;
import graphics.cinnabar.core.b3d.window.CinnabarWindow;
import graphics.cinnabar.core.util.MagicNumbers;
import graphics.cinnabar.core.vk.VulkanSampler;
import graphics.cinnabar.lib.util.MathUtil;
import graphics.cinnabar.lib.vulkan.VulkanDebug;
import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.blaze3d.GpuDeviceFeatures;
import net.neoforged.neoforge.client.blaze3d.GpuDeviceProperties;
import net.neoforged.neoforge.client.config.NeoForgeClientConfig;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static graphics.cinnabar.core.CinnabarConfig.CONFIG;
import static graphics.cinnabar.core.CinnabarCore.CINNABAR_CORE_LOG;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.EXTDebugMarker.VK_EXT_DEBUG_MARKER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.VK12.*;

public class CinnabarDevice implements CVKGpuDevice {
    
    private final CVKDeviceProperties deviceProperties;
    private final CVKDeviceFeatures enabledFeatures;
    
    public final VkInstance vkInstance;
    private final long debugCallback;
    private final List<String> enabledLayersAndInstanceExtensions;
    
    public final VkPhysicalDevice vkPhysicalDevice;
    public final VkPhysicalDeviceProperties2 physicalDeviceProperties2 = VkPhysicalDeviceProperties2.calloc().sType$Default();
    public final VkPhysicalDeviceProperties physicalDeviceProperties = physicalDeviceProperties2.properties();
    public final VkPhysicalDeviceVulkan12Properties physicalDevice12Properties = VkPhysicalDeviceVulkan12Properties.calloc().sType$Default();
    public final VkPhysicalDeviceLimits limits = physicalDeviceProperties.limits();
    
    public final String vendorString;
    public final String apiVersionUsed;
    public final String driverVersion;
    public final String renderer;
    
    public final VkDevice vkDevice;
    public final DriverWorkarounds workarounds;
    
    public final VkQueue graphicsQueue;
    public final int graphicsQueueFamily;
    public final VkQueue computeQueue;
    public final int computeQueueFamily;
    public final VkQueue transferQueue;
    public final int transferQueueFamily;
    
    public final List<String> enabledDeviceExtensions;
    public final boolean debugMarkerEnabled;
    
    // starts at frame n instead of 0 for initial sync reasons
    private long currentFrame = MagicNumbers.MaximumFramesInFlight;
    
    public final long vmaAllocator;
    public final IntIntImmutablePair deviceMemoryType;
    public final IntIntImmutablePair hostMemoryType;
    
    public final BufferPool vertexBufferPool;
    public final BufferPool indexBufferPool;
    public final List<BufferPool> uploadPools;
    
    private final BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider;
    
    public CinnabarDevice(long windowHandle, int debugLevel, boolean syncDebug, BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider, boolean debugLabels) {
        if (CinnabarCore.cinnabarDeviceSingleton != null) {
            throw new IllegalStateException("Cannot have more that one CinnabarDevice active at a time");
        }
        CinnabarCore.cinnabarDeviceSingleton = this;
        this.shaderSourceProvider = shaderSourceProvider;
        CINNABAR_CORE_LOG.info("Initializing CinnabarDevice");
        try (final var stack = MemoryStack.stackPush()) {
            final var debugCreateInfo = VulkanDebug.getCreateInfo(stack, new VulkanDebug.MessageSeverity[]{VulkanDebug.MessageSeverity.ERROR, VulkanDebug.MessageSeverity.WARNING, VulkanDebug.MessageSeverity.INFO}, new VulkanDebug.MessageType[]{VulkanDebug.MessageType.GENERAL, VulkanDebug.MessageType.VALIDATION});
            final var instanceAndDebugCallback = VulkanStartup.createVkInstance(NeoForgeClientConfig.INSTANCE.enableB3DValidationLayer.getAsBoolean(), CONFIG.EnableMesaOverlay, debugCreateInfo);
            vkInstance = instanceAndDebugCallback.instance();
            debugCallback = instanceAndDebugCallback.debugCallback();
            enabledLayersAndInstanceExtensions = instanceAndDebugCallback.enabledInsanceExtensions();
        }
        
        try {
            vkPhysicalDevice = VulkanStartup.selectPhysicalDevice(vkInstance, CONFIG.ManualDeviceSelection, CONFIG.ForcedVulkanDeviceIndex, enabledLayersAndInstanceExtensions);
        } catch (Exception e) {
            if (debugCallback != -1) {
                vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
            }
            vkDestroyInstance(vkInstance, null);
            throw e;
        }
        
        physicalDeviceProperties2.pNext(physicalDevice12Properties);
        vkGetPhysicalDeviceProperties2(vkPhysicalDevice, physicalDeviceProperties2);
        
        deviceProperties = new CinnabarGpuDeviceProperties.Immutable(new CinnabarGpuDeviceProperties());
        final var event = net.neoforged.fml.ModLoader.postEventWithReturn(new ConfigureCinnabarDeviceEvent(deviceProperties, new CinnabarGpuDeviceFeatures()));
        enabledFeatures = new CinnabarGpuDeviceFeatures.Immutable(event);
        
        final VulkanStartup.Device deviceAndQueues;
        try {
            deviceAndQueues = VulkanStartup.createLogicalDeviceAndQueues(vkInstance, vkPhysicalDevice, enabledLayersAndInstanceExtensions);
        } catch (Exception e) {
            if (debugCallback != -1) {
                vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
            }
            vkDestroyInstance(vkInstance, null);
            throw e;
        }
        
        vkDevice = deviceAndQueues.device();
        final var queues = deviceAndQueues.queues();
        graphicsQueue = queues.get(0).queue();
        graphicsQueueFamily = queues.get(0).queueFamily();
        computeQueue = queues.get(1).queue();
        computeQueueFamily = queues.get(1).queueFamily();
        transferQueue = queues.get(2).queue();
        transferQueueFamily = queues.get(2).queueFamily();
        
        enabledDeviceExtensions = deviceAndQueues.enabledDeviceExtensions();
        debugMarkerEnabled = enabledDeviceExtensions.contains(VK_EXT_DEBUG_MARKER_EXTENSION_NAME);
        
        final var APIVersionEncoded = physicalDeviceProperties.apiVersion();
        final var driverVersionEncoded = physicalDeviceProperties.driverVersion();
        vendorString = switch (physicalDeviceProperties.vendorID()) {
            case 0x1002, 0x1022 -> "AMD";
            case 0x8086 -> "Intel";
            case 0x10DE, 0x12D2 -> "Nvidia";
            case 0x1969, 0x168c, 0x17CB, 0x5143 -> "Qualcomm";
            default -> String.format("0x%x", physicalDeviceProperties.vendorID());
        };
        apiVersionUsed = String.format("Vulkan %d.%d.%d", VK_VERSION_MAJOR(APIVersionEncoded), VK_VERSION_MINOR(APIVersionEncoded), VK_VERSION_PATCH(APIVersionEncoded));
        driverVersion = String.format("%d.%d.%d", VK_VERSION_MAJOR(driverVersionEncoded), VK_VERSION_MINOR(driverVersionEncoded), VK_VERSION_PATCH(driverVersionEncoded));
        renderer = String.format("%s", physicalDeviceProperties.deviceNameString());
        
        workarounds = new DriverWorkarounds(vkDevice);
        
        try (final var stack = MemoryStack.stackPush()) {
            
            final var vmaVulkanFunctions = VmaVulkanFunctions.calloc(stack);
            vmaVulkanFunctions.set(vkInstance, vkDevice);
            
            final var allocatorCreateInfo = VmaAllocatorCreateInfo.calloc(stack);
            allocatorCreateInfo.flags(0);
            allocatorCreateInfo.vulkanApiVersion(VK_API_VERSION_1_2);
            allocatorCreateInfo.physicalDevice(vkPhysicalDevice);
            allocatorCreateInfo.device(vkDevice);
            allocatorCreateInfo.instance(vkInstance);
            allocatorCreateInfo.pVulkanFunctions(vmaVulkanFunctions);
            
            final var handlePtr = stack.pointers(0);
            vmaCreateAllocator(allocatorCreateInfo, handlePtr);
            vmaAllocator = handlePtr.get(0);
        }
        
        deviceMemoryType = pickMemoryType(VK_MEMORY_HEAP_DEVICE_LOCAL_BIT, 0);
        hostMemoryType = pickMemoryType(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
        
        commandEncoder = new CinnabarCommandEncoder(this);
        ((CinnabarWindow) Minecraft.getInstance().getWindow()).attachDevice(this);
        
        vertexBufferPool = new BufferPool(this, deviceMemoryType, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, MathUtil.MBToB(64), "vertex", false);
        indexBufferPool = new BufferPool(this, deviceMemoryType, GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, MathUtil.MBToB(16), "index", false);
        destroyOnShutdown(vertexBufferPool);
        destroyOnShutdown(indexBufferPool);
        final var uploadPools = new ReferenceArrayList<BufferPool>();
        for (int i = 0; i < MagicNumbers.MaximumFramesInFlight; i++) {
            final var uploadPool = new BufferPool(this, hostMemoryType, GpuBuffer.USAGE_COPY_SRC, MathUtil.MBToB(16), "upload", true);
            destroyOnShutdown(uploadPool);
            uploadPools.add(uploadPool);
        }
        this.uploadPools = Collections.unmodifiableList(uploadPools);
        
        NeoForge.EVENT_BUS.register(this);
    }
    
    @Override
    public void close() {
        NeoForge.EVENT_BUS.unregister(this);
        
        clearPipelineCache();
        
        vkDeviceWaitIdle(vkDevice);
        
        ((CinnabarWindow) Minecraft.getInstance().getWindow()).detachDevice();
        
        VulkanSampler.shutdown();
        
        commandEncoder.destroy();
        
        toDestroy.forEach(list -> list.forEach(Destroyable::destroy));
        toDestroy.clear();
        
        shutdownDestroy.forEach(Destroyable::destroy);
        shutdownDestroy.clear();
        
        vmaDestroyAllocator(vmaAllocator);
        vkDestroyDevice(vkDevice, null);
        if (debugCallback != -1) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
        }
        vkDestroyInstance(vkInstance, null);
        CinnabarCore.cinnabarDeviceSingleton = null;
        
        physicalDeviceProperties2.free();
        physicalDevice12Properties.free();
        
        CINNABAR_CORE_LOG.info("CinnabarDevice Shutdown");
    }
    
    private final ReferenceArrayList<Destroyable> submitDestroy = new ReferenceArrayList<>();
    private final ReferenceArrayList<ReferenceArrayList<Destroyable>> toDestroy = new ReferenceArrayList<>();
    private final ReferenceArrayList<Destroyable> shutdownDestroy = new ReferenceArrayList<>();
    
    {
        for (int i = 0; i < MagicNumbers.MaximumFramesInFlight; i++) {
            toDestroy.add(new ReferenceArrayList<>());
        }
    }
    
    public void newFrame() {
        currentFrame++;
    }
    
    public void startFrame() {
        submitDestroy.forEach(Destroyable::destroy);
        submitDestroy.clear();
        toDestroy.get(currentFrameIndex()).forEach(Destroyable::destroy);
        toDestroy.get(currentFrameIndex()).clear();
        vertexBufferPool.processRealloc();
        indexBufferPool.processRealloc();
        uploadPools.get(currentFrameIndex()).processRealloc();
    }
    
    public long currentFrame() {
        return currentFrame;
    }
    
    public int currentFrameIndex() {
        return (int) (currentFrame % MagicNumbers.MaximumFramesInFlight);
    }
    
    public void idleAndClear() {
        // waits for device idle, and destroys anything pending
        // this is rarely useful
        vkDeviceWaitIdle(vkDevice);
        toDestroy.forEach(list -> list.forEach(Destroyable::destroy));
        toDestroy.forEach(ReferenceArrayList::clear);
    }
    
    public <T extends Destroyable> T destroyAfterSubmit(T destroyable) {
        submitDestroy.add(destroyable);
        return destroyable;
    }
    
    public <T extends Destroyable> T destroyEndOfFrame(T destroyable) {
        IWorkQueue.AFTER_END_OF_GPU_FRAME.enqueue(destroyable);
        return destroyable;
    }
    
    public synchronized <T extends Destroyable> T destroyOnShutdown(T destroyable) {
        shutdownDestroy.add(destroyable);
        return destroyable;
    }
    
    @Override
    public void endFrame() {
        commandEncoder.endFrame();
    }
    
    public IntIntImmutablePair pickMemoryType(int requiredProperties, int preferredProperties) {
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
        return new IntIntImmutablePair(selectedMemoryType, selectedMemoryTypeBits);
    }
    
    @SubscribeEvent
    private void handleDebugTextEvent(CustomizeGuiOverlayEvent.DebugText event) {
        final var list = event.getRight();
        list.add("");
        if (validationLayersEnabled()) {
            list.add("Validation layers enabled");
        } else if (!FMLEnvironment.production) {
            list.add("Validation layers disabled");
        }
        try (final var stack = MemoryStack.stackPush()) {
            final var stats = VmaBudget.calloc(VK_MAX_MEMORY_HEAPS, stack);
            vmaGetHeapBudgets(vmaAllocator, stats);
            for (int i = 0; i < VK_MAX_MEMORY_HEAPS; i++) {
                stats.position(i);
                if (stats.usage() == 0) {
                    continue;
                }
                list.add(String.format("Heap %d usage: %s/%s/%s", i, MathUtil.byteString(stats.statistics().allocationBytes()), MathUtil.byteString(stats.statistics().blockBytes()), MathUtil.byteString(stats.budget())));
            }
        }
        list.add(String.format("Pending destroys: %05d", toDestroy.stream().mapToInt(ReferenceArrayList::size).sum() + submitDestroy.size()));
        list.addAll(commandEncoder.debugStrings());
    }
    
    private final Int2LongMap renderPasses = new Int2LongOpenHashMap();
    
    public long getRenderPass(TextureFormat colorFormat, @Nullable TextureFormat depthFormat) {
        final int formatsId = colorFormat.ordinal() << 16 | (depthFormat != null ? depthFormat.ordinal() : 0);
        final var renderpass = renderPasses.get(formatsId);
        if (renderpass != 0) {
            return renderpass;
        }
        try (final var stack = MemoryStack.stackPush()) {
            
            final var attachmentDescriptions = VkAttachmentDescription2.calloc(2, stack).sType$Default();
            attachmentDescriptions.format(CinnabarGpuTexture.toVk(colorFormat));
            attachmentDescriptions.samples(VK_SAMPLE_COUNT_1_BIT);
            attachmentDescriptions.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            attachmentDescriptions.loadOp(VK_ATTACHMENT_STORE_OP_STORE);
            attachmentDescriptions.initialLayout(VK_IMAGE_LAYOUT_GENERAL);
            attachmentDescriptions.finalLayout(VK_IMAGE_LAYOUT_GENERAL);
            attachmentDescriptions.limit(1);
            if (depthFormat != null) {
                attachmentDescriptions.limit(2);
                attachmentDescriptions.position(1).sType$Default();
                attachmentDescriptions.format(CinnabarGpuTexture.toVk(depthFormat));
                attachmentDescriptions.samples(VK_SAMPLE_COUNT_1_BIT);
                attachmentDescriptions.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
                attachmentDescriptions.loadOp(VK_ATTACHMENT_STORE_OP_STORE);
                attachmentDescriptions.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
                attachmentDescriptions.stencilLoadOp(VK_ATTACHMENT_STORE_OP_STORE);
                attachmentDescriptions.initialLayout(VK_IMAGE_LAYOUT_GENERAL);
                attachmentDescriptions.finalLayout(VK_IMAGE_LAYOUT_GENERAL);
                attachmentDescriptions.position(0);
            }
            
            final var subpassDescription = VkSubpassDescription2.calloc(1, stack).sType$Default();
            subpassDescription.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);
            final var colorReference = VkAttachmentReference2.calloc(1, stack).sType$Default();
            colorReference.attachment(0);
            colorReference.layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            colorReference.aspectMask(CinnabarGpuTexture.aspects(colorFormat));
            subpassDescription.pColorAttachments(colorReference);
            subpassDescription.colorAttachmentCount(1);
            if (depthFormat != null) {
                final var depthReference = VkAttachmentReference2.calloc(stack).sType$Default();
                depthReference.attachment(1);
                depthReference.layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                depthReference.aspectMask(CinnabarGpuTexture.aspects(colorFormat));
                subpassDescription.pDepthStencilAttachment(depthReference);
            }
            
            final var passCreateInfo = VkRenderPassCreateInfo2.calloc(stack).sType$Default();
            passCreateInfo.pAttachments(attachmentDescriptions);
            passCreateInfo.pSubpasses(subpassDescription);
            
            final var renderPassPtr = stack.longs(0);
            checkVkCode(vkCreateRenderPass2(vkDevice, passCreateInfo, null, renderPassPtr));
            renderPasses.put(formatsId, renderPassPtr.get(0));
            return renderPassPtr.get(0);
        }
    }
    
    // --------- CVKGpuDevice ---------
    
    @Override
    public VkInstance vkInstance() {
        return vkInstance;
    }
    
    @Override
    public VkDevice vkDevice() {
        return vkDevice;
    }
    
    public boolean cinnabarDebugModeEnabled() {
        return false;
    }
    
    public boolean validationLayersEnabled() {
        return enabledLayersAndInstanceExtensions.contains("VK_LAYER_KHRONOS_validation");
    }
    
    public boolean debugMarkerEnabled() {
        return debugMarkerEnabled;
    }
    
    @Override
    public VkQueue graphicsQueue() {
        return graphicsQueue;
    }
    
    @Override
    public int graphicsQueueFamily() {
        return graphicsQueueFamily;
    }
    
    @Override
    public VkQueue computeQueue() {
        return computeQueue;
    }
    
    @Override
    public int computeQueueFamily() {
        return computeQueueFamily;
    }
    
    @Override
    public VkQueue transferQueue() {
        return transferQueue;
    }
    
    @Override
    public int transferQueueFamily() {
        return transferQueueFamily;
    }
    
    // --------- ExtGpuDevice ---------
    
    private static final ExtCapabilities extCapabilities = new ExtCapabilities();
    
    @Override
    public ExtCapabilities extCapabilities() {
        return extCapabilities;
    }
    
    @Override
    public CinnabarGpuTexture creatUninitializedTexture(@Nullable String label, int usage, ExtGpuTexture.Type type, TextureFormat format, int width, int height, int depth, int layers, int mips) {
        if (label == null) {
            label = "Texture";
        }
        return new CinnabarGpuTexture(this, usage, label, type, format, width, height, depth, layers, mips);
    }
    
    @Override
    public CVKGpuTexture createTexture(@Nullable String label, int usage, ExtGpuTexture.Type type, TextureFormat format, int width, int height, int depth, int layers, int mips) {
        final var texture = creatUninitializedTexture(label, usage, type, format, width, height, depth, layers, mips);
        commandEncoder.setupTexture(texture);
        return texture;
    }
    
    @Override
    public CVKGpuTexture createTexture(@Nullable Supplier<String> label, int usage, ExtGpuTexture.Type type, TextureFormat format, int width, int height, int depth, int layers, int mips) {
        @Nullable
        var textureName = label != null && debugMarkerEnabled ? label.get() : null;
        return createTexture(textureName, usage, type, format, width, height, depth, layers, mips);
    }
    
    @Override
    public CVKGpuTextureView createTextureView(ExtGpuTexture texture, ExtGpuTexture.Type type, TextureFormat format, int baseMipLevel, int mipLevels, int baseArrayLayer, int layerCount) {
        return new CinnabarGpuTextureView(this, (CinnabarGpuTexture) texture, type, format, baseMipLevel, mipLevels, baseArrayLayer, layerCount);
    }
    
    // --------- Neo GpuDeviceExtension ---------
    
    @Override
    public GpuDeviceProperties deviceProperties() {
        return deviceProperties;
    }
    
    @Override
    public GpuDeviceFeatures enabledFeatures() {
        return enabledFeatures;
    }
    
    // --------- Vanilla GpuDevice ---------
    
    private final CinnabarCommandEncoder commandEncoder;
    
    @Override
    public CinnabarCommandEncoder createCommandEncoder() {
        return commandEncoder;
    }
    
    @Override
    public CVKGpuBuffer createBuffer(@Nullable Supplier<String> bufferNameSupplier, int usage, int bufferSize) {
        @Nullable
        final var name = bufferNameSupplier != null ? bufferNameSupplier.get() : null;
        if (vertexBufferPool.canAllocate(usage)) {
            return vertexBufferPool.alloc(usage, bufferSize, name);
        }
        if (indexBufferPool.canAllocate(usage)) {
            return indexBufferPool.alloc(usage, bufferSize, name);
        }
        return new CinnabarIndividualGpuBuffer(this, usage, bufferSize, name);
    }
    
    @Override
    public CVKGpuBuffer createBuffer(@Nullable Supplier<String> bufferNameSupplier, int bufferUsage, ByteBuffer bufferData) {
        final var buffer = createBuffer(bufferNameSupplier, bufferUsage, bufferData.remaining());
        commandEncoder.writeToBuffer(buffer.slice(), bufferData);
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
        return "CinnabarVK";
    }
    
    @Override
    public String getVersion() {
        return apiVersionUsed;
    }
    
    @Override
    public String getRenderer() {
        return physicalDeviceProperties.deviceNameString() + " " + physicalDevice12Properties.driverInfoString();
    }
    
    @Override
    public int getMaxTextureSize() {
        return physicalDeviceProperties.limits().maxImageDimension2D();
    }
    
    private final Map<RenderPipeline, CinnabarRenderPipeline> pipelineCache = new IdentityHashMap<>();
    
    @Override
    public CVKCompiledRenderPipeline precompilePipeline(RenderPipeline renderPipeline, @Nullable BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider) {
        return getPipeline(renderPipeline, shaderSourceProvider);
    }
    
    @Override
    public int getUniformOffsetAlignment() {
        return Math.toIntExact(limits.minUniformBufferOffsetAlignment());
    }
    
    public CinnabarRenderPipeline getPipeline(RenderPipeline renderPipeline) {
        return getPipeline(renderPipeline, null);
    }
    
    public CinnabarRenderPipeline getPipeline(RenderPipeline renderPipeline, @Nullable BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider) {
        assert renderPipeline instanceof ExtRenderPipeline;
        return pipelineCache.computeIfAbsent(renderPipeline, __ -> new CinnabarRenderPipeline(this, (ExtRenderPipeline) renderPipeline, shaderSourceProvider == null ? this.shaderSourceProvider : shaderSourceProvider));
    }
    
    @Override
    public void clearPipelineCache() {
        vkDeviceWaitIdle(vkDevice);
//        CinnabarRenderPipeline.clearSourceCache();
        pipelineCache.values().forEach(Destroyable::destroy);
        pipelineCache.clear();
    }
    
    @Override
    public List<String> getEnabledExtensions() {
        return enabledDeviceExtensions;
    }
}
