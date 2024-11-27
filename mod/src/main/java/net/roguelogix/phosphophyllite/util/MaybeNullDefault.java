package net.roguelogix.phosphophyllite.util;

import javax.annotation.meta.TypeQualifierDefault;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

// this explicitly shuts my check for @NonnullDefault or @NullableDefault
// leaves the nullability alone
@Retention(RetentionPolicy.RUNTIME)
@TypeQualifierDefault({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.RECORD_COMPONENT})
public @interface MaybeNullDefault {
    // so i don't rely on any minecraft/mojang code, but can still use this
}
