package graphics.cinnabar.api.cvk.textures;

import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.api.b3dext.textures.ExtGpuTexture;

public abstract class CVKGpuTexture extends ExtGpuTexture {
    public CVKGpuTexture(int usage, String label, Type type, TextureFormat format, int width, int height, int depth, int layers, int mips) {
        super(usage, label, type, format, width, height, depth, layers, mips);
    }
}
