package graphics.cinnabar.internal.statemachine;

import static org.lwjgl.vulkan.VK10.VK_LOGIC_OP_NO_OP;

public class CinnabarGeneralState {
    public static boolean cull = false;
    public static boolean depthTest = false;
    public static boolean depthWrite = false;
    public static int depthFunc = 0;
    
    public static boolean logicOpEnable = false;
    public static int logicOp = VK_LOGIC_OP_NO_OP;
}
