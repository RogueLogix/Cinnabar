package net.roguelogix.phosphophyllite.config.spec;

import net.roguelogix.phosphophyllite.config.ConfigValue;
import net.roguelogix.phosphophyllite.util.NonnullDefault;

import java.lang.reflect.Field;

@NonnullDefault
public abstract class SpecNumberNode extends SpecValueNode {
    public final boolean lowerInclusive;
    
    public final boolean upperInclusive;
    
    protected SpecNumberNode(SpecObjectNode parent, Field field, ConfigOptionsDefaults defaults) {
        super(parent, field, defaults);
        final var annotation = field.getAnnotation(ConfigValue.class);
        var range = annotation.range();
        
        range = range.trim();
        if (range.length() < 3) {
            throw new DefinitionError("Incomplete range given");
        }
        
        final char lowerInclusiveChar = range.charAt(0);
        final char upperInclusiveChar = range.charAt(range.length() - 1);
        lowerInclusive = switch (lowerInclusiveChar) {
            case '(' -> false;
            case '[' -> true;
            default -> throw new DefinitionError("Unknown lower bound inclusivity");
        };
        upperInclusive = switch (upperInclusiveChar) {
            case ')' -> false;
            case ']' -> true;
            default -> throw new DefinitionError("Unknown higher bound inclusivity");
        };
    }
    
    
    public abstract String lowerBoundAsString();
    
    public abstract String upperBoundAsString();
    
}
