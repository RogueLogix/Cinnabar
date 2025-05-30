package graphics.cinnabar.core.vk.shaders.vertex;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceImmutableList;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

public record VertexInputState(List<Buffer> buffers, List<Attrib> attribs) {
    
    
    public static final VertexInputState EMPTY = new VertexInputState(List.of(), List.of());
    
    public record Buffer(int bindingIndex, int stride, boolean perInstance) {
        public Buffer(int bindingIndex, int stride) {
            this(bindingIndex, stride, false);
        }
    }
    
    public record Attrib(@Nullable String name, int location, int bufferBinding, int format, int offset) {
        public Attrib(String name, int bufferBinding, int format, int offset) {
            this(name, -1, bufferBinding, format, offset);
        }
        
        public Attrib(int location, int bufferBinding, int format, int offset) {
            this(null, location, bufferBinding, format, offset);
        }
    }
    
    public VertexInputState(List<Buffer> buffers, List<Attrib> attribs) {
        this.buffers = new ReferenceImmutableList<>(buffers);
        this.attribs = new ReferenceImmutableList<>(attribs);
        // TODO: validate buffer/attrib bindings
        if ((buffers.isEmpty() || attribs.isEmpty()) && !(buffers.isEmpty() && attribs.isEmpty())) {
            throw new IllegalArgumentException();
        }
    }
    
    public VertexInputState(Buffer buffer, List<Attrib> attribs) {
        this(List.of(buffer), attribs);
    }
    
    public VertexInputState(Buffer buffer, Attrib... attribs) {
        this(List.of(buffer), List.of(attribs));
    }
    
    public static VertexInputState forVertexFormat(VertexFormat format, Map<String, @Nullable String> attribTypes) {
        if (format.getVertexSize() == 0) {
            return EMPTY;
        }
        final var attribs = new ReferenceArrayList<Attrib>();
        for (final var element : format.getElements()) {
            final var elementName = format.getElementName(element);
            final var elementFormat = mapTypeAndCountToVkFormat(element.type(), element.count(), element.usage(), isFloatInputType(attribTypes.get(elementName)));
            final var elementOffset = format.getOffset(element);
            attribs.add(new Attrib(elementName, 0, elementFormat, elementOffset));
        }
        return new VertexInputState(new Buffer(0, format.getVertexSize()), attribs);
    }
    
    @SuppressWarnings("DuplicateBranchesInSwitch")
    private static boolean isFloatInputType(@Nullable String input) {
        if (input == null) {
            // if null for a specific attrib set, then it can be treated as int, it wont actually be read by the shader
            return false;
        }
        return switch (input) {
            case "uint", "uvec2", "uvec3", "uvec4" -> false;
            case "int", "ivec2", "ivec3", "ivec4" -> false;
            case "float", "vec2", "vec3", "vec4" -> true;
            default -> throw new IllegalStateException("Unexpected value: " + input);
        };
    }
    
    // TODO: INT vs SCALED for float input types with integers in the buffers    
    private static int mapTypeAndCountToVkFormat(VertexFormatElement.Type type, int count, VertexFormatElement.Usage usage, boolean floatInput) {
        final boolean normalized = switch (usage) {
            case COLOR, NORMAL -> true;
            default -> false;
        };
        return switch (type) {
            case FLOAT -> switch (count) {
                case 1 -> VK_FORMAT_R32_SFLOAT;
                case 2 -> VK_FORMAT_R32G32_SFLOAT;
                case 3 -> VK_FORMAT_R32G32B32_SFLOAT;
                case 4 -> VK_FORMAT_R32G32B32A32_SFLOAT;
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case UBYTE -> switch (count) {
                case 1 -> normalized ? VK_FORMAT_R8_UNORM : VK_FORMAT_R8_UINT;
                case 2 -> normalized ? VK_FORMAT_R8G8_UNORM : VK_FORMAT_R8G8_UINT;
                case 3 -> normalized ? VK_FORMAT_R8G8B8_UNORM : VK_FORMAT_R8G8B8_UINT;
                case 4 -> normalized ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_R8G8B8A8_UINT;
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case BYTE -> switch (count) {
                case 1 -> normalized ? VK_FORMAT_R8_SNORM : (floatInput ? VK_FORMAT_R8_SSCALED : VK_FORMAT_R8_SINT);
                case 2 -> normalized ? VK_FORMAT_R8G8_SNORM : (floatInput ? VK_FORMAT_R8G8_SSCALED : VK_FORMAT_R8G8_SINT);
                case 3 -> normalized ? VK_FORMAT_R8G8B8_SNORM : (floatInput ? VK_FORMAT_R8G8B8_SSCALED : VK_FORMAT_R8G8B8_SINT);
                case 4 -> normalized ? VK_FORMAT_R8G8B8A8_SNORM : (floatInput ? VK_FORMAT_R8G8B8A8_SSCALED : VK_FORMAT_R8G8B8A8_SINT);
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case USHORT -> switch (count) {
                case 1 -> normalized ? VK_FORMAT_R16_UNORM : VK_FORMAT_R16_UINT;
                case 2 -> normalized ? VK_FORMAT_R16G16_UNORM : VK_FORMAT_R16G16_UINT;
                case 3 -> normalized ? VK_FORMAT_R16G16B16_UNORM : VK_FORMAT_R16G16B16_UINT;
                case 4 -> normalized ? VK_FORMAT_R16G16B16A16_UNORM : VK_FORMAT_R16G16B16A16_UINT;
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case SHORT -> switch (count) {
                case 1 -> normalized ? VK_FORMAT_R16_SNORM : (floatInput ? VK_FORMAT_R16_SSCALED : VK_FORMAT_R16_SINT);
                case 2 -> normalized ? VK_FORMAT_R16G16_SNORM : (floatInput ? VK_FORMAT_R16G16_SSCALED : VK_FORMAT_R16G16_SINT);
                case 3 -> normalized ? VK_FORMAT_R16G16B16_SNORM : (floatInput ? VK_FORMAT_R16G16B16_SSCALED : VK_FORMAT_R16G16B16_SINT);
                case 4 -> normalized ? VK_FORMAT_R16G16B16A16_SNORM : (floatInput ? VK_FORMAT_R16G16B16A16_SSCALED : VK_FORMAT_R16G16B16A16_SINT);
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case UINT -> switch (count) {
                case 1 -> VK_FORMAT_R32_UINT;
                case 2 -> VK_FORMAT_R32G32_UINT;
                case 3 -> VK_FORMAT_R32G32B32_UINT;
                case 4 -> VK_FORMAT_R32G32B32A32_UINT;
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
            case INT -> switch (count) {
                case 1 -> VK_FORMAT_R32_SINT;
                case 2 -> VK_FORMAT_R32G32_SINT;
                case 3 -> VK_FORMAT_R32G32B32_SINT;
                case 4 -> VK_FORMAT_R32G32B32A32_SINT;
                default -> throw new IllegalStateException("Unexpected value: " + count);
            };
        };
    }
}
