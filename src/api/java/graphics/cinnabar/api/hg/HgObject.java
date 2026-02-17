package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.util.Destroyable;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

@ApiStatus.NonExtendable
public interface HgObject<T extends HgObject<T>> extends Destroyable {
    HgDevice device();
    
    T setName(String label);
    
    default T setName(@Nullable Supplier<String> label) {
        if (label != null) {
            return setName(label.get());
        }
        //noinspection unchecked
        return (T) this;
    }
}
