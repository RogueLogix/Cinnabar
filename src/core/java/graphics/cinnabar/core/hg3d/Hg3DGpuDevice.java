package graphics.cinnabar.core.hg3d;

#if NEO

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.*;
import graphics.cinnabar.api.hg.*;
import graphics.cinnabar.api.hg.enums.HgFormat;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.util.MagicNumbers;
import graphics.cinnabar.lib.CinnabarLibBootstrapper;
import graphics.cinnabar.lib.threading.WorkQueue;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.blaze3d.GpuDeviceFeatures;
import net.neoforged.neoforge.client.blaze3d.GpuDeviceProperties;
import net.neoforged.neoforge.client.event.ConfigureGpuDeviceEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import static com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
#endif

#if FABRIC

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.textures.*;
import com.mojang.jtracy.TracyClient;
import graphics.cinnabar.api.c3d.C3DGpuDevice;
import graphics.cinnabar.api.hg.*;
import graphics.cinnabar.api.hg.enums.HgFormat;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.util.MagicNumbers;
import graphics.cinnabar.lib.CinnabarLibBootstrapper;
import graphics.cinnabar.lib.threading.QueueSystem;
import graphics.cinnabar.lib.threading.WorkQueue;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import static com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
#endif

public class Hg3DGpuDevice implements C3DGpuDevice {
    private static final String backendName = "CinnabarVK "
        #if NEO
            + FMLLoader.getCurrent().getLoadingModList().getModFileById("cinnabar").versionString();
        #else
            + FabricLoader.getInstance().getModContainer("cinnabar").get().getMetadata().getVersion().getFriendlyString();
        #endif
    
    private final HgDevice hgDevice;
    private final Hg3DCommandEncoder commandEncoder;
    
    private final ShaderSource shaderSourceProvider;
    
    private final HgSemaphore interFrameSemaphore;
    private final HgSemaphore cleanupDoneSemaphore;
    private final Map<RenderPipeline, Hg3DRenderPipeline> pipelineCache = new Reference2ReferenceOpenHashMap<>();
    private final Int2ReferenceMap<@Nullable HgRenderPass> renderPasses = new Int2ReferenceOpenHashMap<>();
    private final ReferenceArrayList<HgSampler> samplers = new ReferenceArrayList<>();
    private long currentFrame = MagicNumbers.MaximumFramesInFlight;
    private final ReferenceArrayList<ReferenceArrayList<Destroyable>> pendingDestroys = new ReferenceArrayList<>();
    private ReferenceArrayList<Destroyable> activelyDestroying = new ReferenceArrayList<>();
    private final Hg3DGpuBuffer.Manager bufferManager;
    
    public Hg3DGpuDevice(ShaderSource shaderSourceProvider, GpuDebugOptions debugOptions, HgDevice.CreateInfo createInfo) {
        CinnabarLibBootstrapper.bootstrap();
        this.shaderSourceProvider = shaderSourceProvider;
        
        #if NEO
        // no configurable features currently, result ignored
        NeoForge.EVENT_BUS.post(new ConfigureGpuDeviceEvent(deviceProperties(), enabledFeatures()));
        #endif
        
        hgDevice = Hg.createDevice(createInfo);
        commandEncoder = new Hg3DCommandEncoder(this);
        bufferManager = new Hg3DGpuBuffer.Manager(this);
        interFrameSemaphore = hgDevice.createSemaphore(0);
        cleanupDoneSemaphore = hgDevice.createSemaphore(0);
        WorkQueue.AFTER_END_OF_GPU_FRAME.wait(interFrameSemaphore, currentFrame);
        
        for (int i = 0; i < MagicNumbers.MaximumFramesInFlight; i++) {
            pendingDestroys.add(new ReferenceArrayList<>());
        }
    }
    
    @Override
    public void close() {
        // wait for pending GPU work
        interFrameSemaphore.waitValue(currentFrame - 1, -1L);
        // fake the GPU being done with work
        interFrameSemaphore.singlaValue(currentFrame);
        // wait for the cleanup thread to process the release of the semaphore
        WorkQueue.AFTER_END_OF_GPU_FRAME.signal(interFrameSemaphore, currentFrame + 1);
        interFrameSemaphore.waitValue(currentFrame + 1, -1L);
        interFrameSemaphore.destroy();
        
        int cleanSweepDestroy = 0;
        while(cleanSweepDestroy < pendingDestroys.size()) {
            cleanSweepDestroy++;
            activelyDestroying = pendingDestroys.set((int) (currentFrame % MagicNumbers.MaximumFramesInFlight), activelyDestroying);
            for (int i = 0; i < activelyDestroying.size(); i++) {
                activelyDestroying.get(i).destroy();
                cleanSweepDestroy = 0;
            }
            activelyDestroying.clear();
            currentFrame++;
        }
        
        QueueSystem.deviceShutdown(hgDevice);
        
        clearPipelineCache();
        bufferManager.destroy();
        commandEncoder.destroy();
        swapchain.destroy();
        surface.destroy();
        samplers.forEach(Destroyable::destroy);
        hgDevice.destroy();
    }
    
    public HgDevice hgDevice() {
        return hgDevice;
    }
    
    public long currentFrame() {
        return currentFrame;
    }
    
    public void endFrame() {
        try (final var _ = TracyClient.beginZone("Hg3DGpuDevice.endFrame", false)) {
            bufferManager.endOfFrame();
            WorkQueue.AFTER_END_OF_GPU_FRAME.signal(cleanupDoneSemaphore, currentFrame);
            commandEncoder.insertQueueItem(HgQueue.Item.signal(interFrameSemaphore, currentFrame, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));
            commandEncoder.flush();
            hgDevice.markFame();
            
            currentFrame++;
            
            WorkQueue.AFTER_END_OF_GPU_FRAME.wait(interFrameSemaphore, currentFrame);
            // wait for the cleanup of the last time this frame index was submitted
            // the semaphore starts at MaximumFramesInFlight, so this returns immediately for the first few frames
            cleanupDoneSemaphore.waitValue(currentFrame - MagicNumbers.MaximumFramesInFlight, -1L);
            activelyDestroying = pendingDestroys.set((int) (currentFrame % MagicNumbers.MaximumFramesInFlight), activelyDestroying);
            for (int i = 0; i < activelyDestroying.size(); i++) {
                activelyDestroying.get(i).destroy();
            }
            activelyDestroying.clear();
            commandEncoder.resetUploadBuffer();
        }
    }
    
    @Override
    public Hg3DCommandEncoder createCommandEncoder() {
        return commandEncoder;
    }
    
    @Override
    public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        return new Hg3DGpuSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }
    
    @Override
    public GpuTexture createTexture(@Nullable Supplier<String> label, int usage, TextureFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        @Nullable
        final var labelStr = label != null ? label.get() : null;
        assert mipLevels <= Integer.numberOfTrailingZeros(Math.max(width, height)) + 1;
        final var texture = new Hg3DGpuTexture(this, usage, labelStr != null ? labelStr : "Unknown Texture", format, width, height, depthOrLayers, mipLevels);
        createCommandEncoder().setupTexture(texture);
        return texture;
    }
    
    @Override
    public GpuTexture createTexture(@Nullable String label, int usage, TextureFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        final var texture = new Hg3DGpuTexture(this, usage, label != null ? label : "Unknown Texture", format, width, height, depthOrLayers, mipLevels);
        createCommandEncoder().setupTexture(texture);
        return texture;
    }
    
    @Override
    public GpuTextureView createTextureView(GpuTexture texture) {
        return new Hg3DGpuTextureView((Hg3DGpuTexture) texture, 0, texture.getMipLevels());
    }
    
    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        return new Hg3DGpuTextureView((Hg3DGpuTexture) texture, baseMipLevel, mipLevels);
    }
    
    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
        return bufferManager.create(label, usage, size, 32, null);
    }
    
    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
        if (label != null) {
            final var labelStr = label.get();
            if (labelStr.startsWith("Section vertex buffer")) {
                // this bit is added, but not needed, and prevent some of my optimizations from working right
                usage &= ~USAGE_COPY_DST;
            } else if (labelStr.startsWith("Immediate vertex buffer")) {
                // this is an immediate buffer, it'll be used _exactly once_
                // to prevent unneeded memcpys, im going to pull bullshit for uploading it
                return bufferManager.createImmediate(label, usage, data.remaining(), data);
            }
        }
        return bufferManager.create(label, usage, data.remaining(), 32, data);
    }
    
    @Override
    public String getImplementationInformation() {
        return getBackendName() + ", " + hgDevice.properties().apiVersion() + ", " + hgDevice.properties().renderer();
    }
    
    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }
    
    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }
    
    @Override
    public String getVendor() {
        return hgDevice.properties().vendor();
    }
    
    @Override
    public String getBackendName() {
        return backendName;
    }
    
    @Override
    public String getVersion() {
        return hgDevice.properties().apiVersion();
    }
    
    @Override
    public String getRenderer() {
        return hgDevice.properties().renderer() + " " + hgDevice.properties().driverVersion();
    }
    
    @Override
    public int getMaxTextureSize() {
        return hgDevice.properties().maxTexture2dSize();
    }
    
    @Override
    public int getUniformOffsetAlignment() {
        return (int) hgDevice.properties().uboAlignment();
    }
    
    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline renderPipeline, @Nullable ShaderSource shaderSource) {
        final var hg3dPipeline = getPipeline(renderPipeline, shaderSource == null ? shaderSourceProvider : shaderSource);
        // this is the most likely format(s) to be used
        // and will also trigger any validation errors
        hg3dPipeline.getPipeline(getRenderPass(HgFormat.RGBA8_UNORM, HgFormat.D32_SFLOAT));
        return hg3dPipeline;
    }
    
    @Override
    public void clearPipelineCache() {
        hgDevice.waitIdle();
        pipelineCache.values().forEach(Destroyable::destroy);
        pipelineCache.clear();
    }
    
    @Override
    public List<String> getEnabledExtensions() {
        return List.of();
    }
    
    @Override
    public int getMaxSupportedAnisotropy() {
        return (int) hgDevice.properties().maxAnisotropy();
    }
    
    #if NEO
    @Override
    public GpuDeviceProperties deviceProperties() {
        return new GpuDeviceProperties() {
            @Override
            public String backendName() {
                return "Cinnabar";
            }
            
            @Override
            public String apiName() {
                return "Hg";
            }
        };
    }
    
    @Override
    public GpuDeviceFeatures enabledFeatures() {
        return new GpuDeviceFeatures() {
            @Override
            public boolean logicOp() {
                return false;
            }
        };
    }
    #endif
    
    public void destroyEndOfFrame(Destroyable destroyable) {
        pendingDestroys.get((int) (currentFrame % MagicNumbers.MaximumFramesInFlight)).add(destroyable);
    }
    
    public void destroyEndOfFrameAsync(Destroyable destroyable) {
        WorkQueue.AFTER_END_OF_GPU_FRAME.enqueue(destroyable);
    }
    
    public void destroyEndOfFrame(List<? extends Destroyable> destroyable) {
        pendingDestroys.get((int) (currentFrame % MagicNumbers.MaximumFramesInFlight)).addAll(destroyable);
    }
    
    Hg3DRenderPipeline getPipeline(RenderPipeline pipeline) {
        return getPipeline(pipeline, shaderSourceProvider);
    }
    
    Hg3DRenderPipeline getPipeline(RenderPipeline pipeline, ShaderSource shaderSourceProvider) {
        return pipelineCache.computeIfAbsent(pipeline, pipe -> createPipeline(pipe, shaderSourceProvider));
    }
    
    private Hg3DRenderPipeline createPipeline(RenderPipeline pipeline, ShaderSource shaderSourceProvider) {
        return new Hg3DRenderPipeline(this, pipeline, shaderSourceProvider);
    }
    
    public HgRenderPass getRenderPass(HgFormat colorFormat, @Nullable HgFormat depthStencilFormat) {
        final int formatsId = colorFormat.ordinal() << 16 | (depthStencilFormat != null ? depthStencilFormat.ordinal() : 0);
        @Nullable
        final var renderpass = renderPasses.get(formatsId);
        if (renderpass != null) {
            return renderpass;
        }
        final var newRenderPass = hgDevice.createRenderPass(new HgRenderPass.CreateInfo(List.of(colorFormat), depthStencilFormat));
        renderPasses.put(formatsId, newRenderPass);
        return newRenderPass;
    }
    
    @Nullable
    private HgSurface surface;
    @Nullable
    private HgSurface.Swapchain swapchain;
    private boolean swapchainInvalid = false;
    private boolean shouldVsync = false;
    private boolean isVsync = false;
    
    public void attachWindow(long window) {
        surface = hgDevice.createSurface(window);
        swapchain = surface.createSwapchain(isVsync, null);
        swapchain.acquire();
    }
    
    @Nullable
    HgSurface.Swapchain swapchain() {
        if (swapchainInvalid) {
            return null;
        }
        assert swapchain != null;
        return swapchain;
    }
    
    @Override
    public void setVsync(boolean enabled) {
        shouldVsync = enabled;
    }
    
    @Override
    public void presentFrame() {
        endFrame();
        
        final var window = Minecraft.getInstance().getWindow();
        
        try (final var _ = TracyClient.beginZone("Hg3DGpuDevice.swapchainPresent", false)) {
            assert swapchain != null;
            
            if (swapchainInvalid) {
                window.refreshFramebufferSize();
                if (window.getWidth() <= 1 && window.getHeight() <= 1) {
                    // can't do shit cap'n
                    return;
                }
                recreateSwapchain();
                window.eventHandler.resizeDisplay();
                swapchainInvalid = !swapchain.acquire();
                return;
            }
            
            boolean shouldRecreateSwapchain = swapchainInvalid = !swapchain.present();
            shouldRecreateSwapchain = shouldRecreateSwapchain || window.getWidth() != swapchain.width();
            shouldRecreateSwapchain = shouldRecreateSwapchain || window.getHeight() != swapchain.height();
            
            if (swapchainInvalid) {
                // can't do anything when the window is mimized, just early out
                return;
            }
            
            if (shouldVsync != isVsync) {
                isVsync = shouldVsync;
                shouldRecreateSwapchain = true;
            }
            
            if (shouldRecreateSwapchain) {
                window.refreshFramebufferSize();
                recreateSwapchain();
                window.eventHandler.resizeDisplay();
            }
            
            while (!swapchain.acquire()) {
                swapchainInvalid = true;
                // if the swapchain failed to acquire here,
                window.refreshFramebufferSize();
                recreateSwapchain();
                window.eventHandler.resizeDisplay();
            }
        }
    }
    
    private void recreateSwapchain() {
        assert surface != null;
        swapchain = surface.createSwapchain(isVsync, swapchain);
        swapchainInvalid = false;
    }
    
    @Override
    public boolean isZZeroToOne() {
        return true;
    }
}
