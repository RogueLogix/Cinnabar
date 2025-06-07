package graphics.cinnabar.api.cvk.textures;

import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.api.b3dext.textures.ExtGpuTexture;

public abstract class CVKGpuTexture extends ExtGpuTexture {
    public CVKGpuTexture(int usage, String label, TextureFormat format, int width, int height, int depthOrLayers, int mips) {
        super(usage, label, format, width, height, depthOrLayers, mips);
    }
}
