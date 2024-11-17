package graphics.cinnabar.internal.mixin.mixins.blaze3d.shaders;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import graphics.cinnabar.internal.extensions.blaze3d.shaders.CinnabarProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.io.InputStream;

@Mixin(Program.class)
public class ProgramMixin {
    @Redirect(method = "compileShader", at = @At(value = "NEW", target = "(Lcom/mojang/blaze3d/shaders/Program$Type;ILjava/lang/String;)Lcom/mojang/blaze3d/shaders/Program;"))
    private Program cinnabar$newProgram(Program.Type type, int id, String name) {
        return new CinnabarProgram(type, id, name);
    }
    
//    @Overwrite
//    private static int compileShaderInternal(Program.Type type, String name, InputStream shaderData, String sourceName, GlslPreprocessor preprocessor) throws IOException {
//        return CinnabarProgram.compileShaderInternal(type, name, shaderData, sourceName, preprocessor);
//    }
}
