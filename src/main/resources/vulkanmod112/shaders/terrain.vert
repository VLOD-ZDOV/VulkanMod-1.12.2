#version 450

layout(push_constant) uniform PC {
    mat4 mvp;
    vec4 offsetAndCutoff; // xyz = chunk origin - view pos, w = alpha cutoff
    vec4 fogColor;        // rgb = colour, a = mode: 0 off, 1 linear, 2 exp, 3 exp2
    vec4 fogParams;       // x = start, y = end, z = density
} pc;

layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUV;
layout(location = 3) in vec2 inLight;

layout(set = 0, binding = 2, std430) readonly buffer ChunkOffsets { vec4 origins[]; } chunkOffsets;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vUV;
layout(location = 2) out vec2 vLight;
layout(location = 3) out float vDistance;

void main() {
    // Every indirect command has exactly one instance; firstInstance is the
    // index of this chunk's camera-relative origin in the storage buffer.
    // That origin is already relative to the camera, so the sum below is the
    // position in eye space and its length is the distance fog needs.
    vec3 relative = inPos + chunkOffsets.origins[gl_InstanceIndex].xyz;
    gl_Position = pc.mvp * vec4(relative, 1.0);
    vColor = inColor;
    vUV = inUV;
    vLight = (inLight + 8.0) / 256.0;
    vDistance = length(relative);
}
