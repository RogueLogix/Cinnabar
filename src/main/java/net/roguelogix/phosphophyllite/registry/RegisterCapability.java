package net.roguelogix.phosphophyllite.registry;

import javax.annotation.meta.TypeQualifierDefault;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@TypeQualifierDefault({ElementType.FIELD})
public @interface RegisterCapability {
}
