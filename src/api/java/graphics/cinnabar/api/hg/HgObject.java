package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.util.Destroyable;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.NonExtendable
public interface HgObject extends Destroyable {
    HgDevice device();
}
