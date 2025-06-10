package graphics.cinnabar.lib.config.spec;

import graphics.cinnabar.lib.config.ConfigValue;
import graphics.cinnabar.lib.parsers.Element;
import graphics.cinnabar.lib.util.TriConsumer;

import java.lang.reflect.Field;
import java.util.function.BiFunction;

public class SpecFloatNode extends SpecNumberNode {
    public enum FloatType {
        FLOAT((f, o, l) -> f.setFloat(o, l.floatValue()), (val, direction) -> (double) Math.nextAfter(val.floatValue(), direction)),
        DOUBLE(Field::setDouble, Math::nextAfter),
        ;
        
        private final TriConsumer.WithException<Field, Object, Double, IllegalAccessException> writeFunction;
        private final BiFunction<Double, Double, Double> nextAfter;
        
        FloatType(TriConsumer.WithException<Field, Object, Double, IllegalAccessException> writeFunction, BiFunction<Double, Double, Double> nextAfter) {
            this.writeFunction = writeFunction;
            this.nextAfter = nextAfter;
        }
        
        public void write(Field field, Object obj, double val) {
            try {
                writeFunction.accept(field, obj, val);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        
        public static FloatType fromClass(Class<?> clazz) {
            if (clazz == Double.class || clazz == double.class) {
                return DOUBLE;
            }
            
            if (clazz == Float.class || clazz == float.class) {
                return FLOAT;
            }
            
            throw new IllegalArgumentException();
        }
        
        public double nextAfter(double val, double direction) {
            return nextAfter.apply(val, direction);
        }
    }
    
    private final FloatType type;
    public final double lowerBound;
    public final double upperBound;
    public final double defaultValue;
    
    SpecFloatNode(SpecObjectNode parent, Field field, ConfigOptionsDefaults defaults) {
        super(parent, field, defaults);
        final var annotation = field.getAnnotation(ConfigValue.class);
        
        final var range = annotation.range().trim().substring(1, annotation.range().length() - 1).trim();
        final var bounds = range.split(",");
        
        var lowerBoundStr = "";
        var upperBoundStr = "";
        if (bounds.length == 2) {
            lowerBoundStr = bounds[0].trim();
            upperBoundStr = bounds[1].trim();
        } else {
            // ranges split will ommit an empty string at the beginning or end, so, i need to check if you did (,x] or [x,)
            if (range.length() == 0) {
                throw new DefinitionError("Incomplete range given");
            }
            if (range.length() != 1) {
                if (bounds.length != 1) {
                    throw new DefinitionError("Incomplete range given");
                }
                if (range.charAt(0) == ',') {
                    upperBoundStr = bounds[0];
                } else if (range.charAt(range.length() - 1) == ',') {
                    lowerBoundStr = bounds[0];
                } else {
                    throw new DefinitionError("Incomplete range given");
                }
            } else if (range.charAt(0) != ',') {
                throw new DefinitionError("Incomplete range given");
            }
        }
        
        double lowerBound = Double.MIN_VALUE;
        if (lowerBoundStr.length() != 0) {
            lowerBound = Double.parseDouble(lowerBoundStr);
        }
        double upperBound = Double.MAX_VALUE;
        if (upperBoundStr.length() != 0) {
            upperBound = Double.parseDouble(upperBoundStr);
        }
        
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.defaultValue = (double) currentValueObject();
        type = FloatType.fromClass(field.getType());
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
        type.write(field, parent.object(), Double.parseDouble(string));
    }
    
    @Override
    public boolean isValueValid(String valueString) {
        double val;
        try {
            val = Double.parseDouble(valueString);
        } catch (NumberFormatException e) {
            return false;
        }
        return isValueValid(val);
        
    }
    
    public boolean isValueValid(double val) {
        if ((lowerInclusive && val != lowerBound) || val < lowerBound) {
            return false;
        }
        if ((upperInclusive && val != upperBound) || val > upperBound) {
            return false;
        }
        return false;
    }
    
    @Override
    public String lowerBoundAsString() {
        return String.valueOf(lowerBound);
    }
    
    @Override
    public String upperBoundAsString() {
        return String.valueOf(upperBound);
    }
    
    @Override
    public void writeDefault() {
        type.write(field, parent.object(), defaultValue);
    }
    
    @Override
    public Element generateDefaultElement() {
        return new Element(Element.Type.Number, generateComment(), name, defaultValue);
    }
    
    @Override
    public Element generateCurrentElement() {
        return new Element(Element.Type.Number, generateComment(), name, currentValueObject());
    }
    
    @Override
    public Element generateSyncElement() {
        return new Element(Element.Type.Number, null, name, currentValueObject());
    }
    
    @Override
    public String generateComment() {
        final var comment = new StringBuilder(baseComment);
        final var fieldAnnotation = field.getAnnotation(ConfigValue.class);
    
        if (!fieldAnnotation.range().equals("(,)")) {
            if (comment.length() != 0) {
                comment.append('\n');
            }
            comment.append("Valid range: ").append(fieldAnnotation.range());
        }
    
        if (comment.length() != 0) {
            comment.append('\n');
        }
        comment.append("Default: ");
        comment.append(defaultValue);
    
        return comment.toString();
    }
    
    @Override
    public Element correctToValidState(Element element) {
        if (element.type != Element.Type.Number || !(element.value instanceof Number)) {
            return generateDefaultElement();
        }
        if (isValueValid(element.asLong())) {
            return element;
        }
        double val = element.asDouble();
        val = Math.min(Math.max(val, lowerBound), upperBound);
        if (!lowerInclusive && val == lowerBound) {
            val = type.nextAfter(lowerBound, Double.POSITIVE_INFINITY);
        }
        if (!upperInclusive && val == upperBound) {
            val = type.nextAfter(lowerBound, Double.NEGATIVE_INFINITY);
        }
        return new Element(Element.Type.Number, generateComment(), name, val);
    }
    
    @Override
    public void writeElement(Element element) {
        type.write(field, parent.object(), element.asDouble());
    }
}
