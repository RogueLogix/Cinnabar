package graphics.cinnabar.api.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@Internal(note = "The redirector only looks within the cinnabar mod package, this will be ignored elsewhere")
public @interface CinnabarRedirectIMPL {
    String value();
    
    @interface Dst {
        // source of the call
        String value();
    }
}
