package graphics.cinnabar.core.mixin.mixins;

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

@Mixin(ClientHooks.class)
public class ClientHooksMixin {
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
}
