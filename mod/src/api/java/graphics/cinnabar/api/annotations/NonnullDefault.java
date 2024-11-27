package graphics.cinnabar.api.annotations;

import org.jetbrains.annotations.NotNull;

import javax.annotation.meta.TypeQualifierDefault;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@NotNull
@SuppressWarnings("NullableProblems")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PACKAGE, ElementType.TYPE})
@TypeQualifierDefault({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.RECORD_COMPONENT, ElementType.PACKAGE})
public @interface NonnullDefault {
    // so i don't rely on any minecraft/mojang code, but can still use this
}
