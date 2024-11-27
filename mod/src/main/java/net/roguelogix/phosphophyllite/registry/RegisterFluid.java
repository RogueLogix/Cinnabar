package net.roguelogix.phosphophyllite.registry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface RegisterFluid {
    
    String modid() default "";
    
    String name();
    
    boolean registerBucket() default false;
    
    boolean creativeTab() default true;
    
    int color() default 0xFFFFFFFF;
    
    @Retention(RetentionPolicy.RUNTIME)
    @interface Instance {
    }
}
