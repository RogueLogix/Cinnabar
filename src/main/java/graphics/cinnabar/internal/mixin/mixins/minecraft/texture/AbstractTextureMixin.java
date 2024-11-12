package graphics.cinnabar.internal.mixin.mixins.minecraft.texture;

import graphics.cinnabar.internal.exceptions.NotImplemented;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(AbstractTexture.class)
public class AbstractTextureMixin {
    @Overwrite
    public int getId() {
        throw new NotImplemented();
    }
    
    @Overwrite
    public void releaseId() {
        throw new NotImplemented();
    }
}
