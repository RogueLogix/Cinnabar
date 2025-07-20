package graphics.cinnabar.lib.config.spec;

import graphics.cinnabar.lib.config.ConfigValue;
import graphics.cinnabar.lib.parsers.Element;
import org.jetbrains.annotations.Nullable;

public abstract class SpecNode {
    public final String baseComment;
    public final boolean advanced;
    public final boolean hidden;
    public final boolean reloadable;
    
    @Nullable
    public final String name;
    
    protected SpecNode(@Nullable String name, @Nullable String baseComment, boolean advanced, boolean hidden, boolean reloadable) {
        this.name = name;
        this.baseComment = baseComment == null ? "" : baseComment;
        this.advanced = advanced;
        this.hidden = hidden;
        this.reloadable = reloadable;
    }
    
    protected SpecNode(@Nullable String name, ConfigValue annotation, ConfigOptionsDefaults defaults) {
        this.name = name;
        this.baseComment = annotation.comment();
        this.advanced = annotation.advanced().from(defaults.advanced());
        this.hidden = annotation.hidden().from(defaults.hidden());
        this.reloadable = annotation.reloadable().from(defaults.reloadable());
    }
    
    public abstract void writeDefault();
    
    @Nullable
    public abstract Element generateDefaultElement();
    
    @Nullable
    public abstract Element generateCurrentElement();
    
    
    /**
     * Omits no elements, doesnt generate comments
     *
     * @return Element tree for syncing to client
     */
    public abstract Element generateSyncElement();
    
    @Nullable
    public abstract Element correctToValidState(Element element);
    
    public abstract void writeElement(Element element);
}
