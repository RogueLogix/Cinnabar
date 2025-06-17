package graphics.cinnabar.api.memory;

public class MagicMemorySizes {
    public static final long KiB = 1024;
    public static final long MiB = KiB * 1024;
    public static final long GiB = MiB * 1024;
    
    public static final long MEMORY_POOL_BLOCK_SIZE = 16 * MiB;
    
    public static final int FLOAT_BYTE_SIZE = 4;
    public static final int DOUBLE_BYTE_SIZE = 8;
    public static final int SHORT_BYTE_SIZE = 2;
    public static final int INT_BYTE_SIZE = 4;
    public static final int LONG_BYTE_SIZE = 8;
    public static final int VEC2_BYTE_SIZE = FLOAT_BYTE_SIZE * 2;
    public static final int IVEC2_BYTE_SIZE = INT_BYTE_SIZE * 2;
    public static final int VEC3_BYTE_SIZE = FLOAT_BYTE_SIZE * 3;
    public static final int IVEC3_BYTE_SIZE = INT_BYTE_SIZE * 3;
    public static final int VEC4_BYTE_SIZE = FLOAT_BYTE_SIZE * 4;
    public static final int IVEC4_BYTE_SIZE = INT_BYTE_SIZE * 4;
    
    public static final int GLSL_MATRIX_3F_BYTE_SIZE = 48;
    public static final int MATRIX_4F_BYTE_SIZE = 64;
    
    public static long sizeofGLSLType(String type) {
        return switch (type) {
            // TODO: there are more types
            case "int8_t", "uint8_t" -> 1;
            case "int16_t", "uint16_t" -> 2;
            case "float", "int", "uint" -> 4;
            case "vec2", "ivec2", "uvec2" -> 8;
            case "vec3", "ivec3", "uvec3" -> 12; // sizeof, not alignof
            case "vec4", "ivec4", "uvec4" -> 16;
            case "mat3" -> MagicMemorySizes.GLSL_MATRIX_3F_BYTE_SIZE;
            case "mat4" -> MagicMemorySizes.MATRIX_4F_BYTE_SIZE;
            default -> throw new IllegalStateException("Unexpected value: " + type);
        };
    }
}
