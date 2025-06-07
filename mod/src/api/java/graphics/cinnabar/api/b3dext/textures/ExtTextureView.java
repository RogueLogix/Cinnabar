package graphics.cinnabar.api.b3dext.textures;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

public abstract class ExtTextureView extends GpuTextureView {
    public ExtTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
    }
}
