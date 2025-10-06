package graphics.cinnabar.core.hg3d;

#if NEO

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
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
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.blaze3d.GpuDeviceFeatures;
import net.neoforged.neoforge.client.blaze3d.GpuDeviceProperties;
import net.neoforged.neoforge.client.event.ConfigureGpuDeviceEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
#endif

#if FABRIC
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
#endif

public class Hg3DGpuDevice implements GpuDevice {
    private static final String backendName = "CinnabarVK "
        #if NEO
            + FMLLoader.getCurrent().getLoadingModList().getModFileById("cinnabar").versionString();
        #else
             + FabricLoader.getInstance().getModContainer("cinnabar").get().getMetadata().getVersion().getFriendlyString();
        #endif
    
    private final HgDevice hgDevice;
    private final Hg3DCommandEncoder commandEncoder;
    
    private final BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider;
    
    private final HgSemaphore interFrameSemaphore;
    private final HgSemaphore cleanupDoneSemaphore;
    private final Map<RenderPipeline, Hg3DRenderPipeline> pipelineCache = new Reference2ReferenceOpenHashMap<>();
    private final Int2ReferenceMap<@Nullable HgRenderPass> renderPasses = new Int2ReferenceOpenHashMap<>();
    private final ReferenceArrayList<HgSampler> samplers = new ReferenceArrayList<>();
    private long currentFrame = MagicNumbers.MaximumFramesInFlight;
    private final ReferenceArrayList<ReferenceArrayList<Destroyable>> pendingDestroys = new ReferenceArrayList<>();
    private ReferenceArrayList<Destroyable> activelyDestroying = new ReferenceArrayList<>();
    private final Hg3DGpuBuffer.Manager bufferManager;
    
    public Hg3DGpuDevice(long windowHandle, int debugLevel, boolean syncDebug, BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider, boolean debugLabels) {
        CinnabarLibBootstrapper.bootstrap();
        this.shaderSourceProvider = shaderSourceProvider;
        
        #if NEO
        // no configurable features currently, result ignored
        NeoForge.EVENT_BUS.post(new ConfigureGpuDeviceEvent(deviceProperties(), enabledFeatures()));
        #endif
        
        hgDevice = Hg.createDevice(new HgDevice.CreateInfo());
        commandEncoder = new Hg3DCommandEncoder(this);
        bufferManager = new Hg3DGpuBuffer.Manager(this);
        initSamplers();
        ((Hg3DWindow) Minecraft.getInstance().getWindow()).attachDevice(this);
        interFrameSemaphore = hgDevice.createSemaphore(0);
        cleanupDoneSemaphore = hgDevice.createSemaphore(0);
        WorkQueue.AFTER_END_OF_GPU_FRAME.wait(interFrameSemaphore, currentFrame);
        
        for (int i = 0; i < MagicNumbers.MaximumFramesInFlight; i++) {
            pendingDestroys.add(new ReferenceArrayList<>());
        }

        #if NEO
        NeoForge.EVENT_BUS.register(this);
        #endif
    }
    
    @Override
    public void close() {
        #if NEO
        NeoForge.EVENT_BUS.unregister(this);
        #endif
        
        // wait for pending GPU work
        interFrameSemaphore.waitValue(currentFrame - 1, -1L);
        // fake the GPU being done with work
        interFrameSemaphore.singlaValue(currentFrame);
        // wait for the cleanup thread to process the release of the semaphore
        WorkQueue.AFTER_END_OF_GPU_FRAME.signal(interFrameSemaphore, currentFrame + 1);
        interFrameSemaphore.waitValue(currentFrame + 1, -1L);
        interFrameSemaphore.destroy();
        
        bufferManager.destroy();
        commandEncoder.destroy();
        ((Hg3DWindow) Minecraft.getInstance().getWindow()).detachDevice();
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
        bufferManager.endOfFrame();
        WorkQueue.AFTER_END_OF_GPU_FRAME.signal(cleanupDoneSemaphore, currentFrame);
        commandEncoder.flush();
        hgDevice.queue(HgQueue.Type.GRAPHICS).submit(HgQueue.Item.signal(interFrameSemaphore, currentFrame, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));
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
    
    @Override
    public Hg3DCommandEncoder createCommandEncoder() {
        return commandEncoder;
    }
    
    @Override
    public GpuTexture createTexture(@Nullable Supplier<String> label, int usage, TextureFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        final var texture = new Hg3DGpuTexture(this, usage, "TODO: replace me", format, width, height, depthOrLayers, mipLevels);
        createCommandEncoder().setupTexture(texture);
        return texture;
    }
    
    @Override
    public GpuTexture createTexture(@Nullable String label, int usage, TextureFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        final var texture = new Hg3DGpuTexture(this, usage, "TODO: replace me", format, width, height, depthOrLayers, mipLevels);
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
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, int size) {
        return bufferManager.create(usage, size, 32, null);
    }
    
    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
        return bufferManager.create(usage, data.remaining(), 32, data);
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
    public CompiledRenderPipeline precompilePipeline(RenderPipeline renderPipeline, @Nullable BiFunction<ResourceLocation, ShaderType, String> shaderSource) {
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
    
    Hg3DRenderPipeline getPipeline(RenderPipeline pipeline, BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider) {
        return pipelineCache.computeIfAbsent(pipeline, pipe -> createPipeline(pipe, shaderSourceProvider));
    }
    
    private Hg3DRenderPipeline createPipeline(RenderPipeline pipeline, BiFunction<ResourceLocation, ShaderType, String> shaderSourceProvider) {
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
    
    private void initSamplers() {
        // bit 1: minLinear
        // bit 2: magLinear
        // bit 3: mip
        // bit 4-5: adddressU
        // bit 6-7: adddressV
        // bit 8-9: adddressW
        for (int i = 0; i < 512; i++) {
            boolean minLinear = (i & 0x1) != 0;
            boolean magLinear = (i & (0x1 << 1)) != 0;
            int addressU = (i >> 3) & 0x3;
            int addressV = (i >> 5) & 0x3;
            int addressW = (i >> 7) & 0x3;
            boolean mip = (i & (0x1 << 2)) != 0;
            samplers.add(hgDevice.createSampler(new HgSampler.CreateInfo(minLinear, magLinear, addressU, addressV, addressW, mip)));
        }
    }
    
    public HgSampler getSampler(boolean minLinear, boolean magLinear, int addressU, int addressV, int addressW, boolean mip) {
        int index = 0;
        if (minLinear) {
            index |= 0x1;
        }
        if (magLinear) {
            index |= 0x2;
        }
        if (mip) {
            index |= 0x4;
        }
        index |= (0x3 & addressU) << 3;
        index |= (0x3 & addressV) << 5;
        index |= (0x3 & addressW) << 7;
        return samplers.get(index);
    }
    
    #if NEO
    @SubscribeEvent
    private void handleDebugTextEvent(CustomizeGuiOverlayEvent.DebugText event) {
        final var list = event.getRight();
        list.add("");
        hgDevice.addDebugText(list);
    }
    #endif
    
    public void advanceFramesForEviction() {
        // advances enough frames that nothing can be in-flight on the GPU anymore
        // this is only hit in extreme memory pressure circumstances
        for (int i = 0; i < MagicNumbers.MaximumFramesInFlight; i++) {
            endFrame();
        }
    }
}
