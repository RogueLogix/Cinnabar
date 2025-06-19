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
    
    public static long BtoKB(long bytes) {
        return bytes / 1024;
    }
    
    public static long BtoMB(long bytes) {
        return BtoKB(bytes) / 1024;
    }
    
    public static long KBToB(long kilobytes) {
        return kilobytes * 1024;
    }
    
    public static long MBToB(long megabytes) {
        return KBToB(megabytes * 1024);
    }
    
    private static final String[] suffixes = new String[]{
            "B",
            "KiB",
            "MiB",
            "GiB",
            "TiB",
            "PiB",
            "EiB",
            "ZiB",
            "YiB",
    };
    
    public static String byteString(long bytes) {
        if (bytes < 1024) {
            return String.format("%dB", bytes);
        }
        double fpBytes = bytes;
        int suffixIndex = 0;
        while (fpBytes > 1024) {
            fpBytes /= 1024d;
            suffixIndex++;
        }
        return String.format("%.1f%s", fpBytes, suffixes[suffixIndex]);
    }
}
