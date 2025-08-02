package graphics.cinnabar.core.hg3d;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import graphics.cinnabar.api.hg.HgGraphicsPipeline;
import graphics.cinnabar.api.hg.enums.HgCompareOp;
import graphics.cinnabar.api.hg.enums.HgFormat;
import net.neoforged.neoforge.client.stencil.StencilFunction;
import net.neoforged.neoforge.client.stencil.StencilOperation;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

public class Hg3DConst {
    
    private static final List<HgFormat> formatValues = List.of(HgFormat.values());
    
    public static boolean normalized(VertexFormatElement.Usage usage) {
        return switch (usage) {
            case COLOR, NORMAL -> true;
            default -> false;
        };
    }
    
    public static HgFormat vertexInputFormat(VertexFormatElement.Type type, int count, boolean normalized) {
        return switch (type) {
            case FLOAT -> formatValues.get(HgFormat.R32_SFLOAT.ordinal() + count - 1);
            case UBYTE -> formatValues.get((normalized ? HgFormat.R8_UNORM : HgFormat.R8_UINT).ordinal() + count - 1);
            case BYTE -> formatValues.get((normalized ? HgFormat.R8_SNORM : HgFormat.R8_SINT).ordinal() + count - 1);
            case USHORT -> formatValues.get((normalized ? HgFormat.R16_UNORM : HgFormat.R16_UINT).ordinal() + count - 1);
            case SHORT -> formatValues.get((normalized ? HgFormat.R16_SNORM : HgFormat.R16_SINT).ordinal() + count - 1);
            case UINT -> formatValues.get(HgFormat.R32_SINT.ordinal() + count - 1);
            case INT -> formatValues.get(HgFormat.R32_UINT.ordinal() + count - 1);
        };
    }
    
    public static HgGraphicsPipeline.Blend.Factor factor(SourceFactor sourceFactor) {
        return switch (sourceFactor) {
            case CONSTANT_ALPHA -> HgGraphicsPipeline.Blend.Factor.CONSTANT_ALPHA;
            case CONSTANT_COLOR -> HgGraphicsPipeline.Blend.Factor.CONSTANT_COLOR;
            case DST_ALPHA -> HgGraphicsPipeline.Blend.Factor.DST_ALPHA;
            case DST_COLOR -> HgGraphicsPipeline.Blend.Factor.DST_COLOR;
            case ONE -> HgGraphicsPipeline.Blend.Factor.ONE;
            case ONE_MINUS_CONSTANT_ALPHA -> HgGraphicsPipeline.Blend.Factor.ONE_MINUS_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_COLOR -> HgGraphicsPipeline.Blend.Factor.ONE_MINUS_CONSTANT_COLOR;
            case ONE_MINUS_DST_ALPHA -> HgGraphicsPipeline.Blend.Factor.ONE_MINUS_DST_ALPHA;
            case ONE_MINUS_DST_COLOR -> HgGraphicsPipeline.Blend.Factor.ONE_MINUS_DST_COLOR;
            case ONE_MINUS_SRC_ALPHA -> HgGraphicsPipeline.Blend.Factor.ONE_MINUS_SRC_ALPHA;
            case ONE_MINUS_SRC_COLOR -> HgGraphicsPipeline.Blend.Factor.ONE_MINUS_SRC_COLOR;
            case SRC_ALPHA -> HgGraphicsPipeline.Blend.Factor.SRC_ALPHA;
            case SRC_ALPHA_SATURATE -> HgGraphicsPipeline.Blend.Factor.SRC_ALPHA_SATURATE;
            case SRC_COLOR -> HgGraphicsPipeline.Blend.Factor.SRC_COLOR;
            case ZERO -> HgGraphicsPipeline.Blend.Factor.ZERO;
        };
    }
    
    public static HgGraphicsPipeline.Blend.Factor factor(DestFactor destFactor) {
        return switch (destFactor) {
            case CONSTANT_ALPHA -> HgGraphicsPipeline.Blend.Factor.CONSTANT_ALPHA;
            case CONSTANT_COLOR -> HgGraphicsPipeline.Blend.Factor.CONSTANT_COLOR;
            case DST_ALPHA -> HgGraphicsPipeline.Blend.Factor.DST_ALPHA;
            case DST_COLOR -> HgGraphicsPipeline.Blend.Factor.DST_COLOR;
            case ONE -> HgGraphicsPipeline.Blend.Factor.ONE;
            case ONE_MINUS_CONSTANT_ALPHA -> HgGraphicsPipeline.Blend.Factor.ONE_MINUS_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_COLOR -> HgGraphicsPipeline.Blend.Factor.ONE_MINUS_CONSTANT_COLOR;
            case ONE_MINUS_DST_ALPHA -> HgGraphicsPipeline.Blend.Factor.ONE_MINUS_DST_ALPHA;
            case ONE_MINUS_DST_COLOR -> HgGraphicsPipeline.Blend.Factor.ONE_MINUS_DST_COLOR;
            case ONE_MINUS_SRC_ALPHA -> HgGraphicsPipeline.Blend.Factor.ONE_MINUS_SRC_ALPHA;
            case ONE_MINUS_SRC_COLOR -> HgGraphicsPipeline.Blend.Factor.ONE_MINUS_SRC_COLOR;
            case SRC_ALPHA -> HgGraphicsPipeline.Blend.Factor.SRC_ALPHA;
            case SRC_COLOR -> HgGraphicsPipeline.Blend.Factor.SRC_COLOR;
            case ZERO -> HgGraphicsPipeline.Blend.Factor.ZERO;
        };
    }
    
    public static HgCompareOp stencil(StencilFunction equation) {
        return switch (equation) {
            case NEVER -> HgCompareOp.NEVER;
            case ALWAYS -> HgCompareOp.ALWAYS;
            case LESS -> HgCompareOp.LESS;
            case LEQUAL -> HgCompareOp.LESS_OR_EQUAL;
            case EQUAL -> HgCompareOp.EQUAL;
            case GEQUAL -> HgCompareOp.GREATER_OR_EQUAL;
            case GREATER -> HgCompareOp.GREATER;
            case NOTEQUAL -> HgCompareOp.NOT_EQUAL;
        };
    }
    
    public static HgGraphicsPipeline.Stencil.Op stencil(StencilOperation operation) {
        return switch (operation) {
            case KEEP -> HgGraphicsPipeline.Stencil.Op.KEEP;
            case ZERO -> HgGraphicsPipeline.Stencil.Op.ZERO;
            case REPLACE -> HgGraphicsPipeline.Stencil.Op.REPLACE;
            case INCR -> HgGraphicsPipeline.Stencil.Op.INCREMENT_AND_CLAMP;
            case DECR -> HgGraphicsPipeline.Stencil.Op.DECREMENT_AND_CLAMP;
            case INVERT -> HgGraphicsPipeline.Stencil.Op.INVERT;
            case INCR_WRAP -> HgGraphicsPipeline.Stencil.Op.INCREMENT_AND_WRAP;
            case DECR_WRAP -> HgGraphicsPipeline.Stencil.Op.DECREMENT_AND_WRAP;
        };
    }
    
    public static HgFormat format(TextureFormat textureFormat) {
        return switch (textureFormat) {
            case RGBA8 -> HgFormat.RGBA8_UNORM;
            case RED8 -> HgFormat.R8_UNORM;
            case RED8I -> HgFormat.R8_SINT;
            case DEPTH32 -> HgFormat.D32_SFLOAT;
            case DEPTH24_STENCIL8 -> throw new IllegalArgumentException("D24S8 is unsupported");
            case DEPTH32_STENCIL8 -> HgFormat.D32_SFLOAT_S8_UINT;
        };
    }
    
    public static long bufferUsageBits(int b3dUsage) {
        int bits = 0;
        if (b3dUsage > ((GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER << 1) - 1)) {
            throw new IllegalArgumentException("Unknown b3dUsage bits set");
        }
        // always need transfer_dst for uploads to device buffers
        bits |= VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        // oh, and b3d has a bug, so USAGE_COPY_DST also means USAGE_COPY_SRC...
        // so just always add it
        bits |= VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        if ((b3dUsage & GpuBuffer.USAGE_VERTEX) != 0) {
            bits |= VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        }
        if ((b3dUsage & GpuBuffer.USAGE_INDEX) != 0) {
            bits |= VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        }
        if ((b3dUsage & GpuBuffer.USAGE_UNIFORM) != 0) {
            bits |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        }
        if ((b3dUsage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
            bits |= VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
        }
        return bits;
    }
    
    public static int textureUsageBits(int b3dUsage, boolean color) {
        int bits = 0;
        // must always have TRANSFER_DST for uploads
        bits |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if ((b3dUsage & GpuTexture.USAGE_COPY_SRC) != 0) {
            bits |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        }
        if ((b3dUsage & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
            bits |= VK_IMAGE_USAGE_SAMPLED_BIT;
        }
        if ((b3dUsage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            bits |= color ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
        }
        return bits;
    }
    
    public static HgGraphicsPipeline.PrimitiveTopology topology(VertexFormat.Mode vertexFormatMode) {
        return switch (vertexFormatMode) {
            case VertexFormat.Mode.LINES -> HgGraphicsPipeline.PrimitiveTopology.TRIANGLE_LIST;
            case VertexFormat.Mode.LINE_STRIP -> HgGraphicsPipeline.PrimitiveTopology.TRIANGLE_STRIP;
            case VertexFormat.Mode.DEBUG_LINES -> HgGraphicsPipeline.PrimitiveTopology.LINE_LIST;
            case VertexFormat.Mode.DEBUG_LINE_STRIP -> HgGraphicsPipeline.PrimitiveTopology.LINE_STRIP;
            case VertexFormat.Mode.TRIANGLES -> HgGraphicsPipeline.PrimitiveTopology.TRIANGLE_LIST;
            case VertexFormat.Mode.TRIANGLE_STRIP -> HgGraphicsPipeline.PrimitiveTopology.TRIANGLE_STRIP;
            case VertexFormat.Mode.TRIANGLE_FAN -> HgGraphicsPipeline.PrimitiveTopology.TRIANGLE_FAN;
            case VertexFormat.Mode.QUADS -> HgGraphicsPipeline.PrimitiveTopology.TRIANGLE_LIST;
        };
    }
    
    public static int addressMode(AddressMode mode) {
        return switch (mode) {
            case REPEAT -> VK_SAMPLER_ADDRESS_MODE_REPEAT;
            case CLAMP_TO_EDGE -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        };
    }
}
