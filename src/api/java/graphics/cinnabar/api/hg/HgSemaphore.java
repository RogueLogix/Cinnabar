package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.threading.ISemaphore;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.NonExtendable
public interface HgSemaphore extends HgObject, ISemaphore {
    record Op(HgSemaphore semaphore, long value) {
    }
}
