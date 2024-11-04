package net.roguelogix.phosphophyllite.config;

import it.unimi.dsi.fastutil.booleans.Boolean2BooleanFunction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigValue {
    
    enum BoolOption {
        Default(Boolean2BooleanFunction.identity()),
        True(a -> true),
        False(a -> false),
        ;
        
        private final Boolean2BooleanFunction function;
    
        BoolOption(Boolean2BooleanFunction function) {
            this.function = function;
        }
    
        public boolean from(boolean defaultValue) {
            return function.apply(defaultValue);
        }
    }
    
    ConfigType configType() default ConfigType.NULL;
    
    String comment() default "";
    
    // only used for numbers
    String range() default "(,)";
    
    // only used for enums
    String[] allowedValues() default {};
    
    String[] restrictions() default {};
    
    BoolOption advanced() default BoolOption.Default;
    
    BoolOption hidden() default BoolOption.Default;
    
    BoolOption reloadable() default BoolOption.Default;
}

