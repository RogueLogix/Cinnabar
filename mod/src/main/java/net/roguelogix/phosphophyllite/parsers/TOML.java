package net.roguelogix.phosphophyllite.parsers;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlParser;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class TOML {
    
    @Nullable
    public static Element parseString(String string) {
        try {
            return parseObject(new TomlParser().parse(string), null);
        } catch (ParsingException e) {
            e.printStackTrace();
            return null;
        }
    }
    
    private static Element parseObject(Object obj, @Nullable String name) {
        if (obj instanceof Config) {
            return parseObject(((Config) obj).valueMap(), name);
        } else if (obj instanceof Map) {
            final ArrayList<Element> subElements = new ArrayList<>();
            ((Map<?, ?>) obj).forEach((str, obj1) -> subElements.add(parseObject(obj1, (String) str)));
            return new Element(Element.Type.Map, null, name, subElements.toArray(new Element[0]));
        } else if (obj instanceof List) {
            final ArrayList<Element> subElements = new ArrayList<>();
            ((List<?>) obj).forEach(e -> subElements.add(parseObject(e, null)));
            return new Element(Element.Type.Array, null, name, subElements.toArray(new Element[0]));
        } else if (obj instanceof String) {
            return new Element(Element.Type.String, null, name, obj);
        } else if (obj instanceof Boolean) {
            return new Element(Element.Type.Boolean, null, name, obj);
        } else if (obj instanceof Number) {
            return new Element(Element.Type.Number, null, name, obj);
        }
        throw new IllegalStateException("Unknown object type");
    }
    
    private static void newLine(int indentLevel, StringBuilder builder) {
        builder.append("\n");
        for (int i = 0; i < indentLevel; i++) {
            builder.append("    ");
        }
    }
    
    public static String parseElement(Element element) {
        StringBuilder builder = new StringBuilder();
        parseElement(element, 0, builder, false, null);
        return builder.substring(2, builder.length() - 2);
    }
    
    private static void parseElement(Element element, int indentLevel, StringBuilder builder, boolean omitComments, @Nullable String currentSectionName) {
        var commentText = "";
        if (!omitComments && element.comment != null) {
            StringBuilder commentBuilder = new StringBuilder();
            if (!element.comment.isEmpty()) {
                String[] commentLines = element.comment.split("\n");
                if (builder.isEmpty()) {
                    newLine(indentLevel, commentBuilder);
                }
                newLine(indentLevel, commentBuilder);
                commentBuilder.append("#");
                for (String commentLine : commentLines) {
                    newLine(indentLevel, commentBuilder);
                    commentBuilder.append("# ");
                    commentBuilder.append(commentLine);
                }
                newLine(indentLevel, commentBuilder);
                commentBuilder.append("#");
                commentText = commentBuilder.toString();
            }
        }
        switch (element.type) {
            case String: {
                builder.append(commentText);
                newLine(indentLevel, builder);
                builder.append(element.name).append(" = ");
                String value = element.asString();
                value = value.replace("\n", "\\n");
                value = value.replace("\r", "\\r");
                value = value.replace("\"", "\\\"");
                builder.append("\"");
                builder.append(value);
                builder.append("\"");
                break;
            }
            case Boolean:
            case Number: {
                builder.append(commentText);
                newLine(indentLevel, builder);
                builder.append(element.name).append(" = ");
                builder.append(element.asString());
                break;
            }
            case Array: {
                builder.append(commentText);
                newLine(indentLevel, builder);
                builder.append("# Arrays are not supported with TOML");
                newLine(indentLevel, builder);
                break;
            }
            case Map: {
                assert element.subArray != null;
                Element[] elements = Arrays.copyOf(element.subArray, element.subArray.length);
                Arrays.sort(elements, (a, b) -> {
                    if (a.type == b.type) {
                        return 0;
                    }
                    if (a.type == Element.Type.Map) {
                        return 1;
                    }
                    if (b.type == Element.Type.Map) {
                        return -1;
                    }
                    return 0;
                });
                String sectionname = null;
                if (element.name != null) {
                    sectionname = (currentSectionName == null ? "" : currentSectionName + ".") + element.name;
                    if (Arrays.stream(elements).anyMatch(a -> a.type != Element.Type.Map)) {
                        builder.append(commentText);
                        newLine(indentLevel, builder);
                        builder.append('[').append(sectionname).append(']');
                        indentLevel++;
                    }
                }
                for (Element value : elements) {
                    parseElement(value, indentLevel, builder, omitComments, sectionname);
                    if (value.type == Element.Type.Map) {
                        builder.deleteCharAt(builder.length() - 1);
                    }
                }
                if (element.name != null && Arrays.stream(elements).anyMatch(a -> a.type != Element.Type.Map)) {
                    indentLevel--;
                }
                builder.deleteCharAt(builder.length() - 1);
                newLine(indentLevel, builder);
                break;
            }
        }
        builder.append('\n');
    }
}
