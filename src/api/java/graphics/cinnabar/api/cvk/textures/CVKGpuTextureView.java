package graphics.cinnabar.api.cvk.textures;

import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.api.b3dext.textures.ExtGpuTexture;
import graphics.cinnabar.api.b3dext.textures.ExtGpuTextureView;

public abstract class CVKGpuTextureView extends ExtGpuTextureView {
    public CVKGpuTextureView(ExtGpuTexture texture, ExtGpuTexture.Type type, TextureFormat format, int baseMipLevel, int mipLevels, int baseArrayLayer, int layerCount) {
        super(texture, type, format, baseMipLevel, mipLevels, baseArrayLayer, layerCount);
    }
}
