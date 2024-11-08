package graphics.cinnabar.internal.extensions.minecraft.renderer;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import graphics.cinnabar.internal.extensions.blaze3d.platform.CinnabarWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.VirtualScreen;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;

@NonnullDefault
public class CinnabarVirtualScreen extends VirtualScreen {
    public CinnabarVirtualScreen(Minecraft minecraft) {
        super(minecraft);
    }
    
    @Override
    public Window newWindow(DisplayData screenSize, @Nullable String videoModeName, String title) {
        return new CinnabarWindow(this.minecraft, this.screenManager, screenSize, videoModeName, title);
    }
}