#version 150

in vec3 Position;

out vec2 texCoord;

void main() {
    vec2 screenPos = Position.xy * 2.0 - 1.0;
    // without flipping the y here, the lightmap is inverted, _idk why exactly_
    // its probably because of the inverted viewport
    gl_Position = vec4(screenPos.x, 1.0 - screenPos.y, 1.0, 1.0);
    texCoord = Position.xy;
}
