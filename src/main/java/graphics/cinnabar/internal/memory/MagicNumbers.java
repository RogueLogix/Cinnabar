package graphics.cinnabar.internal.memory;

public class MagicNumbers {
    public static final long KiB = 1024;
    public static final long MiB = KiB * 1024;
    public static final long GiB = MiB * 1024;
    
    public static final int VERTEX_BYTE_SIZE = 32;
    public static final int FLOAT_BYTE_SIZE = 4;
    public static final int DOUBLE_BYTE_SIZE = 8;
    public static final int SHORT_BYTE_SIZE = 2;
    public static final int INT_BYTE_SIZE = 4;
    public static final int LONG_BYTE_SIZE = 8;
    public static final int VEC3_BYTE_SIZE = FLOAT_BYTE_SIZE * 3;
    public static final int IVEC3_BYTE_SIZE = INT_BYTE_SIZE * 3;
    public static final int VEC4_BYTE_SIZE = FLOAT_BYTE_SIZE * 4;
    public static final int IVEC4_BYTE_SIZE = INT_BYTE_SIZE * 4;
    
    public static final int GLSL_MATRIX_3F_BYTE_SIZE = 48;
    public static final int MATRIX_4F_BYTE_SIZE = 64;
}
