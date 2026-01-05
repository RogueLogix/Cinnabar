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
float ChunkVisibility;
ivec2 TextureSize;
ivec3 ChunkPosition;

struct CnkSection {
    mat4 ModelViewMat;
    float ChunkVisibility;
    ivec2 TextureSize;
    ivec3 ChunkPosition;
    int padding1;
// std140 92 bytes, align 16, 96 byte array stride
#define REQUIRED_PADDING_VEC4S  ((((96 + (CINNABAR_UBO_ALIGNMENT - 1)) & ~(CINNABAR_UBO_ALIGNMENT - 1)) - 96) / 16)
#if REQUIRED_PADDING_VEC4S > 0
    vec4[REQUIRED_PADDING_VEC4S] padding;
#endif
};

layout(std140) buffer readonly ChunkSection {
    CnkSection cnkSections[];
};

void loadCnkSection() {
    #ifdef CINNABAR_VERTEX_SHADER
    // even with multidraw, base_instance can be used to index into it
    // but this also works if im not doing multidraw
    arrayIndex = gl_BaseInstance;
    #endif
    CnkSection section = cnkSections[arrayIndex];
    ModelViewMat = section.ModelViewMat;
    ChunkVisibility = section.ChunkVisibility;
    TextureSize = section.TextureSize;
    ChunkPosition = section.ChunkPosition;
}

// overwrite the main func
void realMain();

void main() {
    loadCnkSection();
    realMain();
}

#define main realMain

#else

layout(std140) uniform ChunkSection {
    mat4 ModelViewMat;
    float ChunkVisibility;
    ivec2 TextureSize;
    ivec3 ChunkPosition;
};

#endif