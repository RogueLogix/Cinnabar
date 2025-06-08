package graphics.cinnabar.api.b3dext.textures;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;

public abstract class ExtGpuTextureView extends GpuTextureView {
    
    private final ExtGpuTexture.Type type;
    private final TextureFormat format;
    private final int baseArrayLayer;
    private final int layerCount;
    
    public ExtGpuTextureView(ExtGpuTexture texture, ExtGpuTexture.Type type, TextureFormat format, int baseMipLevel, int mipLevels, int baseArrayLayer, int layerCount) {
        super(texture, baseMipLevel, mipLevels);
        this.type = type;
        this.format = format;
        this.baseArrayLayer = baseArrayLayer;
        this.layerCount = layerCount;
    }
    
    public ExtGpuTexture.Type type() {
        return type;
    }
    
    public TextureFormat format() {
        return format;
    }
    
    public int baseArrayLayer() {
        return baseArrayLayer;
    }
    
    public int layerCount() {
        return layerCount;
    }
}
