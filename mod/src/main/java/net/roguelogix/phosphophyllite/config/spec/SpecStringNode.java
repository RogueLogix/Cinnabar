package net.roguelogix.phosphophyllite.config.spec;

import net.roguelogix.phosphophyllite.parsers.Element;
import net.roguelogix.phosphophyllite.util.NonnullDefault;

import java.lang.reflect.Field;

@NonnullDefault
public class SpecStringNode extends SpecValueNode {
    public final String defaultString;
    
    SpecStringNode(SpecObjectNode parent, Field field, ConfigOptionsDefaults defaults) {
        super(parent, field, defaults);
        this.defaultString = currentValueAsString();
    }
    
    @Override
    public String defaultValueAsString() {
        return defaultString;
    }
    
    @Override
    public String currentValueAsString() {
        return (String) currentValueObject();
    }
    
    @Override
    public void writeFromString(String string) {
        writeObject(string);
    }
    
    @Override
    public boolean isValueValid(String valueString) {
        return true;
    }
    
    @Override
    public void writeDefault() {
        writeFromString(defaultString);
    }
    
    @Override
    public Element generateDefaultElement() {
        return new Element(Element.Type.String, generateComment(), name, defaultString);
    }
    
    @Override
    public Element generateCurrentElement() {
        return new Element(Element.Type.String, generateComment(), name, currentValueAsString());
    }
    
    @Override
    public Element generateSyncElement() {
        return new Element(Element.Type.String, null, name, currentValueAsString());
    }
    
    
    @Override
    public String generateComment() {
        return baseComment;
    }
    
    @Override
    public Element correctToValidState(Element element) {
        if (element.type == Element.Type.String && element.value instanceof String) {
            return new Element(Element.Type.String, baseComment, name, element.value);
        }
        return generateDefaultElement();
    }
    
    @Override
    public void writeElement(Element element) {
        writeFromString(element.asString());
    }
}
