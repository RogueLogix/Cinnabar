package graphics.cinnabar.internal.mixin.mixins.blaze3d.platform;

import com.mojang.blaze3d.platform.GlUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(GlUtil.class)
public class GlUtilMixin {
    
    @Overwrite
    public static String getVendor() {
        return "UNKNOWN VENDOR";
    }
    
    @Overwrite
    public static String getRenderer() {
        return "Cinnabar VK";
    }
    
    @Overwrite
    public static String getOpenGLVersion() {
        return "VK 1.3";
    }
}
