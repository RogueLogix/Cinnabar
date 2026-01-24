package graphics.cinnabar.api.util;

public class UnsignedMath {
    public static long min(long a, long b) {
        // if only one is negative, the "greater" one is actually the smaller number
        if (a < 0 != b < 0) {
            return Math.max(a, b);
        } else {
            return Math.min(a, b);
        }
    }
    
    public static long max(long a, long b) {
        // if only one is negative, the "smaller" one is actually the greater number
        if (a < 0 != b < 0) {
            return Math.min(a, b);
        } else {
            return Math.max(a, b);
        }
    }
}
