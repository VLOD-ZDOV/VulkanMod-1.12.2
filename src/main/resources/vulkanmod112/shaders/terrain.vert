#version 450

layout(push_constant) uniform PC {
    mat4 mvp;
    vec4 offsetAndCutoff; // xyz = chunk origin - view pos, w = alpha cutoff
} pc;

layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUV;
layout(location = 3) in vec2 inLight;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vUV;
layout(location = 2) out vec2 vLight;

void main() {
    gl_Position = pc.mvp * vec4(inPos + pc.offsetAndCutoff.xyz, 1.0);
    vColor = inColor;
    vUV = inUV;
    vLight = (inLight + 8.0) / 256.0;
}
