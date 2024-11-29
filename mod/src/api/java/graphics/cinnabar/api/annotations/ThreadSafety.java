package graphics.cinnabar.api.annotations;

// informational annotations
@NotNullDefault
public @interface ThreadSafety {
    
    // functions annotated with this can only be called on the main graphics thread
    // this is primarily targeted at functions that need access to the statemachine
    @interface MainGraphics {
        String lockGroups() default "0";
        
        String note() default "";
    }
    
    // functions annotated with this can be called by any thread, but only by one at a time.
    // simultaneous access to multiple unique objects is allowed
    // lock groups can include multiple different functions, that all may only have a single call at once
    // group 0 (the default) includes any functions annotated with @MainGraphics
    @interface Any {
        String lockGroups() default "0";
        
        String note() default "";
    }
    
    // functions annotated with this can not only be called by any thread, but multiple at the same time
    // see usage for argument requirements when doing so, simultaneous calls with identical arguments may not be allowed
    // lock group specifies that while this function may be called multiple times at the same time, it may *not* be called at the same time as any other non-many function in the specified group
    // default of -1 specifies no such requirement
    @interface Many {
        String lockGroups() default "-1";
        
        String note() default "";
    }
    
    // its more complicated than a single interface could describe
    // may rely on creation parameters for object
    @interface ItDepends {
        String note() default "";
    }
}
