#version 450

// Everything that is the same for a whole frame. Kept in a buffer rather than
// in push constants, which the matrix and the fog between them had filled to
// 96 of the 128 bytes Vulkan guarantees — with nothing left for anything else
// to be given to the shaders at all.
layout(set = 0, binding = 3, std140) uniform Frame {
    mat4 mvp;
    vec4 fogColor;   // rgb = colour, a = mode: 0 off, 1 linear, 2 exp, 3 exp2
    vec4 fogParams;  // x = start, y = end, z = density
    vec4 lightInfo;  // x = how many of lights[] are in use
    vec4 lights[32]; // xyz = position relative to the camera, w = light level
    // x = seconds since the renderer came up, for anything that animates.
    // y = 1 when dynamic light should respect which way a surface faces.
    vec4 frameInfo;
    // x = how much of the colour the low ground gives up, 0 turns it off.
    // y = how quickly it thickens with each block below the camera.
    vec4 heightFog;
} frame;

// What actually differs between draws.
layout(push_constant) uniform Draw {
    vec4 params; // x = alpha cutoff
} draw;

layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUV;
layout(location = 3) in vec2 inLight;

layout(set = 0, binding = 2, std430) readonly buffer ChunkOffsets { vec4 origins[]; } chunkOffsets;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vUV;
layout(location = 2) out vec2 vLight;
layout(location = 3) out float vDistance;
layout(location = 4) out vec3 vRelative;

void main() {
    // Every indirect command has exactly one instance; firstInstance is the
    // index of this chunk's camera-relative origin in the storage buffer.
    // That origin is already relative to the camera, so the sum below is the
    // position in eye space and its length is the distance fog needs.
    vec3 relative = inPos + chunkOffsets.origins[gl_InstanceIndex].xyz;
    gl_Position = frame.mvp * vec4(relative, 1.0);
    vColor = inColor;
    vUV = inUV;
    vLight = (inLight + 8.0) / 256.0;
    vDistance = length(relative);
    // Camera-relative world position, which is what anything positional in the
    // fragment stage needs and what the fog distance is already derived from.
    vRelative = relative;
}
