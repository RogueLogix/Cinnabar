package graphics.cinnabar.core.mixin.mixins;

#if NEO

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import graphics.cinnabar.core.hg3d.Hg3DGpuDevice;
import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.blaze3d.validation.ValidationGpuDevice;
import net.neoforged.neoforge.client.config.NeoForgeClientConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BiFunction;
#endif

#if FABRIC

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import graphics.cinnabar.core.hg3d.Hg3DGpuDevice;
import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.BiFunction;
#endif

#if NEO
@Mixin(ClientHooks.class)
#elif FABRIC
@Mixin(RenderSystem.class)
#endif
public class DeviceCreationMixin {
    #if NEO
    @Inject(at = @At("HEAD"), method = "createGpuDevice", cancellable = true)
    private static void init(long window, int debugLevel, boolean syncDebug, BiFunction<ResourceLocation, ShaderType, String> defaultShaderSource, boolean enableDebugLabels, CallbackInfoReturnable<GpuDevice> info) {
        if (VulkanStartup.isSupported()) {
            info.setReturnValue(new Hg3DGpuDevice(window, debugLevel, syncDebug, defaultShaderSource, enableDebugLabels));
            boolean enableValidation;
            try {
                enableValidation = NeoForgeClientConfig.INSTANCE.enableB3DValidationLayer.getAsBoolean();
            } catch (NullPointerException | IllegalStateException e) {
                // We're in an early error state, config is not available. Assume environment default.
                enableValidation = NeoForgeClientConfig.INSTANCE.enableB3DValidationLayer.getDefault();
            }
            if (enableValidation) {
                info.setReturnValue(new ValidationGpuDevice(info.getReturnValue(), true));
            }
            info.cancel();
        }
    }
    #endif
    
    #if FABRIC
    @Shadow
    @Nullable
    private static GpuDevice DEVICE;
    @Shadow
    private static String apiDescription;
    @Shadow
    @Nullable
    private static DynamicUniforms dynamicUniforms;
    @Shadow
    @Nullable
    private static GpuBuffer QUAD_VERTEX_BUFFER;
    
    @Overwrite(remap = false)
    public static void initRenderer(long l, int i, boolean bl, BiFunction<ResourceLocation, ShaderType, String> biFunction, boolean bl2) {
        if (VulkanStartup.isSupported()) {
            DEVICE = new Hg3DGpuDevice(l, i, bl, biFunction, bl2);
        } else {
            DEVICE = new GlDevice(l, i, bl, biFunction, bl2);
        }
        apiDescription = DEVICE.getImplementationInformation();
        dynamicUniforms = new DynamicUniforms();
        
        try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION.getVertexSize() * 4)) {
            BufferBuilder bufferBuilder = new BufferBuilder(byteBufferBuilder, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            bufferBuilder.addVertex(0.0F, 0.0F, 0.0F);
            bufferBuilder.addVertex(1.0F, 0.0F, 0.0F);
            bufferBuilder.addVertex(1.0F, 1.0F, 0.0F);
            bufferBuilder.addVertex(0.0F, 1.0F, 0.0F);
            
            try (MeshData meshData = bufferBuilder.buildOrThrow()) {
                QUAD_VERTEX_BUFFER = DEVICE.createBuffer(() -> "Quad", 32, meshData.vertexBuffer());
            }
        }
        
    }
    #endif
}