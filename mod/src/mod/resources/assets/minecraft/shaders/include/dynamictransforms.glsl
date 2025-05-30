
#if !defined(VERTEX_SHADER) && !defined(FRAGMENT_SHADER)
#error VERTEX_SHADER or FRAGMENT_SHADER must be defined
#endif

#ifdef VERTEX_SHADER
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
};

layout(std140) buffer readonly DynamicTransforms {
    DynTransforms dynTransforms[];
};

void loadDynTransforms() {
    #ifdef VERTEX_SHADER
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