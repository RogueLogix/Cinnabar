package graphics.cinnabar.core.hg3d;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import graphics.cinnabar.api.hg.HgImage;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT;

public class Hg3DGpuTexture extends GpuTexture implements Hg3DObject {
    private final Hg3DGpuDevice device;
    private final HgImage image;
    private boolean closed = false;
    private int liveViews = 0;
    
    public Hg3DGpuTexture(Hg3DGpuDevice device, int usage, String label, TextureFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        final var flags = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0;
        image = device.hgDevice().createImage(HgImage.Type.TYPE_2D, Hg3DConst.format(format), width, height, 1, depthOrLayers, mipLevels, Hg3DConst.textureUsageBits(usage, format.hasColorAspect()), flags, false);
        image.setName(label);
    }
    
    @Override
    public boolean isClosed() {
        return closed;
    }
    
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (liveViews == 0) {
            device.destroyEndOfFrameAsync(image);
        }
    }
    
    public HgImage image() {
        return image;
    }
    
    @Override
    public Hg3DGpuDevice device() {
        return device;
    }
    
    public void addView() {
        liveViews++;
    }
    
    public void removeView() {
        liveViews--;
        assert liveViews >= 0;
        if (closed && liveViews == 0) {
            device.destroyEndOfFrameAsync(image);
        }
    }
}
