package graphics.cinnabar.internal.mixin.mixins.minecraft.renderer;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.EffectProgram;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.internal.extensions.blaze3d.shaders.CinnabarProgram;
import graphics.cinnabar.internal.extensions.blaze3d.shaders.CinnabarUniform;
import graphics.cinnabar.internal.extensions.minecraft.renderer.CinnabarEffectInstance;
import graphics.cinnabar.internal.extensions.minecraft.renderer.CinnabarShaderInstance;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Objects;

@Mixin(EffectInstance.class)
public class EffectInstanceMixin {
    
    @Nullable
    private static Object lastShaderInstance;
    @Nullable
    private JsonObject jsonObject;
    
    @Inject(
            method = "<init>",
            at = @At(value = "INVOKE", target = "net/minecraft/util/GsonHelper.getAsString(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/lang/String;"),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void EffectInstance$getJsonObject(ResourceProvider resourceProvider, String name, CallbackInfo ignored, ResourceLocation resourcelocation, ResourceLocation resourcelocation1, Resource resource, Reader reader, JsonObject jsonObject) {
        //noinspection ConstantValue
        if (!(((Object) this) instanceof CinnabarEffectInstance)) {
            throw new IllegalStateException();
        }
        this.jsonObject = jsonObject;
        lastShaderInstance = this;
    }
    
    
    @Redirect(
            method = "getOrCreate",
            at = @At(
                    value = "INVOKE",
                    target = "com/mojang/blaze3d/shaders/EffectProgram.compileShader(Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;Ljava/io/InputStream;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/EffectProgram;"
            )
    )
    private static EffectProgram compileShader(Program.Type type, String name, InputStream shaderData, String sourceName) throws IOException {
        assert lastShaderInstance != null;
        return CinnabarProgram.compileShader(Objects.requireNonNull(((EffectInstanceMixin) lastShaderInstance).jsonObject), DefaultVertexFormat.POSITION, type, name, shaderData, sourceName, EffectProgram.PREPROCESSOR);
    }
    
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/shaders/ProgramManager;createProgram()I"))
    private int createProgramNoop() {
        return -1;
    }
    
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/shaders/ProgramManager;linkShader(Lcom/mojang/blaze3d/shaders/Shader;)V"))
    private void linkShader(Shader shader) {
    
    }
    
    @Redirect(method = "parseUniformNode(Lcom/google/gson/JsonElement;)V", at = @At(value = "NEW", target = "(Ljava/lang/String;IILcom/mojang/blaze3d/shaders/Shader;)Lcom/mojang/blaze3d/shaders/Uniform;"))
    private static Uniform createUniform(String name, int type, int count, Shader parent) {
        return new CinnabarUniform(name, type, count, parent);
    }
}
