package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import graphics.cinnabar.core.CinnabarCore;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RenderSystem.class)
public class RenderSystemMixin {
    
    @Shadow
    @Nullable
    private static GpuDevice DEVICE;
    
    // this is set at the beginning of device init, and allows me to get the device within its own init phase
    // this is used for debug things
    @Overwrite
    public static GpuDevice getDevice() {
        if (DEVICE != null) {
            return DEVICE;
        }
        if (CinnabarCore.cinnabarDeviceSingleton != null){
            return CinnabarCore.cinnabarDeviceSingleton;
        }
        throw new IllegalStateException("Can't getDevice() before it was initialized");
    }
}
