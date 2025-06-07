package graphics.cinnabar.api.cvk.textures;

import com.mojang.blaze3d.textures.GpuTexture;
import graphics.cinnabar.api.b3dext.textures.ExtTextureView;

public abstract class CVKTextureView extends ExtTextureView {
    public CVKTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
    }
}
