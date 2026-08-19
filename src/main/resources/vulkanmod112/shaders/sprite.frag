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
    // x = alpha cutoff
    // y = 1 on creature geometry, 0 on particles and weather
    // z = 1 to show the shading term on its own, flat grey
    vec4 params;
    // xyz = which way the sun is, in the same camera-relative axes the
    // positions arrive in. w = how much of the shading to believe, 0 = off.
    vec4 sun;
} draw;

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUV;
layout(location = 2) in vec2 vLight;
layout(location = 3) in float vDistance;
layout(location = 4) in vec3 vRelative;

layout(location = 0) out vec4 outColor;

// The face this creature's own geometry is showing.
//
// Every quad the game builds out of ModelRenderer is a face of a box, so it is
// flat, so the derivative of the camera-relative position across it IS the face
// and not an approximation of it. That is the same reason the terrain shader
// takes block normals this way, and it is worth being explicit that this is not
// the other kind of reconstruction: the full-frame passes rebuild a normal from
// the depth buffer and have to guess where one surface ends and the next
// begins, which halos around anything thin. Here there is nothing to guess,
// because the surface is being drawn at the moment the question is asked.
vec3 faceOf(vec3 rel) {
    vec3 n = normalize(cross(dFdx(rel), dFdy(rel)));
    // Turned to face the eye. Which way round a captured model part is wound
    // is whatever the game happened to build, and half of them come out
    // backwards — a creature lit from inside reads as a hole in it.
    return dot(n, rel) > 0.0 ? -n : n;
}

// How much of its sky light a face keeps, by which way it is turned.
//
// Wrapped rather than clamped, so a back turned fully on the sun keeps half its
// sky light instead of none. Taking it to zero is the physically true answer
// and the wrong one for everything this game looks like: a cow's far side would
// go black in open daylight, which vanilla never does, and which reads as a
// fault rather than as shading. The terrain shader refuses the same temptation
// in the same place and for the same reason.
float sunFacing(vec3 rel) {
    return dot(faceOf(rel), draw.sun.xyz) * 0.5 + 0.5;
}

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
    // Worked out before the alpha test below, because a derivative taken after
    // part of a quad has been discarded is taken across fragments that are no
    // longer there. The branch itself is a push constant — uniform across the
    // whole draw — so the derivative inside it is well defined, and particles
    // never enter it at all.
    float facing = 1.0;
    if (draw.params.y > 0.5 && draw.sun.w > 0.0) {
        facing = mix(1.0, sunFacing(vRelative), draw.sun.w);
    }
    vec4 texel = texture(sprite, vUV) * vColor;
    // Vanilla's alphaFunc, kept per batch: particles cut at one 255th, weather
    // at a tenth. Without it a particle sheet's empty space is drawn as
    // transparent black over the world, which on a premultiplied target is
    // nothing at all — but it still costs the blend and, on the depth-writing
    // batches, would have claimed the depth.
    if (texel.a <= draw.params.x) {
        discard;
    }
    // After the alpha test, so the silhouette shown is the real one.
    if (draw.params.z > 0.5 && draw.params.y > 0.5) {
        outColor = vec4(vec3(facing) * texel.a, texel.a);
        return;
    }
    // Only the sky half of the lightmap moves, and that is the whole reason a
    // creature can be shaded at all without the effect being wrong indoors.
    //
    // Vanilla's lightmap is looked up by two coordinates: how much block light
    // reaches this vertex, and how much sky light. The sun is allowed to touch
    // the sky half and nothing else — so a pig standing in a cave by a torch
    // has a sky coordinate of zero, and zero multiplied by any shading at all
    // is still zero. It comes out exactly as vanilla drew it, by construction
    // rather than by tuning.
    //
    // A directional term applied to the finished colour instead — which is all
    // a pass over the whole frame can reach, because by then the two halves
    // have been mixed into one number — cannot tell those apart. It would dim
    // one side of that torchlit pig according to where the sun is, in a cave,
    // at night. That is why this lives here, in the pass that still knows.
    vec2 light = vec2(vLight.x, vLight.y * facing);
    vec3 shaded = texel.rgb * texture(lightmap, light).rgb;
    int mode = int(frame.fogColor.a + 0.5);
    if (mode != 0) {
        shaded = mix(frame.fogColor.rgb, shaded, clamp(fogFactor(mode), 0.0, 1.0));
    }
    // Premultiplied, like the translucent terrain drawn in the same pass and
    // for the same reason: this target is blended over the game's frame a
    // second time, and only colour carrying its own coverage survives that.
    outColor = vec4(shaded * texel.a, texel.a);
}
