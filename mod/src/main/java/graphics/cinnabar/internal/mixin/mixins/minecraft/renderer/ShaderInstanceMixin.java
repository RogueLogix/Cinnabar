package graphics.cinnabar.internal.mixin.mixins.minecraft.renderer;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.VertexFormat;
import graphics.cinnabar.internal.extensions.blaze3d.shaders.CinnabarProgram;
import graphics.cinnabar.internal.extensions.blaze3d.shaders.CinnabarUniform;
import graphics.cinnabar.internal.extensions.minecraft.renderer.CinnabarShaderInstance;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import graphics.cinnabar.api.annotations.NotNullDefault;
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

@NotNullDefault
@Mixin(ShaderInstance.class)
public class ShaderInstanceMixin {
    
    @Nullable
    private static Object lastShaderInstance;
    @Nullable
    private JsonObject jsonObject;
    
    @Inject(
            method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V",
            at = @At(value = "INVOKE", target = "net/minecraft/util/GsonHelper.getAsString(Lcom/google/gson/JsonObject;Ljava/lang/String;)Ljava/lang/String;"),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void getJsonObject(ResourceProvider p_173336_, ResourceLocation shaderLocation, VertexFormat p_173338_, CallbackInfo ignored, ResourceLocation resourcelocation, Reader reader, JsonObject jsonObject) {
        //noinspection ConstantValue
        if (!(((Object) this) instanceof CinnabarShaderInstance)) {
            throw new IllegalStateException();
        }
        this.jsonObject = jsonObject;
        lastShaderInstance = this;
    }
    
    @Redirect(
            method = "getOrCreate(Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/Program;",
            at = @At(
                    value = "INVOKE",
                    target = "com/mojang/blaze3d/shaders/Program.compileShader(Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;Ljava/io/InputStream;Ljava/lang/String;Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;)Lcom/mojang/blaze3d/shaders/Program;"
            )
    )
    private static Program compileShader(Program.Type type, String name, InputStream shaderData, String sourceName, GlslPreprocessor preprocessor) throws IOException {
        assert lastShaderInstance != null;
        return CinnabarProgram.compileShader(Objects.requireNonNull(((ShaderInstanceMixin) lastShaderInstance).jsonObject), ((ShaderInstance) lastShaderInstance).getVertexFormat(), type, name, shaderData, sourceName, preprocessor);
    }
    
    @Redirect(method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/shaders/ProgramManager;createProgram()I"))
    private int createProgramNoop() {
        return -1;
    }
    
    @Redirect(method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/shaders/ProgramManager;linkShader(Lcom/mojang/blaze3d/shaders/Shader;)V"))
    private void linkShader(Shader shader) {
    
    }
    
    @Redirect(method = "parseUniformNode(Lcom/google/gson/JsonElement;)V", at = @At(value = "NEW", target = "(Ljava/lang/String;IILcom/mojang/blaze3d/shaders/Shader;)Lcom/mojang/blaze3d/shaders/Uniform;"))
    private static Uniform createUniform(String name, int type, int count, Shader parent) {
        return new CinnabarUniform(name, type, count, parent);
    }
}
