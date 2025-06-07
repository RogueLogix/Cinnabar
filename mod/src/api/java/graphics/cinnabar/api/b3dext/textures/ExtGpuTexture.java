package graphics.cinnabar.api.b3dext.textures;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;

public abstract class ExtGpuTexture extends GpuTexture {
    public ExtGpuTexture(int usage, String label, TextureFormat format, int width, int height, int depthOrLayers, int mips) {
        super(usage, label, format, width, height, depthOrLayers, mips);
    }
}
