package graphics.cinnabar.api.exceptions;

import graphics.cinnabar.api.annotations.Internal;

@Internal
public class RedirectImplemented extends RuntimeException {
    public RedirectImplemented() {
        super("This should be impossible to hit");
    }
}
