package graphics.cinnabar.internal.extensions.blaze3d.shaders;

import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Uniform;
import graphics.cinnabar.internal.extensions.minecraft.renderer.CinnabarShaderInstance;
import org.lwjgl.system.MemoryUtil;

import java.nio.Buffer;

public class CinnabarUniform extends Uniform {
    public CinnabarUniform(String name, int type, int count, Shader parent) {
        super(name, type, count, parent);
    }
    
    CinnabarShaderInstance cinnabarParent() {
        return (CinnabarShaderInstance) parent;
    }
    
    @Override
    public void upload() {
        final var buffer = getType() <= UT_INT4 ? getIntBuffer() : getFloatBuffer();
        cinnabarParent().writeUniform(getLocation(), MemoryUtil.memAddress(buffer), getCount() * 4L);
    }
}
