package net.roguelogix.phosphophyllite.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RegisterItem {
    
    String modid() default "";
    
    String name();
    
    boolean creativeTab() default true;
}
