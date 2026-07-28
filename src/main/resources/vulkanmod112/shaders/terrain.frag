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
    vec4 frameInfo;  // x = seconds, y = directional light strength (0 = off)
    vec4 heightFog;  // x = strength (0 = off), y = thickening per block
} frame;

layout(push_constant) uniform Draw {
    vec4 params; // x = alpha cutoff
} draw;

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUV;
layout(location = 2) in vec2 vLight;
layout(location = 3) in float vDistance;
layout(location = 4) in vec3 vRelative;

layout(location = 0) out vec4 outColor;

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
float directionalTerm(vec3 normal, vec3 toSource, float distance) {
    float lambert = dot(normal, toSource / max(distance, 0.0001));
    float wrap = SOURCE_RADIUS / max(distance, SOURCE_RADIUS);
    float shaped = clamp((lambert + wrap) / (1.0 + wrap), 0.0, 1.0);
    return mix(BACK_FACE_LIGHT, 1.0, shaped);
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
    // Both of these come from the frame's uniform buffer, so every fragment in
    // the draw takes the same branch — which is what makes it safe to ask for
    // derivatives inside it.
    float directional = frame.frameInfo.y;
    vec3 normal = (lightCount > 0 && directional > 0.0) ? faceNormal() : vec3(0.0, 1.0, 0.0);
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
            level *= mix(1.0, directionalTerm(normal, toSource, distance), directional);
        }
        if (level > 0.0) {
            // The light map is sampled at (level * 16 + 8) / 256, which is the
            // texel centre of that row.
            blockLight = max(blockLight, (level * 16.0 + 8.0) / 256.0);
        }
    }
    vec3 light = texture(lightmap, vec2(blockLight, vLight.y)).rgb;
    vec3 shaded = tex.rgb * vColor.rgb * light;

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
