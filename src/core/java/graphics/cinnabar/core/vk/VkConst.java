package graphics.cinnabar.core.vk;

import net.neoforged.neoforge.client.stencil.StencilFunction;
import net.neoforged.neoforge.client.stencil.StencilOperation;

import static org.lwjgl.vulkan.VK12.*;

public class VkConst {
    
    public static int toVk(StencilOperation op) {
        return switch (op){
            case KEEP -> VK_STENCIL_OP_KEEP;
            case ZERO -> VK_STENCIL_OP_ZERO;
            case REPLACE -> VK_STENCIL_OP_REPLACE;
            case INCR -> VK_STENCIL_OP_INCREMENT_AND_CLAMP;
            case DECR -> VK_STENCIL_OP_DECREMENT_AND_CLAMP;
            case INVERT -> VK_STENCIL_OP_INVERT;
            case INCR_WRAP -> VK_STENCIL_OP_INCREMENT_AND_WRAP;
            case DECR_WRAP -> VK_STENCIL_OP_DECREMENT_AND_WRAP;
        };
    }
    
    public static int toVk(StencilFunction compare) {
        return switch (compare){
            case NEVER -> VK_COMPARE_OP_NEVER;
            case ALWAYS -> VK_COMPARE_OP_ALWAYS;
            case LESS -> VK_COMPARE_OP_LESS;
            case LEQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
            case EQUAL -> VK_COMPARE_OP_EQUAL;
            case GEQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            case GREATER -> VK_COMPARE_OP_GREATER;
            case NOTEQUAL -> VK_COMPARE_OP_NOT_EQUAL;
        };
    }
}
