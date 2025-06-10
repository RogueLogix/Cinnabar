package graphics.cinnabar.api.b3dext.textures;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;

public abstract class ExtGpuTexture extends GpuTexture {
    
    public enum Type {
        TYPE_1D,
        TYPE_2D,
        TYPE_3D,
        TYPE_CUBE,
        TYPE_1D_ARRAY,
        TYPE_2D_ARRAY,
        TYPE_CUBE_ARRAY,
    }
    
    private final Type type;
    private final int depth;
    private final int layers;
    
    public ExtGpuTexture(int usage, String label, Type type, TextureFormat format, int width, int height, int depth, int layers, int mips) {
        // depth and/or layers must be 1 
        super(usage, label, format, width, height, depth != 1 ? depth : layers, mips);
        this.type = type;
        this.depth = depth;
        this.layers = layers;
    }
    
    public Type type() {
        return type;
    }
    
    public int depth(int mipLevel) {
        return depth >> mipLevel;
    }
    
    public int layers() {
        return layers;
    }
}
