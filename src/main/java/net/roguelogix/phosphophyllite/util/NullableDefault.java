package net.roguelogix.phosphophyllite.util;

import org.jetbrains.annotations.Nullable;

import javax.annotation.meta.TypeQualifierDefault;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Nullable
@Retention(RetentionPolicy.RUNTIME)
@TypeQualifierDefault({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.RECORD_COMPONENT})
public @interface NullableDefault {
    // so i don't rely on any minecraft/mojang code, but can still use this
}
