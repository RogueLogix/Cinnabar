package net.roguelogix.phosphophyllite.config.spec;

import dev.logix.cinnabar.Cinnabar;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.roguelogix.phosphophyllite.config.ConfigType;
import net.roguelogix.phosphophyllite.config.ConfigValue;
import net.roguelogix.phosphophyllite.parsers.Element;
import net.roguelogix.phosphophyllite.util.NonnullDefault;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

@NonnullDefault
public class SpecObjectNode extends SpecNode {
    
    @Nullable
    public SpecObjectNode parent;
    
    private final Object object;
    @Nullable
    private final Field field;
    private Object activeObject;
    public final Map<String, SpecNode> subNodes;
    public final List<SpecNode> subNodeList;
    
    private static final Class<ResourceLocation> RESOURCE_LOCATION_CLASS;
    static {
        Class<ResourceLocation> foundClass = null;
        try {
            foundClass = ResourceLocation.class;
        } catch (NoClassDefFoundError ignored){
        }
        RESOURCE_LOCATION_CLASS = foundClass;
    }
    
    protected static class EnableAdvancedNode extends SpecNode {
        
        private boolean advancedEnabled;
        
        protected EnableAdvancedNode() {
            super("EnableAdvancedConfig", "EnableAdvancedConfig", false, false, true);
        }
        
        @Override
        public void writeDefault() {
            advancedEnabled = false;
        }
        
        @Nullable
        @Override
        public Element generateDefaultElement() {
            return new Element(Element.Type.Boolean, generateComment(), "EnableAdvancedConfig", false);
        }
        
        @Override
        public Element generateCurrentElement() {
            return new Element(Element.Type.Boolean, generateComment(), "EnableAdvancedConfig", advancedEnabled);
        }
        
        @Override
        public Element generateSyncElement() {
            return new Element(Element.Type.Boolean, null, "EnableAdvancedConfig", advancedEnabled);
        }
        
        public String generateComment() {
            return "Enabled advanced config options\nAdditional options will be shown after next config load";
        }
        
        
        @Override
        public Element correctToValidState(Element element) {
            if (element.type != Element.Type.Boolean || !(element.value instanceof Boolean)) {
                return generateDefaultElement();
            }
            return new Element(Element.Type.Boolean, Objects.requireNonNull(generateDefaultElement()).comment, name, element.asBool());
        }
        
        @Override
        public void writeElement(Element element) {
            advancedEnabled = element.asBool();
        }
    }
    
    private SpecObjectNode(SpecObjectNode parent, Field field, ConfigType type, ConfigOptionsDefaults defaults) {
        // fuckery so i dont need to create two records
        super(field.getName(), field.getAnnotation(ConfigValue.class), defaults = defaults.transform(field.getAnnotation(ConfigValue.class)));
        this.parent = parent;
        this.field = field;
        try {
            this.object = field.get(parent.object);
            if (this.object == null) {
                throw new IllegalArgumentException();
            }
            this.activeObject = object;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        this.subNodeList = Collections.unmodifiableList(readObjectSubNodes(type, defaults));
        subNodes = subNodeList.stream().collect(Collectors.toUnmodifiableMap(node -> Objects.requireNonNull(node.name), node -> node));
    }
    
    public SpecObjectNode(Object rootObject, String comment, ConfigType type, ConfigOptionsDefaults defaults) {
        super(null, comment, defaults.advanced(), defaults.hidden(), defaults.reloadable());
        object = rootObject;
        this.activeObject = object;
        this.field = null;
        final var modifiableSubNodes = readObjectSubNodes(type, defaults);
        modifiableSubNodes.add(0, new EnableAdvancedNode());
        this.subNodeList = Collections.unmodifiableList(modifiableSubNodes);
        //noinspection ConstantConditions
        subNodes = subNodeList.stream().collect(Collectors.toUnmodifiableMap(node -> node.name, node -> node));
    }
    
    public Object object() {
        return activeObject;
    }
    
    public void resetObject() {
        boolean reset = setActiveObject(object);
        if (!reset) {
            throw new IllegalStateException("Failed to reset to previous active object");
        }
    }
    
    public boolean setActiveObject(@Nullable Object newObject) {
        if (newObject == null) {
            resetObject();
            return true;
        }
        if (newObject.getClass() != object.getClass()) {
            return false;
        }
        for (SpecNode subNode : this.subNodeList) {
            if (subNode instanceof SpecObjectNode subObjectNode) {
                if (subObjectNode.field == null) {
                    throw new IllegalStateException();
                }
                try {
                    final var newSubObject = subObjectNode.field.get(newObject);
                    if (!subObjectNode.setActiveObject(newSubObject)) {
                        boolean reset = setActiveObject(activeObject);
                        if (!reset) {
                            throw new IllegalStateException("Failed to reset to previous active object");
                        }
                        return false;
                    }
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
        activeObject = newObject;
        return true;
    }
    
    private List<SpecNode> readObjectSubNodes(ConfigType type, ConfigOptionsDefaults defaults) {
        final var subNodes = new ObjectArrayList<SpecNode>();
        
        final var objectClazz = object.getClass();
        if(!objectClazz.isAnnotationPresent(NonnullDefault.class)){
            Cinnabar.LOGGER.warn("Config object class {} not annotated with NonnullDefault, while not required this is recommended", objectClazz.getName());
        }
        
        for (final var objectField : objectClazz.getDeclaredFields()) {
            if (!objectField.isAnnotationPresent(ConfigValue.class)) {
                continue;
            }
            
            objectField.setAccessible(true);
            
            final var fieldAnnotation = objectField.getAnnotation(ConfigValue.class);
            final var fieldClazz = objectField.getType();
            final SpecNode subNode;
            
            if (fieldClazz == String.class) {
                subNode = new SpecStringNode(this, objectField, defaults);
            } else if (fieldClazz == RESOURCE_LOCATION_CLASS) {
                subNode = new SpecResourceLocationNode(this, objectField, defaults);
            } else if (fieldClazz.isPrimitive() || Boolean.class.isAssignableFrom(fieldClazz) || Number.class.isAssignableFrom(fieldClazz)) {
                if (fieldClazz == boolean.class || fieldClazz == Boolean.class) {
                    subNode = new SpecBoolNode(this, objectField, defaults);
                } else if (ConfigSpecUtil.isIntegral(fieldClazz)) {
                    subNode = new SpecIntegralNode(this, objectField, defaults);
                } else if (ConfigSpecUtil.isFloat(fieldClazz)) {
                    subNode = new SpecFloatNode(this, objectField, defaults);
                } else {
                    throw new IllegalArgumentException("Unknown primitive field type " + fieldClazz.getSimpleName());
                }
            } else if (fieldClazz.isEnum()) {
                subNode = new SpecEnumNode(this, objectField, defaults);
            } else {
                subNode = new SpecObjectNode(this, objectField, type, defaults);
                if (((SpecObjectNode) subNode).subNodeList.isEmpty()) {
                    continue;
                }
            }
            
            // the type for an object node is semi-ignored because its really just setting the default for the values within it, which dont need to respect it directly
            if (!(subNode instanceof SpecObjectNode) && fieldAnnotation.configType().from(defaults.type()) != type) {
                continue;
            }
            
            subNodes.add(subNode);
        }
        
        return subNodes;
    }
    
    @Override
    public void writeDefault() {
        subNodeList.forEach(SpecNode::writeDefault);
    }
    
    
    @Override
    @Nullable
    public Element generateDefaultElement() {
        return generateDefaultElement(false);
    }
    
    @Nullable
    public Element generateDefaultElement(boolean advanced) {
        final var subElements = subNodeList.stream()
                .filter(specNode -> !specNode.hidden)
                .map(specNode -> {
                    if (specNode instanceof SpecObjectNode objectNode) {
                        return objectNode.generateDefaultElement(advanced);
                    }
                    if (!advanced && specNode.advanced) {
                        return null;
                    }
                    return specNode.generateDefaultElement();
                })
                .filter(Objects::nonNull)
                .toArray(Element[]::new);
        if (subElements.length == 0) {
            return null;
        }
        return new Element(Element.Type.Map, baseComment, name, subElements);
    }
    
    @Override
    @Nullable
    public Element generateCurrentElement() {
        return generateCurrentElement(false);
    }
    
    @Nullable
    public Element generateCurrentElement(boolean advanced) {
        final var subElements = subNodeList.stream()
                .filter(specNode -> !specNode.hidden)
                .map(specNode -> {
                    if (specNode instanceof SpecObjectNode objectNode) {
                        return objectNode.generateCurrentElement(advanced);
                    }
                    if (!advanced && specNode.advanced) {
                        return null;
                    }
                    return specNode.generateCurrentElement();
                })
                .filter(Objects::nonNull)
                .toArray(Element[]::new);
        if (subElements.length == 0) {
            return null;
        }
        return new Element(Element.Type.Map, baseComment, name, subElements);
    }
    
    @Override
    public Element generateSyncElement() {
        final var subElements = subNodeList.stream().map(SpecNode::generateSyncElement).toArray(Element[]::new);
        return new Element(Element.Type.Map, null, name, subElements);
    }
    
    @Override
    @Nullable
    public Element correctToValidState(Element element) {
        if (element.type != Element.Type.Map || element.subArray == null) {
            return generateDefaultElement(false);
        }
        final var subElements = Arrays.stream(element.subArray)
                .map(subElement -> {
                    var node = subNodes.get(subElement.name);
                    if (node == null) {
                        return null;
                    }
                    return node.correctToValidState(subElement);
                })
                .filter(Objects::nonNull)
                .toArray(Element[]::new);
        if (subElements.length == 0) {
            return null;
        }
        return new Element(Element.Type.Map, baseComment, name, subElements);
    }
    
    @Override
    public void writeElement(Element element) {
        if (element.subArray == null) {
            return;
        }
        
        for (final var value : element.subArray) {
            final var subNode = subNodes.get(value.name);
            if (subNode == null) {
                continue;
            }
            subNode.writeElement(value);
        }
    }
    
    /**
     * @param element: the element to be regenerated
     * @return an element with any missing subnodes added as subelements, may return original element, will return original subelements
     */
    public Element regenerateMissingElements(Element element) {
        final var specNode = subNodes.get("EnableAdvancedConfig");
        return regenerateMissingElements(element, specNode instanceof EnableAdvancedNode eaNode && eaNode.advancedEnabled);
    }
    
    private Element regenerateMissingElements(Element element, boolean enableAdvanced) {
        assert element.subArray != null;
        final var newElements = new ObjectArrayList<Element>();
        for (final var entry : subNodes.entrySet()) {
            nextEntry:
            {
                final var node = entry.getValue();
                if (node.hidden || node.advanced && !enableAdvanced) {
                    break nextEntry;
                }
                var name = entry.getKey();
                for (final var value : element.subArray) {
                    if (name.equals(value.name)) {
                        break nextEntry;
                    }
                }
                final Element newElement;
                if (node instanceof SpecObjectNode objectNode) {
                    newElement = objectNode.generateDefaultElement(enableAdvanced);
                } else {
                    newElement = node.generateDefaultElement();
                }
                if (newElement == null) {
                    break nextEntry;
                }
                newElements.add(newElement);
            }
        }
        
        final var subElements = new Element[element.subArray.length + newElements.size()];
        int i = 0;
        for (int j = 0; j < element.subArray.length; i++, j++) {
            var subElement = element.subArray[j];
            var subNode = subNodes.get(subElement.name);
            if (subNode instanceof SpecObjectNode objectNode) {
                subElement = objectNode.regenerateMissingElements(subElement, enableAdvanced);
            } else {
                assert subElement.value != null;
                subElement = new Element(subElement.type, Objects.requireNonNull(subNode.generateDefaultElement()).comment, subElement.name, subElement.value);
            }
            subElements[i] = subElement;
        }
        for (int j = 0; j < newElements.size(); i++, j++) {
            subElements[i] = newElements.get(j);
        }
        
        return new Element(Element.Type.Map, baseComment, name, subElements);
    }
    
    @Nullable
    public Element removeUnknownElements(Element element) {
        final var retainedElements = new ObjectArrayList<Element>();
        
        assert element.subArray != null;
        for (var value : element.subArray) {
            final var subNode = subNodes.get(value.name);
            if (subNode != null) {
                if (subNode instanceof SpecObjectNode objectNode) {
                    value = objectNode.removeUnknownElements(value);
                }
                if (value == null) {
                    continue;
                }
                retainedElements.add(value);
            }
        }
        
        if (retainedElements.isEmpty()) {
            return null;
        }
        
        return new Element(Element.Type.Map, element.comment, name, retainedElements.toArray(new Element[0]));
    }
    
    public Element correctElementOrder(Element element) {
        final var elementMap = Arrays.stream(element.subArray).collect(Collectors.toMap(node -> node.name, node -> node));
        final var newElementArray = new Element[element.subArray.length];
        int i = 0;
        for (final var value : subNodeList) {
            var subElement = elementMap.remove(value.name);
            if (subElement == null) {
                continue;
            }
            if (value instanceof SpecObjectNode objectNode) {
                subElement = objectNode.correctElementOrder(subElement);
            }
            newElementArray[i++] = subElement;
        }
        if (i != newElementArray.length) {
            // unknown elements are shoved at  end
            for (final var value : elementMap.values()) {
                newElementArray[i++] = value;
            }
        }
        return new Element(element.type, element.comment, element.name, newElementArray);
    }
    
    @Nullable
    public Element trimToReloadable(Element element) {
        final var retainedElements = new ObjectArrayList<Element>();
        
        assert element.subArray != null;
        for (var value : element.subArray) {
            final var subNode = subNodes.get(value.name);
            if (subNode != null) {
                if (subNode instanceof SpecObjectNode objectNode) {
                    value = objectNode.trimToReloadable(value);
                } else if (!subNode.reloadable) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                retainedElements.add(value);
            }
        }
        
        if (retainedElements.isEmpty()) {
            return null;
        }
        
        return new Element(Element.Type.Map, element.comment, name, retainedElements.toArray(new Element[0]));
    }
}
