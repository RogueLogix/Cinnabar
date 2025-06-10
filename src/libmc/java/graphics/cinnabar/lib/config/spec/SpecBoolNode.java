package graphics.cinnabar.lib.config.spec;

import graphics.cinnabar.lib.parsers.Element;

import java.lang.reflect.Field;

public class SpecBoolNode extends SpecValueNode {
    
    public final boolean defaultValue;
    
    SpecBoolNode(SpecObjectNode parent, Field field, ConfigOptionsDefaults defaults) {
        super(parent, field, defaults);
        defaultValue = (boolean) currentValueObject();
    }
    
    @Override
    public String defaultValueAsString() {
        return String.valueOf(defaultValue);
    }
    
    @Override
    public String currentValueAsString() {
        return String.valueOf(currentValueObject());
    }
    
    @Override
    public void writeFromString(String string) {
        writeObject(Boolean.parseBoolean(string));
    }
    
    @Override
    public boolean isValueValid(String valueString) {
        return valueString.equalsIgnoreCase("true") || valueString.equalsIgnoreCase("false");
    }
    
    @Override
    public void writeDefault() {
        writeObject(defaultValue);
    }
    
    @Override
    public Element generateDefaultElement() {
        return new Element(Element.Type.Boolean, generateComment(), name, defaultValue);
    }
    
    @Override
    public Element generateCurrentElement() {
        return new Element(Element.Type.Boolean, generateComment(), name, currentValueObject());
    }
    
    @Override
    public Element generateSyncElement() {
        return new Element(Element.Type.Boolean, null, name, currentValueObject());
    }
    
    @Override
    public String generateComment() {
        final var comment = new StringBuilder(baseComment);
    
        if (comment.length() != 0) {
            comment.append('\n');
        }
        comment.append("Default: ");
        comment.append(defaultValue);
        
        return comment.toString();
    }
    
    @Override
    public Element correctToValidState(Element element) {
        if (element.type != Element.Type.Boolean || !(element.value instanceof Boolean)) {
            return generateDefaultElement();
        }
        return new Element(Element.Type.Boolean, generateComment(), name, element.asBool());
    }
    
    @Override
    public void writeElement(Element element) {
        writeObject(element.asBool());
    }
}
