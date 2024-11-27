package graphics.cinnabar.internal.mixin.helpers.minecraft.renderer;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.shaders.Program;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;

public class ShaderInstanceMixinHelper {
    public static Program getOrCreateProgram(final JsonObject jsonObject, final ResourceProvider resourceProvider, Program.Type programType, String name) throws IOException {
        
        return null;
    }
}
