#version 450

layout(set = 0, binding = 0) uniform sampler2D atlas;
layout(set = 0, binding = 1) uniform sampler2D lightmap;

// Same block as the vertex stage; see terrain.vert for why it is a buffer.
layout(set = 0, binding = 3, std140) uniform Frame {
    mat4 mvp;
    vec4 fogColor;   // rgb = colour, a = mode: 0 off, 1 linear, 2 exp, 3 exp2
    vec4 fogParams;  // x = start, y = end, z = density
    vec4 lightInfo;  // x = how many of lights[] are in use
    vec4 lights[32]; // xyz = position relative to the camera, w = light level
    // x = seconds, y = directional light strength (0 = off),
    // z = 1 when vMaterial is real, w = 1 to paint the world by material
    vec4 frameInfo;
    // x = strength (0 = off), y = thickening per block,
    // z = how many entries of materialSprites are in use
    vec4 heightFog;
    // Pairs: a rectangle of the block atlas, then the material it stands for
    // in .x. See spriteMaterial for why the translucent layer needs these.
    vec4 materialSprites[16];
} frame;

layout(push_constant) uniform Draw {
    vec4 params; // x = alpha cutoff
} draw;

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUV;
layout(location = 2) in vec2 vLight;
layout(location = 3) in float vDistance;
layout(location = 4) in vec3 vRelative;
layout(location = 5) flat in uint vMaterial;

layout(location = 0) out vec4 outColor;

const uint MATERIAL_PLAIN = 0u;
const uint MATERIAL_WATER = 1u;
const uint MATERIAL_FOLIAGE = 2u;
const uint MATERIAL_GLASS = 3u;
const uint MATERIAL_LAVA = 4u;
const uint MATERIAL_ICE = 5u;

/**
 * The material of a surface read off the atlas rather than off the vertex.
 *
 * The translucent layer is the one place a per-vertex label cannot survive:
 * the game sorts its quads by distance after building the chunk, and sorts
 * them again every time the camera moves far enough, so that water draws back
 * to front. Labels numbered by vertex describe the wrong surface the moment
 * the quads move — marked only where a chunk's translucent layer was a single
 * material, water went grey beside one block of ice.
 *
 * What the sort cannot separate is a quad from its own texture coordinates.
 * So here the answer comes from where the fragment lands in the block atlas.
 * A handful of rectangles, walked once, and only in the translucent pipeline:
 * every other layer already has the real thing.
 */
uint spriteMaterial(vec2 uv) {
    int count = int(frame.heightFog.z);
    for (int i = 0; i < count; ++i) {
        vec4 rect = frame.materialSprites[i * 2];
        if (uv.x >= rect.x && uv.x <= rect.z && uv.y >= rect.y && uv.y <= rect.w) {
            return uint(frame.materialSprites[i * 2 + 1].x);
        }
    }
    return MATERIAL_PLAIN;
}

/**
 * Diagnostic colours for the material of a surface.
 *
 * Nothing in the finished renderer uses this. It exists because the material
 * of a vertex is worked out on the game side, carried through a buffer of its
 * own and read back here, and every step of that is invisible when it works
 * and equally invisible when it is off by one chunk — this makes the answer
 * something you can look at.
 */
vec3 materialColor(uint material) {
    if (material == MATERIAL_WATER) {
        return vec3(0.2, 0.4, 1.0);
    }
    if (material == MATERIAL_FOLIAGE) {
        return vec3(0.2, 1.0, 0.2);
    }
    if (material == MATERIAL_GLASS) {
        return vec3(1.0, 1.0, 0.3);
    }
    if (material == MATERIAL_LAVA) {
        return vec3(1.0, 0.3, 0.1);
    }
    if (material == MATERIAL_ICE) {
        return vec3(0.6, 0.9, 1.0);
    }
    return vec3(0.5);
}

// Specialised per pipeline. SOLID has a cutoff of 0.0, so its discard could
// never fire — but the mere presence of discard in the module makes the
// hardware turn off early depth testing for the whole pass, and SOLID is both
// the bulk of the terrain and the layer with the most overdraw. Compiled out
// for that pipeline, the depth test runs before shading again.
layout(constant_id = 0) const bool ALPHA_TEST = true;

// Set for the translucent pipeline. Opaque layers write an alpha of 1 because
// their result replaces what is under it; water and glass have to carry how
// much of what is behind them shows through, and that lives in the texture's
// alpha times the vertex colour's.
//
// The value is written premultiplied — colour already scaled by alpha. Two
// blends happen to this fragment, one into the translucent target here and one
// compositing that target over the game's frame, and "over" only survives being
// split in two that way when the colour carries its coverage with it.
// Straight alpha would darken every overlap.
layout(constant_id = 1) const bool BLEND = false;

// Mirrors the fixed-function fog the game sets up for everything OpenGL still
// draws. Without it the terrain is the one thing in the scene with no fog:
// underwater, entities turn the colour of the water while the blocks behind
// them stay perfectly clear.
float fogFactor(int mode) {
    if (mode == 1) {
        return (frame.fogParams.y - vDistance) / (frame.fogParams.y - frame.fogParams.x);
    }
    if (mode == 2) {
        return exp(-frame.fogParams.z * vDistance);
    }
    // exp2
    float scaled = frame.fogParams.z * vDistance;
    return exp(-scaled * scaled);
}

/**
 * The surface normal, worked out from how the world position changes across
 * the screen rather than read from the vertex.
 *
 * There is no normal to read: the game's block vertex is 28 bytes of position,
 * colour, texture and light map, and adding one would mean building the chunk
 * ourselves instead of mirroring the buffer the game already built. Every quad
 * in a block model is flat, so the cross product of the two screen-space
 * derivatives is the exact face normal here, not an approximation of it.
 *
 * vRelative is the surface with the eye at the origin, so the direction back to
 * the camera is its negation. Which way the cross product points depends on the
 * winding as it lands on screen, so the result is turned to face the camera
 * rather than trusted.
 */
vec3 faceNormal() {
    vec3 n = normalize(cross(dFdx(vRelative), dFdy(vRelative)));
    return dot(n, vRelative) > 0.0 ? -n : n;
}

// Roughly half the width of a torch flame, in blocks. What it controls is how
// far light bends past the horizon of a surface: see directionalTerm.
const float SOURCE_RADIUS = 0.6;

// What a face turned right away from a source keeps of its light. Vanilla's
// own face shading never reaches zero either — the underside of a block is
// drawn at 0.5 of the top, not black — so a face out of the light here dims
// rather than dropping out of the scene, which is a thing that happens to
// nothing else in this game.
const float BACK_FACE_LIGHT = 0.35;

// The same for foliage, which keeps far more: a leaf is thin enough to be lit
// from behind, and a torch on the far side of a bush lights the whole bush.
const float BACK_FACE_LIGHT_FOLIAGE = 0.6;

// How far a blade of grass is treated as facing up rather than facing the way
// its quad happens to face. See foliageNormal.
const float FOLIAGE_UPRIGHT = 0.65;

/**
 * How much of a source's light a face turned this way receives.
 *
 * Not max(dot(n, l), 0). That is the right answer for a point light and the
 * wrong one here, and one test showed both halves of why: a torch dropped
 * beside a one-block wall left the top of the wall completely black, and
 * jumping with a torch in hand lit that same face up at once. Both are the
 * same edge — the source crossing the plane of the face — and a clipped dot
 * product has nothing to say on either side of it.
 *
 * A flame is not a point. It has width, and a face level with a flame a step
 * away still sees half of it; that is what softens the edge of a shadow on a
 * real surface. So the source is a sphere here, and how far its light wraps
 * past the geometric horizon is its radius over the distance to it: a lot when
 * you are standing next to it, almost nothing across the room — which is also
 * where a hard edge is what the eye expects.
 */
float directionalTerm(vec3 normal, vec3 toSource, float distance, float backFace) {
    float lambert = dot(normal, toSource / max(distance, 0.0001));
    float wrap = SOURCE_RADIUS / max(distance, SOURCE_RADIUS);
    float shaped = clamp((lambert + wrap) / (1.0 + wrap), 0.0, 1.0);
    return mix(backFace, 1.0, shaped);
}

/**
 * The normal to light a blade of grass or a leaf by.
 *
 * Grass, flowers and saplings are drawn as two flat quads crossing each other,
 * both standing straight up. Lighting that geometry the way it is written down
 * gives an answer that is exactly wrong in the case you notice: raise a torch
 * over a patch of grass and nothing happens, because the light arriving from
 * above is arriving edge-on to a vertical surface, while the ground beside it
 * brightens as it should. Reported as grass not reacting to a jump when the
 * ground under it did.
 *
 * Vanilla sidesteps this by not shading those quads at all — cross models are
 * drawn unshaded, so that plants are not black. That is the same admission in
 * a different form: the plane is not what the plant is.
 *
 * A tuft of grass is a small volume of scattering material, and what light
 * does to it depends far more on where the light is than on which way any one
 * blade happens to be turned. So the normal is bent most of the way towards
 * standing up: a torch above brightens it, a torch below leaves it dim, a
 * torch beside it lights it, and none of that depends on which of the two
 * crossed quads you are looking at.
 */
vec3 foliageNormal(vec3 geometric) {
    vec3 bent = mix(geometric, vec3(0.0, 1.0, 0.0), FOLIAGE_UPRIGHT);
    // Bending past a quad facing straight down could cancel to nothing at some
    // other value of the constant; normalising that is a NaN across the whole
    // surface rather than a wrong shade on one of them.
    return length(bent) < 0.001 ? vec3(0.0, 1.0, 0.0) : normalize(bent);
}

/**
 * Thickens the fog towards the ground below the camera.
 *
 * Measured from the camera rather than from sea level, because the shader is
 * given camera-relative positions and nothing else; the difference shows only
 * when the camera itself is inside the fog, and the effect is a mood rather
 * than a simulation. Returns how much of the fog colour to mix in, on top of
 * whatever distance fog already decided.
 */
float heightFogAmount() {
    float below = max(0.0, -vRelative.y);
    return frame.heightFog.x * (1.0 - exp(-below * frame.heightFog.y));
}

void main() {
    vec4 tex = texture(atlas, vUV);
    if (ALPHA_TEST) {
        if (tex.a < draw.params.x) {
            discard;
        }
    }
    // Dynamic light is added by raising the block-light coordinate, not by
    // mixing a colour in. The game's light map is a 16x16 table whose rows and
    // columns are block and sky light, already carrying the warm cast of torch
    // light and whatever a mod has done to it, and it changes with the time of
    // day. Sampling it one step brighter is what makes a carried torch look
    // like a torch; adding white would look like a flashlight.
    float blockLight = vLight.x;
    int lightCount = int(frame.lightInfo.x);
    // The translucent pipeline is the only one that asks the atlas, and it only
    // asks where the vertex had nothing to say — which for that layer is
    // everywhere, because its labels are dropped rather than sent wrong.
    uint material = vMaterial;
    if (BLEND && material == MATERIAL_PLAIN) {
        material = spriteMaterial(vUV);
    }
    bool foliage = material == MATERIAL_FOLIAGE;
    // Both of these come from the frame's uniform buffer, so every fragment in
    // the draw takes the same branch — which is what makes it safe to ask for
    // derivatives inside it.
    float directional = frame.frameInfo.y;
    vec3 normal = (lightCount > 0 && directional > 0.0) ? faceNormal() : vec3(0.0, 1.0, 0.0);
    // After the derivatives and outside their branch: this is arithmetic on the
    // answer, not another question about the neighbourhood.
    if (foliage) {
        normal = foliageNormal(normal);
    }
    float backFace = foliage ? BACK_FACE_LIGHT_FOLIAGE : BACK_FACE_LIGHT;
    for (int i = 0; i < lightCount; ++i) {
        vec4 source = frame.lights[i];
        vec3 toSource = source.xyz - vRelative;
        float distance = length(toSource);
        // Vanilla propagates block light one level per block, so a source of
        // level L reaches L blocks. The same falloff, in a straight line.
        float level = source.w - distance;
        if (directional > 0.0 && level > 0.0) {
            // A face turned away from a torch should not be lit by it as
            // brightly as one facing it. Vanilla cannot express this at all —
            // its light is a per-block value with no idea which way a surface
            // points — so a dropped torch lights the underside of the floor it
            // sits on exactly as brightly as the top. The strength is how far
            // to go from vanilla's answer towards this one.
            level *= mix(1.0, directionalTerm(normal, toSource, distance, backFace), directional);
        }
        if (level > 0.0) {
            // The light map is sampled at (level * 16 + 8) / 256, which is the
            // texel centre of that row.
            blockLight = max(blockLight, (level * 16.0 + 8.0) / 256.0);
        }
    }
    vec3 light = texture(lightmap, vec2(blockLight, vLight.y)).rgb;
    vec3 shaded = tex.rgb * vColor.rgb * light;
    if (frame.frameInfo.w > 0.5) {
        shaded = materialColor(material) * light;
    }

    int mode = int(frame.fogColor.a);
    if (mode != 0) {
        shaded = mix(frame.fogColor.rgb, shaded, clamp(fogFactor(mode), 0.0, 1.0));
    }
    // After the distance fog and only where the game already has fog of its
    // own: with fog switched off there is no colour to thicken towards, and
    // inventing one would make this the only surface in the scene fading into
    // something the sky never does.
    if (mode != 0 && frame.heightFog.x > 0.0) {
        shaded = mix(shaded, frame.fogColor.rgb, clamp(heightFogAmount(), 0.0, 1.0));
    }
    if (BLEND) {
        float alpha = tex.a * vColor.a;
        outColor = vec4(shaded * alpha, alpha);
    } else {
        outColor = vec4(shaded, 1.0);
    }
}
