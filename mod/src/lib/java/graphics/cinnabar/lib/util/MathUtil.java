package graphics.cinnabar.lib.util;

public class MathUtil {
    public static int clampToInt(long val) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, val));
    }
    
    public static int roundUpPo2(int minSize) {
        int size = Integer.highestOneBit(minSize);
        if (size < minSize) {
            size <<= 1;
        }
        return size;
    }
    
    public static long roundUpPo2(long minSize) {
        long size = Long.highestOneBit(minSize);
        if (size < minSize) {
            size <<= 1;
        }
        return size;
    }
}
