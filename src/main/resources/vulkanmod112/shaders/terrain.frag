#version 450

layout(set = 0, binding = 0) uniform sampler2D atlas;
layout(set = 0, binding = 1) uniform sampler2D lightmap;

layout(push_constant) uniform PC {
    mat4 mvp;
    vec4 offsetAndCutoff;
    vec4 fogColor;  // rgb = colour, a = mode: 0 off, 1 linear, 2 exp, 3 exp2
    vec4 fogParams; // x = start, y = end, z = density
} pc;

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUV;
layout(location = 2) in vec2 vLight;
layout(location = 3) in float vDistance;

layout(location = 0) out vec4 outColor;

// Specialised per pipeline. SOLID has a cutoff of 0.0, so its discard could
// never fire — but the mere presence of discard in the module makes the
// hardware turn off early depth testing for the whole pass, and SOLID is both
// the bulk of the terrain and the layer with the most overdraw. Compiled out
// for that pipeline, the depth test runs before shading again.
layout(constant_id = 0) const bool ALPHA_TEST = true;

// Mirrors the fixed-function fog the game sets up for everything OpenGL still
// draws. Without it the terrain is the one thing in the scene with no fog:
// underwater, entities turn the colour of the water while the blocks behind
// them stay perfectly clear.
float fogFactor(int mode) {
    if (mode == 1) {
        return (pc.fogParams.y - vDistance) / (pc.fogParams.y - pc.fogParams.x);
    }
    if (mode == 2) {
        return exp(-pc.fogParams.z * vDistance);
    }
    // exp2
    float scaled = pc.fogParams.z * vDistance;
    return exp(-scaled * scaled);
}

void main() {
    vec4 tex = texture(atlas, vUV);
    if (ALPHA_TEST) {
        if (tex.a < pc.offsetAndCutoff.w) {
            discard;
        }
    }
    vec3 light = texture(lightmap, vLight).rgb;
    vec3 shaded = tex.rgb * vColor.rgb * light;

    int mode = int(pc.fogColor.a);
    if (mode != 0) {
        shaded = mix(pc.fogColor.rgb, shaded, clamp(fogFactor(mode), 0.0, 1.0));
    }
    outColor = vec4(shaded, 1.0);
}
