#version 450

// Everything the game draws as camera-facing textured quads: particles, rain
// and snow. One pipeline for all of it, because they are the same shape of
// work — a list of quads in camera-relative coordinates, one texture at a
// time, blended over the world.
//
// Only the first three members of the frame block are declared. The buffer is
// the terrain's and it is much longer than this; a shader may read the front
// of a uniform block and ignore the rest, and repeating twenty fields nobody
// here looks at would be a second place for the layout to drift out of step.
layout(set = 0, binding = 3, std140) uniform Frame {
    mat4 mvp;
    vec4 fogColor;   // rgb = colour, a = mode: 0 off, 1 linear, 2 exp, 3 exp2
    vec4 fogParams;  // x = start, y = end, z = density
} frame;

// Vanilla PARTICLE_POSITION_TEX_COLOR_LMAP: pos 3f | uv 2f | colour 4ub |
// lightmap 2s = 28 bytes. The same width as the terrain vertex and a
// different order, which is why it needs its own attribute description and
// not its own upload path.
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec2 inUV;
layout(location = 2) in vec4 inColor;
layout(location = 3) in vec2 inLight;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vUV;
layout(location = 2) out vec2 vLight;
layout(location = 3) out float vDistance;

void main() {
    // Already relative to the camera when it arrives. The game builds particle
    // vertices around Particle.interpPos and weather around a BufferBuilder
    // translation of the same value, and the terrain's chunk origins are
    // reduced against that same position — so one matrix serves all of them.
    gl_Position = frame.mvp * vec4(inPos, 1.0);
    vColor = inColor;
    vUV = inUV;
    vLight = (inLight + 8.0) / 256.0;
    vDistance = length(inPos);
}
