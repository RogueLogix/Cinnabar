package net.roguelogix.phosphophyllite.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE, ElementType.METHOD, ElementType.PACKAGE})
public @interface IgnoreRegistration {
    boolean ignoreInDev() default true;
}
