package graphics.cinnabar.core.hg3d;

import com.mojang.blaze3d.systems.GpuQuery;

import java.util.OptionalLong;

public class Hg3DGpuQuery implements Hg3DObject, GpuQuery {
    
    private final Hg3DGpuDevice device;
    
    public Hg3DGpuQuery(Hg3DGpuDevice device) {
        this.device = device;
    }
    
    @Override
    public Hg3DGpuDevice device() {
        return device;
    }
    
    @Override
    public OptionalLong getValue() {
        // queries aren't implemented in Hg, so just return 0
        // TODO: implement queries in Hg
        return OptionalLong.of(0);
    }
    
    @Override
    public void close() {
    
    }
}
