#version 450

layout(set = 0, binding = 1) uniform sampler2D lightmap;
layout(set = 0, binding = 3, std140) uniform Frame {
    mat4 mvp;
    vec4 fogColor;   // rgb = colour, a = mode: 0 off, 1 linear, 2 exp, 3 exp2
    vec4 fogParams;  // x = start, y = end, z = density
} frame;

// The one texture this batch draws from, in a set of its own.
//
// Three different textures are wanted across a frame — the particle sheet, the
// block atlas for block-shaped particles, rain and snow — and which one it is
// changes several times within the pass. An array indexed by a push constant
// would be the obvious shape, but dynamic indexing of a sampler array is an
// optional device feature this renderer does not ask for, and a driver without
// it fails at pipeline creation rather than at the line that would need it.
// One prepared set per texture costs a descriptor each and works everywhere.
layout(set = 1, binding = 0) uniform sampler2D sprite;

layout(push_constant) uniform Draw {
    vec4 params; // x = alpha cutoff
} draw;

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUV;
layout(location = 2) in vec2 vLight;
layout(location = 3) in float vDistance;

layout(location = 0) out vec4 outColor;

// The same fog the terrain uses, and it has to be: a particle and the block
// behind it are a block apart, so a difference in how far each one has faded
// reads as the particle glowing.
float fogFactor(int mode) {
    if (mode == 1) {
        return (frame.fogParams.y - vDistance) / (frame.fogParams.y - frame.fogParams.x);
    }
    if (mode == 2) {
        return exp(-frame.fogParams.z * vDistance);
    }
    float scaled = frame.fogParams.z * vDistance;
    return exp(-scaled * scaled);
}

void main() {
    vec4 texel = texture(sprite, vUV) * vColor;
    // Vanilla's alphaFunc, kept per batch: particles cut at one 255th, weather
    // at a tenth. Without it a particle sheet's empty space is drawn as
    // transparent black over the world, which on a premultiplied target is
    // nothing at all — but it still costs the blend and, on the depth-writing
    // batches, would have claimed the depth.
    if (texel.a <= draw.params.x) {
        discard;
    }
    vec3 shaded = texel.rgb * texture(lightmap, vLight).rgb;
    int mode = int(frame.fogColor.a + 0.5);
    if (mode != 0) {
        shaded = mix(frame.fogColor.rgb, shaded, clamp(fogFactor(mode), 0.0, 1.0));
    }
    // Premultiplied, like the translucent terrain drawn in the same pass and
    // for the same reason: this target is blended over the game's frame a
    // second time, and only colour carrying its own coverage survives that.
    outColor = vec4(shaded * texel.a, texel.a);
}
