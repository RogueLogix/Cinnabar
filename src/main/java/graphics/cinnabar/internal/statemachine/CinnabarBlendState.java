package graphics.cinnabar.internal.statemachine;

import static org.lwjgl.opengl.GL14C.GL_FUNC_ADD;

public class CinnabarBlendState {
    
    private static boolean enabled = false;
    
    public static void setEnabled(boolean enabled) {
        CinnabarBlendState.enabled = enabled;
    }
    
    public static void setBlendFactors(int srcColorFactor, int dstColorFactor, int srcAlphaFactor, int dstAlphaFactor) {
    }
}
