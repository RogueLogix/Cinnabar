package graphics.cinnabar.lib.config.spec;

import graphics.cinnabar.lib.config.ConfigType;
import graphics.cinnabar.lib.config.ConfigValue;
import org.jetbrains.annotations.Nullable;

public record ConfigOptionsDefaults(ConfigType type, boolean advanced, boolean hidden, boolean reloadable) {
    public ConfigOptionsDefaults() {
        this(ConfigType.NULL, false, false, false);
    }
    
    public ConfigOptionsDefaults transform(@Nullable ConfigValue annotation) {
        if (annotation == null) {
            return this;
        }
        return new ConfigOptionsDefaults(annotation.configType().from(type), annotation.advanced().from(advanced), annotation.hidden().from(hidden), annotation.reloadable().from(reloadable));
    }
}
