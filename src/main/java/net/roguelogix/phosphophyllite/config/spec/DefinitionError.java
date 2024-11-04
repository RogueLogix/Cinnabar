package net.roguelogix.phosphophyllite.config.spec;

import net.roguelogix.phosphophyllite.util.NonnullDefault;

@NonnullDefault
public class DefinitionError extends RuntimeException {
    public DefinitionError(String message) {
        super(message);
    }
}
