package graphics.cinnabar.lib.config.spec;

import net.minecraft.resources.ResourceLocation;
import graphics.cinnabar.lib.parsers.Element;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;

public class SpecResourceLocationNode extends SpecValueNode {
    public final ResourceLocation defaultValue;
    
    SpecResourceLocationNode(SpecObjectNode parent, Field field, ConfigOptionsDefaults defaults) {
        super(parent, field, defaults);
        this.defaultValue = (ResourceLocation) currentValueObject();
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
        writeObject(string.equalsIgnoreCase("null") ? null : ResourceLocation.parse(string));
    }
    
    @Override
    public boolean isValueValid(String valueString) {
        return ResourceLocation.tryParse(valueString) != null;
    }
    
    @Override
    public String generateComment() {
        return baseComment;
    }
    
    @Override
    public void writeDefault() {
        writeObject(defaultValue);
    }
    
    @Nullable
    @Override
    public Element generateDefaultElement() {
        return new Element(Element.Type.String, generateComment(), name, defaultValueAsString());
    }
    
    @Nullable
    @Override
    public Element generateCurrentElement() {
        return new Element(Element.Type.String, generateComment(), name, currentValueAsString());
    }
    
    @Override
    public Element generateSyncElement() {
        return new Element(Element.Type.String, null, name, currentValueAsString());
    }
    
    @Nullable
    @Override
    public Element correctToValidState(Element element) {
        if (element.type != Element.Type.String || !(element.value instanceof String)) {
            return generateDefaultElement();
        }
        if (!isValueValid((String) element.value)) {
            return generateCurrentElement();
        }
        return new Element(Element.Type.String, baseComment, name, element.value);
    }
    
    @Override
    public void writeElement(Element element) {
        writeFromString(element.asString());
    }
}
