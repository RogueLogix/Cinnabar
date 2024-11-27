package net.roguelogix.phosphophyllite.config.spec;

import net.roguelogix.phosphophyllite.util.NonnullDefault;

@NonnullDefault
public class ConfigSpecUtil {
    /*
     * TODO:
     *  Should correcting to valid care about the element's name?
     *  Assets, make them better errors
     *  Valid value checking, in general, most writing doesnt check
     */
    
    
    public static boolean isIntegral(Class<?> numberType) {
        return
                numberType == Byte.class || numberType == byte.class ||
                        numberType == Short.class || numberType == short.class ||
                        numberType == Integer.class || numberType == int.class ||
                        numberType == Long.class || numberType == long.class;
    }
    
    public static boolean isFloat(Class<?> numberType) {
        return
                numberType == Float.class || numberType == float.class ||
                        numberType == Double.class || numberType == double.class;
    }
}
