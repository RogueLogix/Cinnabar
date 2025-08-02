package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.enums.HgFormat;
import graphics.cinnabar.api.hg.enums.HgUniformType;

import static org.lwjgl.vulkan.VK12.*;

public class MercuryConst {
    
    public static int vkFormat(HgFormat format) {
        return switch (format) {
            case R8_SINT -> VK_FORMAT_R8_SINT;
            case RG8_SINT -> VK_FORMAT_R8G8_SINT;
            case RGB8_SINT -> VK_FORMAT_R8G8B8_SINT;
            case RGBA8_SINT -> VK_FORMAT_R8G8B8A8_SINT;
            case R8_UINT -> VK_FORMAT_R8_UINT;
            case RG8_UINT -> VK_FORMAT_R8G8_UINT;
            case RGB8_UINT -> VK_FORMAT_R8G8B8_UINT;
            case RGBA8_UINT -> VK_FORMAT_R8G8B8A8_UINT;
            case R8_SNORM -> VK_FORMAT_R8_SNORM;
            case RG8_SNORM -> VK_FORMAT_R8G8_SNORM;
            case RGB8_SNORM -> VK_FORMAT_R8G8B8_SNORM;
            case RGBA8_SNORM -> VK_FORMAT_R8G8B8A8_SNORM;
            case R8_UNORM -> VK_FORMAT_R8_UNORM;
            case RG8_UNORM -> VK_FORMAT_R8G8_UNORM;
            case RGB8_UNORM -> VK_FORMAT_R8G8B8_UNORM;
            case RGBA8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM;
            case R16_SINT -> VK_FORMAT_R16_SINT;
            case RG16_SINT -> VK_FORMAT_R16G16_SINT;
            case RGB16_SINT -> VK_FORMAT_R16G16B16_SINT;
            case RGBA16_SINT -> VK_FORMAT_R16G16B16A16_SINT;
            case R16_UINT -> VK_FORMAT_R16_UINT;
            case RG16_UINT -> VK_FORMAT_R16G16_UINT;
            case RGB16_UINT -> VK_FORMAT_R16G16B16_UINT;
            case RGBA16_UINT -> VK_FORMAT_R16G16B16A16_UINT;
            case R16_SNORM -> VK_FORMAT_R16_SNORM;
            case RG16_SNORM -> VK_FORMAT_R16G16_SNORM;
            case RGB16_SNORM -> VK_FORMAT_R16G16B16_SNORM;
            case RGBA16_SNORM -> VK_FORMAT_R16G16B16A16_SNORM;
            case R16_UNORM -> VK_FORMAT_R16_UNORM;
            case RG16_UNORM -> VK_FORMAT_R16G16_UNORM;
            case RGB16_UNORM -> VK_FORMAT_R16G16B16_UNORM;
            case RGBA16_UNORM -> VK_FORMAT_R16G16B16A16_UNORM;
            case R32_SINT -> VK_FORMAT_R32_SINT;
            case RG32_SINT -> VK_FORMAT_R32G32_SINT;
            case RGB32_SINT -> VK_FORMAT_R32G32B32_SINT;
            case RGBA32_SINT -> VK_FORMAT_R32G32B32A32_SINT;
            case R32_UINT -> VK_FORMAT_R32_UINT;
            case RG32_UINT -> VK_FORMAT_R32G32_UINT;
            case RGB32_UINT -> VK_FORMAT_R32G32B32_UINT;
            case RGBA32_UINT -> VK_FORMAT_R32G32B32A32_UINT;
            case R32_SFLOAT -> VK_FORMAT_R32_SFLOAT;
            case RG32_SFLOAT -> VK_FORMAT_R32G32_SFLOAT;
            case RGB32_SFLOAT -> VK_FORMAT_R32G32B32_SFLOAT;
            case RGBA32_SFLOAT -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case D32_SFLOAT -> VK_FORMAT_D32_SFLOAT;
            case D32_SFLOAT_S8_UINT -> VK_FORMAT_D32_SFLOAT_S8_UINT;
        };
    }
    
    public static int vkDescriptorType(HgUniformType type) {
        return switch (type) {
            case COMBINED_IMAGE_SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case UNIFORM_TEXEL_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
            case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        };
    }
}
