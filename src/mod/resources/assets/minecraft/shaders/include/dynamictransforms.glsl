#ifdef CINNABAR_VK

#if !defined(CINNABAR_VERTEX_SHADER) && !defined(CINNABAR_FRAGMENT_SHADER)
#error CINNABAR_VERTEX_SHADER or CINNABAR_FRAGMENT_SHADER must be defined
#endif

#ifdef CINNABAR_VERTEX_SHADER
out flat int arrayIndex;
#else
in flat int arrayIndex;
#endif

mat4 ModelViewMat;
vec4 ColorModulator;
vec3 ModelOffset;
mat4 TextureMat;
float LineWidth;

struct DynTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
// std140 unpadded 164 bytes in size, align 16, 176 byte array stride
#define REQUIRED_PADDING_VEC4S  ((((176 + (CINNABAR_UBO_ALIGNMENT - 1)) & ~(CINNABAR_UBO_ALIGNMENT - 1)) - 176) / 16)
#if REQUIRED_PADDING_VEC4S > 0
    vec4[REQUIRED_PADDING_VEC4S] padding;
#endif
};

layout(std140) buffer readonly DynamicTransforms {
    DynTransforms dynTransforms[];
};

void loadDynTransforms() {
    #ifdef CINNABAR_VERTEX_SHADER
    // even with multidraw, base_instance can be used to index into it
    // but this also works if im not doing multidraw
    arrayIndex = gl_BaseInstance;
    #endif
    DynTransforms transforms = dynTransforms[arrayIndex];
    ModelViewMat = transforms.ModelViewMat;
    ColorModulator = transforms.ColorModulator;
    ModelOffset = transforms.ModelOffset;
    TextureMat = transforms.TextureMat;
    LineWidth = transforms.LineWidth;
}

// overwrite the main func
void realMain();

void main() {
    loadDynTransforms();
    realMain();
}

#define main realMain

#else

// if not running on VK, fallback to the normal UBO definition
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};

#endif