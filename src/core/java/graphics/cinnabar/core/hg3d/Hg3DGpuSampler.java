package graphics.cinnabar.core.hg3d;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import graphics.cinnabar.api.hg.HgSampler;

import java.util.OptionalDouble;

import static graphics.cinnabar.core.hg3d.Hg3DConst.addressMode;
import static org.lwjgl.vulkan.VK10.VK_LOD_CLAMP_NONE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;

public class Hg3DGpuSampler extends GpuSampler implements Hg3DObject {
    
    private final Hg3DGpuDevice device;
    private final HgSampler sampler;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private boolean closed = false;
    
    public Hg3DGpuSampler(Hg3DGpuDevice device, AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        this.device = device;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
        final var hgDevice = device.hgDevice();
        sampler = hgDevice.createSampler(new HgSampler.CreateInfo(minFilter == FilterMode.LINEAR, magFilter == FilterMode.LINEAR, addressMode(addressModeU), addressMode(addressModeV), VK_SAMPLER_ADDRESS_MODE_REPEAT, maxAnisotropy, maxLod.orElse(VK_LOD_CLAMP_NONE) >= VK_LOD_CLAMP_NONE));
    }
    
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        device.destroyEndOfFrameAsync(sampler);
    }
    
    @Override
    public Hg3DGpuDevice device() {
        return device;
    }
    
    public HgSampler sampler() {
        return sampler;
    }
    
    @Override
    public AddressMode getAddressModeU() {
        return addressModeU;
    }
    
    @Override
    public AddressMode getAddressModeV() {
        return addressModeV;
    }
    
    @Override
    public FilterMode getMinFilter() {
        return minFilter;
    }
    
    @Override
    public FilterMode getMagFilter() {
        return magFilter;
    }
    
    @Override
    public int getMaxAnisotropy() {
        return maxAnisotropy;
    }
    
    @Override
    public OptionalDouble getMaxLod() {
        return maxLod;
    }
}
