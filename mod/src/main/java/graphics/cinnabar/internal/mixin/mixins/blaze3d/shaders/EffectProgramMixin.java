package graphics.cinnabar.internal.mixin.mixins.blaze3d.shaders;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.EffectProgram;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.io.IOException;
import java.io.InputStream;

@Mixin(EffectProgram.class)
public class EffectProgramMixin {
//    @Overwrite
//    public static EffectProgram compileShader(Program.Type type, String name, InputStream shaderData, String sourceName) throws IOException {
//        RenderSystem.assertOnRenderThread();
//        int i = compileShaderInternal(type, name, shaderData, sourceName, EffectProgram.PREPROCESSOR);
//        EffectProgram effectprogram = new EffectProgram(type, i, name);
//        type.getPrograms().put(name, effectprogram);
//        return effectprogram;
//    }
}
