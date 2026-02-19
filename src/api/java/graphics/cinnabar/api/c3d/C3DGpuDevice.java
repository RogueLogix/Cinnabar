package graphics.cinnabar.api.c3d;

import com.mojang.blaze3d.systems.GpuDeviceBackend;

public interface C3DGpuDevice extends GpuDeviceBackend {
    C3DCommandEncoder createCommandEncoder();
}
