package graphics.cinnabar.core.mixin.mixins;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import graphics.cinnabar.core.hg3d.Hg3DWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.VirtualScreen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(VirtualScreen.class)
public class VirtualScreenMixin {
    @Shadow
    protected Minecraft minecraft;
    @Shadow
    protected ScreenManager screenManager;
    
    @Overwrite
    public Window newWindow(DisplayData screenSize, @Nullable String videoModeName, String title) {
        return new Hg3DWindow(this.minecraft, this.screenManager, screenSize, videoModeName, title);
    }
    
}
