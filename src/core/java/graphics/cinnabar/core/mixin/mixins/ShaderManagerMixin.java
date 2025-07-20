package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Set;

@Mixin(ShaderManager.class)
public class ShaderManagerMixin {
    
    @SuppressWarnings("SpellCheckingInspection")
    @Inject(method = "apply", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;clearPipelineCache()V"), locals = LocalCapture.CAPTURE_FAILHARD)
    protected void cinnabar$kickPipelinesFirst(ShaderManager.Configs configs, ResourceManager resourceManager, ProfilerFiller profilerFiller, CallbackInfo callbackInfo, ShaderManager.CompilationCache sourceCache, Set<RenderPipeline> pipelineSet) {
        for (RenderPipeline renderPipeline : pipelineSet) {
            RenderSystem.getDevice().precompilePipeline(renderPipeline, sourceCache::getShaderSource);
        }
    }
}
