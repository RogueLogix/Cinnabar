package graphics.cinnabar.core.mixin.mixins;

#if NEO

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import graphics.cinnabar.core.hg3d.Hg3DGpuDevice;
import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import net.minecraft.resources.Identifier;
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
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.SamplerCache;
import com.mojang.blaze3d.vertex.*;
import graphics.cinnabar.core.hg3d.Hg3DGpuDevice;
import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.resources.Identifier;
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
    private static void init(long window, int debugLevel, boolean syncDebug, BiFunction<Identifier, ShaderType, String> defaultShaderSource, boolean enableDebugLabels, CallbackInfoReturnable<GpuDevice> info) {
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
    private static SamplerCache samplerCache;
    
    @Overwrite(remap = false)
    public static void initRenderer(long l, int i, boolean bl, ShaderSource shaderSource, boolean bl2) {
        if (VulkanStartup.isSupported()) {
            DEVICE = new Hg3DGpuDevice(l, i, bl, shaderSource, bl2);
        } else {
            DEVICE = new GlDevice(l, i, bl, shaderSource, bl2);
        }
        apiDescription = DEVICE.getImplementationInformation();
        dynamicUniforms = new DynamicUniforms();
        samplerCache.initialize();
        
    }
    #endif
}