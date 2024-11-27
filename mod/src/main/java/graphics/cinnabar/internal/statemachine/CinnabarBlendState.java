package graphics.cinnabar.internal.statemachine;

import org.lwjgl.vulkan.VkColorBlendEquationEXT;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_DST_COLOR;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_COLOR;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_SRC_COLOR;
import static org.lwjgl.opengl.GL11C.GL_ZERO;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.vulkan.EXTExtendedDynamicState3.vkCmdSetColorBlendEnableEXT;
import static org.lwjgl.vulkan.EXTExtendedDynamicState3.vkCmdSetColorBlendEquationEXT;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;

public class CinnabarBlendState {
    
    private static boolean enabled = false;
    private static final VkColorBlendEquationEXT.Buffer colorBlendEquations = VkColorBlendEquationEXT.calloc(1);
    
    public static void setEnabled(boolean enabled) {
        CinnabarBlendState.enabled = enabled;
    }
    
    public static void setBlendFactors(int srcColorFactor, int dstColorFactor, int srcAlphaFactor, int dstAlphaFactor) {
        colorBlendEquations.srcColorBlendFactor(glToVkBlendFactor(srcColorFactor));
        colorBlendEquations.dstColorBlendFactor(glToVkBlendFactor(dstColorFactor));
        colorBlendEquations.colorBlendOp(VK_BLEND_OP_ADD);
        colorBlendEquations.srcAlphaBlendFactor(glToVkBlendFactor(srcAlphaFactor));
        colorBlendEquations.dstAlphaBlendFactor(glToVkBlendFactor(dstAlphaFactor));
        colorBlendEquations.alphaBlendOp(VK_BLEND_OP_ADD);
    }
    
    public static void apply(VkCommandBuffer commandBuffer) {
        vkCmdSetColorBlendEnableEXT(commandBuffer, 0, new int[]{enabled ? VK_TRUE : VK_FALSE});
        vkCmdSetColorBlendEquationEXT(commandBuffer, 0, colorBlendEquations);
    }
    
    private static int glToVkBlendFunc(int glFunc) {
        return switch (glFunc) {
            case GL_FUNC_ADD -> VK_BLEND_OP_ADD;
            case GL_FUNC_SUBTRACT -> VK_BLEND_OP_SUBTRACT;
            case GL_FUNC_REVERSE_SUBTRACT -> VK_BLEND_OP_REVERSE_SUBTRACT;
            case GL_MIN -> VK_BLEND_OP_MIN;
            case GL_MAX -> VK_BLEND_OP_MAX;
            default -> throw new IllegalStateException("Unsupported blend func");
        };
    }
    
    private static int glToVkBlendFactor(int glFactor) {
        return switch (glFactor) {
            case GL_ZERO -> VK_BLEND_FACTOR_ZERO;
            case GL_ONE -> VK_BLEND_FACTOR_ONE;
            case GL_SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA;
            case GL_ONE_MINUS_SRC_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case GL_SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR;
            case GL_ONE_MINUS_SRC_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case GL_ONE_MINUS_DST_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            default -> -1;
        };
    }
    
    private static int vkToGLBlendFactor(int vkFactor) {
        return switch (vkFactor) {
            case VK_BLEND_FACTOR_ZERO -> GL_ZERO;
            case VK_BLEND_FACTOR_ONE -> GL_ONE;
            case VK_BLEND_FACTOR_SRC_ALPHA -> GL_SRC_ALPHA;
            case VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA -> GL_ONE_MINUS_SRC_ALPHA;
            case VK_BLEND_FACTOR_SRC_COLOR -> GL_SRC_COLOR;
            case VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR -> GL_ONE_MINUS_SRC_COLOR;
            case VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR -> GL_ONE_MINUS_DST_COLOR;
            default -> -1;
        };
    }
    
    public static boolean enabled() {
        return enabled;
    }
    
    public static int srcRgb() {
        return vkToGLBlendFactor(colorBlendEquations.srcColorBlendFactor());
    }
    
    public static int dstRgb() {
        return vkToGLBlendFactor(colorBlendEquations.dstColorBlendFactor());
    }
    
    public static int srcAlpha() {
        return vkToGLBlendFactor(colorBlendEquations.srcAlphaBlendFactor());
    }
    
    public static int dstAlpha() {
        return vkToGLBlendFactor(colorBlendEquations.dstAlphaBlendFactor());
    }
}
