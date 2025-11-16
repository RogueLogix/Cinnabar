package graphics.cinnabar.api.hg.enums;

import static org.lwjgl.vulkan.VK10.*;

public enum HgFormat {
    
    R8_SINT,
    RG8_SINT,
    RGB8_SINT,
    RGBA8_SINT,
    R8_UINT,
    RG8_UINT,
    RGB8_UINT,
    RGBA8_UINT,
    R8_SNORM,
    RG8_SNORM,
    RGB8_SNORM,
    RGBA8_SNORM,
    R8_UNORM,
    RG8_UNORM,
    RGB8_UNORM,
    RGBA8_UNORM,
    
    R16_SINT,
    RG16_SINT,
    RGB16_SINT,
    RGBA16_SINT,
    R16_UINT,
    RG16_UINT,
    RGB16_UINT,
    RGBA16_UINT,
    R16_SNORM,
    RG16_SNORM,
    RGB16_SNORM,
    RGBA16_SNORM,
    R16_UNORM,
    RG16_UNORM,
    RGB16_UNORM,
    RGBA16_UNORM,
    R16_SFLOAT,
    RG16_SFLOAT,
    RGBA16_SFLOAT,
    
    R32_SINT,
    RG32_SINT,
    RGB32_SINT,
    RGBA32_SINT,
    R32_UINT,
    RG32_UINT,
    RGB32_UINT,
    RGBA32_UINT,
    R32_SFLOAT,
    RG32_SFLOAT,
    RGB32_SFLOAT,
    RGBA32_SFLOAT,
    
    B10G11R11_UFLOAT_PACK,
    
    D32_SFLOAT,
    D32_SFLOAT_S8_UINT,
    ;
    
    public int aspects() {
        if (this == D32_SFLOAT) {
            return VK_IMAGE_ASPECT_DEPTH_BIT;
        } else if (this == D32_SFLOAT_S8_UINT) {
            return VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
        }
        return VK_IMAGE_ASPECT_COLOR_BIT;
    }
}
