#version 450

// Present only in the ray-query build of this shader. A module that names an
// acceleration structure asks the driver for a capability it either has or
// refuses the whole pipeline for, so a card without ray query is never handed
// this variant — see the shader task in build.gradle, which produces both from
// this one file.
#ifdef RAY_QUERY
#extension GL_EXT_ray_query : require
layout(set = 0, binding = 7) uniform accelerationStructureEXT terrainStructure;
#endif

layout(set = 0, binding = 0) uniform sampler2D atlas;
layout(set = 0, binding = 1) uniform sampler2D lightmap;
/*
 * The world as it stood a moment ago, colour and depth, for the water to look
 * at. Only the translucent pass is given the scene here — every other pass has
 * the block atlas bound in these two places instead, because in those passes
 * this colour image is the thing being drawn into and reading what you are
 * writing is not allowed. The water pass is the one that runs after the opaque
 * world is finished and handed over, which is exactly what makes this possible
 * at all: the picture the reflection needs is already made.
 */
layout(set = 0, binding = 4) uniform sampler2D sceneColor;
layout(set = 0, binding = 5) uniform sampler2D sceneDepth;

// Same block as the vertex stage; see terrain.vert for why it is a buffer.
layout(set = 0, binding = 3, std140) uniform Frame {
    mat4 mvp;
    vec4 fogColor;   // rgb = colour, a = mode: 0 off, 1 linear, 2 exp, 3 exp2
    vec4 fogParams;  // x = start, y = end, z = density
    // x = how many of lights[] are in use, y = how strongly the sun glints
    // off water and ice. yzw were spare; see sunGlint.
    vec4 lightInfo;
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
    // x = wave strength (0 = off), yz = the camera's own world x and z reduced
    // modulo the wave lattice. See waveGradient for what that is for.
    vec4 water;
    // x = how much of a reflection is traced against the scene rather than
    // taken from the fog colour, 0 = off. y = 1 to paint the water with what
    // the ray found and nothing else. zw = the near and far planes, which turn
    // a stored depth back into a distance.
    vec4 screenMirror;
    // xyz = which way the sun is, in the same camera-relative axes everything
    // else here uses. w = how much of a shadow to believe, 0 = off.
    vec4 sun;
    // x = how far a shadow ray may travel, in blocks — the world only has
    // structures near the camera, so past this there is nothing to hit and the
    // shadow has to be faded out rather than stopped.
    // y = how much of its sky light a fully shadowed surface keeps.
    // z = how wide the sun is made, in radians of half-angle. 0 is a point
    //     source and a hard edge; larger spreads the ray and softens it.
    // w = how many of the moving lights may be traced per fragment. 0 leaves
    //     them shining through walls, which is what they always did.
    vec4 sunParams;
    // x = how wide a moving light is treated as being, in blocks. A torch is a
    //     flame rather than a point, and a point casts an edge with no width
    //     at all.
    // y = how much of vanilla's own block light to give up in favour of the
    //     light traced from the sources below. 0 leaves the game's lighting
    //     exactly as it was.
    // z = how far to turn the dither pattern this frame, 0..1. Zero holds it
    //     still, which is what anything without frame averaging wants; see
    //     ditherValue.
    // w = how much the surface of water bends what is seen through it. 0 off.
    vec4 lightShadow;
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
// A cross-shaped plant. Lit exactly as foliage is — it is the same kind of
// thing — and told apart only because it is the one material that may be
// moved by the wind. See terrain.vert.
const uint MATERIAL_PLANT = 6u;
const uint MATERIAL_PLANT_TALL_LOWER = 7u;
const uint MATERIAL_PLANT_TALL_UPPER = 8u;
const uint MATERIAL_LEAVES = 9u;
// The block's own light level lives in the upper four bits, and the material
// in the lower four. Independent of each other: lava is a material and a
// light, glowstone is a light and nothing in particular.
const uint MATERIAL_MASK = 0x0Fu;

/**
 * Everything lit as a volume rather than as a flat face.
 *
 * One place, because the list grew: leaves and the two halves of a tall plant
 * are new materials only so that the vertex stage can move them differently,
 * and every one of them wants exactly the lighting foliage always had. Asked
 * in two places and written out in both, the second would have been forgotten.
 */
bool isFoliage(uint material) {
    return material == MATERIAL_FOLIAGE || material == MATERIAL_PLANT
            || material == MATERIAL_PLANT_TALL_LOWER
            || material == MATERIAL_PLANT_TALL_UPPER
            || material == MATERIAL_LEAVES;
}

/**
 * How far a fully tilted wave may drag what is under it, in screen widths at
 * one block away.
 *
 * Set by eye against the one thing that gives refraction away when it is
 * overdone: straight edges under the water — a sand bank, the line of a
 * channel — start to look like they are made of jelly. Under this the bed
 * moves with the wave and stays recognisably itself.
 */
const float REFRACT_REACH = 1.6;

/** How tight the glint is: water ripples broadly, a sheet of ice sharply. */
const float WATER_GLINT_SHARPNESS = 48.0;
const float ICE_GLINT_SHARPNESS = 96.0;
/** Full strength at the setting's maximum. Above this the sun becomes a lamp. */
const float GLINT_MAX = 2.5;
/** Not white: sunlight is warm, and a neutral glint reads as a specular bug. */
const vec3 SUN_TINT = vec3(1.0, 0.96, 0.88);

/**
 * The sun itself on a surface, rather than the sky the surface reflects.
 *
 * Water in the open shows two different things at once. One is the horizon,
 * which the fresnel term mixes in and which the screen reflection sharpens —
 * that is the surface acting as a mirror. The other is a narrow bright glint
 * of the sun sliding along the ripples, and no reflection can ever produce it:
 * the sun is a light, not a surface that was drawn into the scene for a ray to
 * find. Reflecting the sky where the sun is gives its colour, not its shape.
 *
 * That glint is one of the plainest marks of a shader pack on water, and it is
 * nearly free here — the direction of the sun and the calmed normal of the
 * surface are both already computed for the reflection.
 *
 * Faded out as the sun meets the horizon, the same way its shadow is: a glint
 * from a sun that is not up reads as a light with no source.
 */
float sunGlint(vec3 n, float sharpness) {
    if (frame.sun.y <= 0.0) {
        return 0.0;
    }
    vec3 h = normalize(normalize(-vRelative) + frame.sun.xyz);
    return pow(max(dot(n, h), 0.0), sharpness) * min(frame.sun.y * 4.0, 1.0);
}

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
    if (isFoliage(material)) {
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
 * A different number for every pixel, and — when something is averaging frames
 * — a different one each frame as well.
 *
 * Interleaved gradient noise: the pattern it makes is fine and even rather
 * than clumped, which is what lets a single sample per pixel read as a soft
 * edge instead of as speckle.
 *
 * Whether it holds still is not a matter of taste. A pattern that moves while
 * nothing averages it is seen moving — the shadow edge crawls, which is worse
 * than the grain it was meant to hide. A pattern that holds still while frames
 * *are* being averaged is worse again in the opposite way: every frame draws
 * exactly the same grain, so averaging a hundred of them gives back the one
 * they all agree on and removes nothing at all. So the turn per frame arrives
 * as a number, and it is zero exactly when nothing is accumulating.
 *
 * The turn itself is the golden ratio's fractional part, which is the step that
 * spreads any number of successive samples most evenly over the circle instead
 * of letting them fall into a short repeating cycle.
 */
float ditherValue(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715)))
                 + frame.lightShadow.z);
}

// True only in the build that can trace, and a compile-time constant in both —
// so the branch it guards costs nothing in the ordinary shader and the
// derivatives inside it stay legal.
#ifdef RAY_QUERY
#define SUN_SHADOWS_WANTED (frame.sun.w > 0.0)
#else
#define SUN_SHADOWS_WANTED false
#endif

/**
 * How much of the sun this fragment is denied, 0 to 1.
 *
 * One ray, and it stops at the first thing it meets: a shadow only asks
 * whether anything is in the way, never what or how far, so the traversal can
 * give up the moment it finds an answer. That is the cheapest question ray
 * tracing hardware can be asked and the reason this is the first thing worth
 * tracing here.
 *
 * Three refusals before the ray, each for its own reason:
 *
 *  - the sun below the horizon has no shadow to cast, and at night the sky
 *    light is already low everywhere;
 *  - a surface turned away from the sun is not shaded here at all. It is
 *    unlit in a physical model, but this game does not shade by which way a
 *    face points, and darkening every back face would be a change to the whole
 *    look of the world rather than a shadow. What that would be is the
 *    directional light setting, which already exists;
 *  - past the reach there are no structures to hit, so every ray would come
 *    back lit and draw a visible circle around the player. The strength fades
 *    out over the last quarter instead.
 */
float sunShadow(vec3 normal) {
#ifdef RAY_QUERY
    if (frame.sun.w <= 0.0 || frame.sun.y <= 0.0) {
        return 0.0;
    }
    float facing = dot(normal, frame.sun.xyz);
    if (facing <= 0.0) {
        return 0.0;
    }
    // Faded in as the sun climbs, not switched on when it clears a threshold.
    //
    // A shadow that begins the instant the sun is above the horizon appears
    // over the whole world in one frame, and at that moment it is also at its
    // longest and sweeping fastest — so it does not fade in, it arrives and
    // then races. The sky it belongs to brightens over minutes, and this now
    // follows the same climb.
    float risen = smoothstep(0.02, 0.30, frame.sun.y);
    float reach = frame.sunParams.x;
    float fade = 1.0 - clamp((vDistance - reach * 0.75) / max(reach * 0.25, 1.0), 0.0, 1.0);
    fade *= risen;
    if (fade <= 0.0) {
        return 0.0;
    }
    // Lifted off the surface, and further the flatter the light strikes it.
    //
    // A fixed lift is enough for a face the sun hits square. Near sunrise the
    // light runs nearly along the ground, and there a fixed lift leaves the
    // ray skimming its own surface and finding it — which reads as a crawling
    // stipple over everything flat, exactly where the shadows are longest and
    // most visible.
    vec3 from = vRelative + normal * (0.02 + 0.14 * (1.0 - facing));
    // Which way to look, spread over how wide the sun is made to be.
    //
    // One ray gives one answer, so its edge is a staircase along the pixel
    // grid. Spreading that one ray over a disc instead — a different direction
    // for every pixel, from an ordered pattern rather than at random — turns
    // the staircase into a band the width of the spread. It is dithered rather
    // than smooth, but it costs nothing: still one ray. A second ray would
    // cost as much again as the whole effect does.
    vec3 direction = frame.sun.xyz;
    float spread = frame.sunParams.z;
    if (spread > 0.0) {
        vec3 tangent = normalize(cross(direction,
                abs(direction.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
        vec3 bitangent = cross(direction, tangent);
        float angle = ditherValue(gl_FragCoord.xy) * 6.2831853;
        float radius = sqrt(ditherValue(gl_FragCoord.xy + 5.588238)) * spread;
        direction = normalize(direction + (cos(angle) * tangent + sin(angle) * bitangent) * radius);
    }
    rayQueryEXT query;
    rayQueryInitializeEXT(query, terrainStructure,
            gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
            0xFFu, from, 0.01, direction, reach);
    rayQueryProceedEXT(query);
    bool blocked = rayQueryGetIntersectionTypeEXT(query, true)
            != gl_RayQueryCommittedIntersectionNoneEXT;
    return blocked ? frame.sun.w * fade : 0.0;
#else
    return 0.0;
#endif
}

/**
 * Whether something stands between this surface and a light that is moving.
 *
 * The lights added here are the ones vanilla has not baked into the world: a
 * torch being carried, a creature on fire, a glowing block that was dropped a
 * second ago. They are added as a straight line from the source with a falloff
 * — which is all they could ever be, because nothing in this game's lighting
 * knows what is in the way — and the result is a torch that lights the far
 * side of a wall and the room around a corner. It is the most obviously wrong
 * thing this renderer does with light, and no arrangement of the falloff can
 * fix it: the missing information is the geometry between the two points, and
 * until there were structures to trace, that information did not exist here.
 *
 * A surface turned away from the light returns unblocked rather than blocked.
 * It receives nothing from that light either way, and answering "blocked"
 * would be this function deciding something it was not asked.
 *
 * The ray stops short of the source, because a torch is a piece of geometry
 * standing in front of the light it emits, and a ray that reaches it finds it
 * and reports the torch as shadowing itself.
 */
float lightBlocked(vec3 normal, vec3 toSource, float distance) {
#ifdef RAY_QUERY
    // Guarded the way the directional term beside it already is: a source that
    // lands exactly on the fragment divides by nothing, and one NaN in a
    // direction poisons everything computed from it.
    vec3 direction = toSource / max(distance, 0.0001);
    float facing = dot(normal, direction);
    // Somewhere on the flame, not at its centre.
    //
    // A point source casts an edge with no width, and a torch is not a point:
    // the shadow it throws has a soft border that widens the further the
    // shadow falls from what cast it, which comes out of this on its own —
    // aim at a different spot on the flame for every pixel and a distant
    // shadow's border spreads while a contact shadow's stays tight.
    vec3 target = toSource;
    float radius = frame.lightShadow.x;
    if (radius > 0.0) {
        vec3 tangent = normalize(cross(direction,
                abs(direction.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
        vec3 bitangent = cross(direction, tangent);
        float angle = ditherValue(gl_FragCoord.xy + 11.13) * 6.2831853;
        float offset = sqrt(ditherValue(gl_FragCoord.xy + 17.71)) * radius;
        target += (cos(angle) * tangent + sin(angle) * bitangent) * offset;
    }
    float travel = length(target);
    direction = target / travel;
    // Off the surface along the light, and along the face as well where the
    // face is turned towards it.
    //
    // Both, because either alone fails somewhere. Along the normal is what
    // keeps a lit floor from finding itself, and it is nothing at all for a
    // surface turned away from the light — a blade of grass is two crossed
    // quads and one of them always is, and that one used to be skipped
    // entirely and lit straight through the block in front of it. Along the
    // light works for that one and is useless where the light runs flat along
    // the ground, which is what the second term is for.
    vec3 from = vRelative + direction * 0.05
            + normal * (facing > 0.0 ? (0.02 + 0.14 * (1.0 - facing)) : 0.0);
    rayQueryEXT query;
    rayQueryInitializeEXT(query, terrainStructure,
            gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
            // Stopping short of the source, and never inside half of the way:
            // a torch is a piece of geometry standing in front of the light it
            // emits, and a fixed margin that is right for a lamp across the
            // room is most of the distance to one held in the hand.
            0xFFu, from, 0.02, direction, max(travel - 0.5, travel * 0.5));
    rayQueryProceedEXT(query);
    return rayQueryGetIntersectionTypeEXT(query, true)
            != gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
#else
    return 0.0;
#endif
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
    // Snapped to the axis it is nearest, and this is the difference between a
    // measurement and an answer.
    //
    // How well two screen-space derivatives determine the plane they lie in
    // falls apart as the surface turns edge-on: the two vectors become nearly
    // parallel, and their cross product is then a small difference of large
    // numbers. The symptom was reported without a torch in hand at all —
    // jumping beside a wall, the top of it darkened briefly twice, once
    // rising and once falling. Nothing about the light had moved; the only
    // thing in the shading that depends on where the eye is, is this.
    //
    // Blocks are boxes. Every face of one is exactly along an axis, so the
    // measured direction does not have to be believed to any precision at
    // all — only enough to say which of six directions it is, and that
    // survives noise that would ruin the direction itself. It is also the
    // model vanilla uses: it shades a face by which way it points and nothing
    // else.
    //
    // Below the threshold the measurement is kept as it is. That is where the
    // things that genuinely are not axis-aligned live — the crossed quads of
    // grass sit at forty-five degrees, so their largest component is 0.71 and
    // no snapping can turn them into a face of a box.
    vec3 size = abs(n);
    float largest = max(size.x, max(size.y, size.z));
    if (largest > 0.75) {
        n = size.x == largest ? vec3(sign(n.x), 0.0, 0.0)
                : (size.y == largest ? vec3(0.0, sign(n.y), 0.0)
                : vec3(0.0, 0.0, sign(n.z)));
    }
    // Which of the two ways along that axis: the one facing the camera. Tested
    // after the snap on purpose — against an axis this is a single coordinate
    // of the surface's position, which is a clean number, where against the
    // measured normal it was a dot product of two noisy ones and could come
    // out either way exactly when the face was hardest to measure.
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
// Vanilla's own number, and now exactly it: the underside of a block is drawn
// at half the brightness of the top, and that is the whole range this game
// has ever used for which way a surface points. Going below it was mine, and
// it cost more than it bought — the light a player carries sweeps its own
// terminator across every nearby face whenever they move, and the wider the
// range, the more that reads as the world blinking rather than as a lamp
// being lifted. Nothing else in Minecraft does that, so the eye has no
// practice at reading it.
const float BACK_FACE_LIGHT = 0.5;

// The same for foliage, which keeps far more: a leaf is thin enough to be lit
// from behind, and a torch on the far side of a bush lights the whole bush.
const float BACK_FACE_LIGHT_FOLIAGE = 0.6;

// How far a blade of grass is treated as facing up rather than facing the way
// its quad happens to face. See foliageNormal.
const float FOLIAGE_UPRIGHT = 0.65;

// How quickly a face reaches full brightness once it faces the light at all.
//
// Below one, so the lit side saturates fast and what is left of the falloff
// sits at the terminator and behind it. A straight cosine was tried first and
// is wrong for this game: it makes the brightness of every lit surface track
// where the lamp is, so jumping with a torch beside a raised block lit its top
// face up and dropped it again, and the only way to stop noticing that was to
// turn the whole effect down to a sixth, which also threw away the part that
// was worth having.
//
// Vanilla shades a face by its direction alone and in three fixed steps — the
// top of a block at 1.0, the sides at 0.8, the underside at 0.5 — with no
// regard for where any light is. This curve lands on 1.0, 0.76 and 0.35 for
// the same three directions, which is that same character rather than a
// photograph's.
const float FACING_CURVE = 0.35;

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
    float shaped = pow(clamp((lambert + wrap) / (1.0 + wrap), 0.0, 1.0), FACING_CURVE);
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
 * How much of a water surface is reflection rather than what is under it.
 *
 * Looking straight down into water you see the bottom; looking along it you
 * see the sky, and the change between the two is steep and happens near the
 * end. That is Fresnel, and Schlick's approximation of it — one minus the
 * cosine, to the fifth — is the whole of it here.
 *
 * What it reflects is the game's own fog colour. That is not a shortcut
 * standing in for a reflection: at a grazing angle what a flat water surface
 * shows you *is* the horizon, and the horizon is exactly what the fog colour
 * is — vanilla's own, read from GL each frame, so it tracks sunrise, weather,
 * being underwater and whatever a mod has done to it. Reflecting anything
 * computed here instead would be the one surface in the scene disagreeing
 * with the sky above it.
 */

/*
 * Follows a reflected ray across the picture that is already drawn.
 *
 * The whole of this rests on one accident of the frame's order. Water is drawn
 * in a pass of its own, after the opaque world has been finished and handed
 * back — so by the time a water fragment is being shaded, the colour and depth
 * of everything behind it exist and can be read. Nothing has to be traced
 * against the world itself, which is what makes this cost a loop rather than an
 * acceleration structure.
 *
 * The ray is walked in the same space the fragment is in, camera-relative
 * world, and each step is put back on screen with the frame's own matrix. That
 * is not a shortcut for a screen-space walk, it is the accurate version of it:
 * equal steps on screen are wildly unequal steps in the world, and it is the
 * world the ray is travelling through. Steps grow as they go, because a metre
 * near the eye covers far more of the screen than a metre far from it.
 *
 * What comes back is a colour and how much to believe it. The honest part is
 * the believing: this can only ever reflect what is on the screen, so a ray
 * that leaves the frame, or turns back towards the eye where nothing can be
 * behind it, has no answer and says so rather than inventing one. The caller
 * falls back to the fog colour, which is the horizon, which is what flat water
 * shows at that angle anyway.
 */
// How far a reflected ray may travel, in blocks, and this is the whole of what
// makes the effect usable rather than a limit reluctantly imposed on it.
//
// Looking along water rather than down at it, the reflected ray leaves at a
// very shallow angle and travels enormous distances. Everything such rays reach
// lies in a band a few pixels tall at the horizon, and the water then stretches
// that band across the entire lake — which is what the first version did, and
// it came out as spokes radiating from a point rather than as a reflection. The
// picture simply does not contain what that geometry is asking for, and no
// amount of care in the marching adds it.
//
// Kept short, a ray finds what is near the water: the bank it runs along, a
// tree standing beside it, a wall at the edge. Those are at a sane angle, they
// occupy real area on screen, and they are what a reflection is actually made
// of. Beyond this the fog colour takes over, which is the horizon, which is
// what water at that distance shows anyway.
const float REFLECT_REACH = 34.0;
/**
 * The most of the Fresnel term that a flat sky colour is allowed to claim.
 *
 * Fresnel says that at a grazing angle water is very nearly a perfect mirror,
 * and that is true — but a perfect mirror of *nothing* is a sheet of pale
 * paint. Where the ray found no world to reflect, the fallback is the fog
 * colour, and believing the Fresnel term completely turned an entire lake into
 * the colour of the sky: the wash the whole surface had, worse the more the
 * waves tilted it, because tilting is what puts more of the surface at a
 * grazing angle.
 *
 * So the term is believed in proportion to whether there is anything behind
 * it. A ray that found the far bank reflects it fully; a ray that found
 * nothing gets this much and the water keeps being water.
 */
const float FLAT_SKY_LIMIT = 0.30;

// How far behind a surface a ray may be and still be counted as having hit it,
// in blocks. Without this the reflection finds things standing in front of the
// water rather than beside it: the ray leaves the surface going away from the
// eye, but its path across the *screen* passes behind whatever is nearer the
// camera — the bank you are standing on — and everything is deeper than that,
// so every ray "hits" it. What was reflected was the near shore, in a mess of
// alternating hit and miss along the boundary where neighbouring rays disagreed.
// A crossing is a crossing only if the ray is a little way behind the surface,
// not a long way past it.
const float REFLECT_THICKNESS = 1.4;

/** A stored depth back to a distance from the eye. */
float distanceOf(float depth) {
    float n = frame.screenMirror.z;
    float f = frame.screenMirror.w;
    return 2.0 * n * f / (f + n - (2.0 * depth - 1.0) * (f - n));
}

vec4 traceReflection(vec3 origin, vec3 dir) {
    // Away from the surface before the first step, or the surface finds itself.
    float t = 0.3;
    float step = 0.35;
    float lastMiss = t;
    for (int i = 0; i < 32; i++) {
        vec4 clip = frame.mvp * vec4(origin + dir * t, 1.0);
        if (clip.w <= 0.0001) {
            return vec4(0.0);
        }
        vec3 onScreen = vec3(clip.xy / clip.w * 0.5 + 0.5, clip.z / clip.w);
        if (onScreen.x < 0.0 || onScreen.x > 1.0 || onScreen.y < 0.0 || onScreen.y > 1.0) {
            return vec4(0.0);
        }
        if (t > REFLECT_REACH) {
            return vec4(0.0);
        }
        if (onScreen.z > textureLod(sceneDepth, onScreen.xy, 0.0).r) {
            // Between the last step that was still in front of everything and
            // this one, which is behind something. Halving four times puts the
            // crossing within a sixteenth of a step, which at these sizes is
            // closer than the surface it landed on is thick.
            float near = lastMiss;
            float far = t;
            for (int j = 0; j < 8; j++) {
                float mid = 0.5 * (near + far);
                vec4 c = frame.mvp * vec4(origin + dir * mid, 1.0);
                vec3 s = vec3(c.xy / c.w * 0.5 + 0.5, c.z / c.w);
                if (s.z > textureLod(sceneDepth, s.xy, 0.0).r) {
                    far = mid;
                    onScreen = s;
                } else {
                    near = mid;
                }
            }
            // Faded out towards the edges of the picture, because that is where
            // the picture stops knowing. A reflection that ended in a hard line
            // along the edge of the screen would announce how it was made.
            // Behind it, but by how much. A ray that is a long way past the
            // surface never touched it — it went by, somewhere out of sight.
            if (distanceOf(onScreen.z) - distanceOf(textureLod(sceneDepth, onScreen.xy, 0.0).r)
                    > REFLECT_THICKNESS) {
                return vec4(0.0);
            }
            vec2 edge = smoothstep(vec2(0.0), vec2(0.14), onScreen.xy)
                      * smoothstep(vec2(0.0), vec2(0.14), vec2(1.0) - onScreen.xy);
            // And believed less the further it had to go, all the way to
            // nothing at the end of its rope.
            float reach = t / REFLECT_REACH;
            float trust = clamp(1.0 - reach * reach, 0.0, 1.0);
            return vec4(textureLod(sceneColor, onScreen.xy, 0.0).rgb,
                        edge.x * edge.y * trust);
        }
        lastMiss = t;
        t += step;
        // Barely. Once the ray is not allowed to travel far, its steps do not
        // have to grow much either, and the whole march stays fine: the
        // longest step here is under a block and a half, where it used to be
        // ten. Steps still grow a little, a metre near the eye covering more
        // of the picture than a metre far from it.
        step *= 1.045;
    }
    return vec4(0.0);
}

float fresnel(vec3 normal) {
    float facing = clamp(dot(normal, normalize(-vRelative)), 0.0, 1.0);
    float f = 1.0 - facing;
    float f2 = f * f;
    return f2 * f2 * f;
}

// The wave lattice, in blocks. Every wave below repeats exactly over this
// distance on both horizontal axes, and that is not decoration: see waveXZ.
const float WAVE_LATTICE = 16.0;
const float WAVE_K = 6.2831853 / WAVE_LATTICE;
// The steepest slope the surface is tilted to at full strength, as a rise over
// a run. Water in this game is flat and stays flat — nothing is displaced, so
// this is what the surface is shaded as, not what it is.
const float WAVE_SLOPE = 0.3;
// How much of the sky a tilted facet gains or loses, at full strength. Set by
// eye: at 0.14 the first version was reported invisible from above, which is
// the one direction the Fresnel term has nothing to say in, so this is the
// whole of what a wave looks like when you are standing over it.
const float WAVE_SHADE = 0.32;

/**
 * A horizontal position that does not travel with the player.
 *
 * vRelative is the surface with the eye at the origin, so using it directly
 * would drag every wave along behind the camera. The world position it came
 * from is not available in full and could not be used if it were: Minecraft
 * coordinates reach tens of millions, where a 32-bit float can no longer
 * separate one block from the next.
 *
 * Neither is needed. What a wave wants is a phase, and a phase is periodic —
 * so the camera's world position is reduced modulo the lattice on the Java
 * side, in double precision where that is exact, and only the remainder is
 * sent. Adding it back gives the world position shifted by some whole number
 * of lattice steps, which every wave here is built to be blind to: each phase
 * is a whole multiple of 2*pi over the lattice, along a direction with whole
 * components. Continuous across chunk borders, identical from anywhere, and
 * two adds.
 */
vec2 waveXZ() {
    return vRelative.xz + frame.water.yz;
}

/**
 * The slope of the water surface at this point, as a rise over a run on each
 * horizontal axis, at most 1 in each.
 *
 * Four travelling sine waves crossing at unrelated angles. The derivative is
 * written out rather than sampled, because the sines are already being
 * evaluated and a cosine of the same argument is free next to them — and a
 * normal taken from screen-space derivatives of a height field would be a
 * measurement of the pixel grid rather than of the water.
 *
 * The directions are whole-numbered pairs on purpose (see waveXZ). What that
 * costs is that the pattern repeats every sixteen blocks; what it buys is that
 * it never swims when the player walks, which is the failure that would be
 * noticed.
 */
vec2 waveGradient(vec2 p, float t) {
    vec2 g = vec2(1.0, 0.0) * cos(WAVE_K * p.x + 0.9 * t);
    g += 0.60 * vec2(0.0, 2.0) * cos(WAVE_K * 2.0 * p.y + 1.5 * t);
    g += 0.45 * vec2(2.0, 1.0) * cos(WAVE_K * (2.0 * p.x + p.y) + 1.9 * t);
    g += 0.28 * vec2(1.0, -3.0) * cos(WAVE_K * (p.x - 3.0 * p.y) + 2.6 * t);
    // The sum of the four amplitudes times their own directions, so the result
    // reaches one only where every wave crests along the same axis at once.
    return g * (1.0 / 4.09);
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
    // The top bit is not a material, it is whether the block this vertex came
    // from gives off light of its own. Kept beside the material rather than as
    // one more value of it, because the two are independent: lava is a material
    // and a light, glowstone is a light and nothing in particular.
    float emits = float(vMaterial >> 4u) * (1.0 / 15.0);
    uint material = vMaterial & MATERIAL_MASK;
    if (BLEND && material == MATERIAL_PLAIN) {
        material = spriteMaterial(vUV);
    }
    bool foliage = isFoliage(material);
    // Both of these come from the frame's uniform buffer, so every fragment in
    // the draw takes the same branch — which is what makes it safe to ask for
    // derivatives inside it.
    float directional = frame.frameInfo.y;
    // BLEND is a compiled-in constant, so adding it here keeps the branch the
    // same for every fragment of the draw, which is what makes the derivatives
    // inside it legal. The translucent pipeline always needs the normal: water
    // is in it, and water is asked which way it faces even in the dark.
    vec3 normal = (BLEND || (lightCount > 0 && directional > 0.0) || SUN_SHADOWS_WANTED
            || (lightCount > 0 && frame.sunParams.w > 0.0))
            ? faceNormal() : vec3(0.0, 1.0, 0.0);
    // After the derivatives and outside their branch: this is arithmetic on the
    // answer, not another question about the neighbourhood.
    if (foliage) {
        normal = foliageNormal(normal);
    }
    // Waves, before anything asks which way the water faces. They are a change
    // to the normal and to nothing else, so every answer already built on the
    // normal moves with them: the reflection breaks up along the crests, and a
    // torch held over water is scattered across it instead of landing as one
    // smooth patch. Only the top: the sides of a water block are the walls of
    // the channel it runs in, and a wave has no business tilting those.
    float waveShade = 1.0;
    vec3 mirrorNormal = normal;
    if (frame.water.x > 0.0 && material == MATERIAL_WATER && normal.y > 0.9) {
        vec3 still = normal;
        vec2 g = waveGradient(waveXZ(), frame.frameInfo.x);
        vec2 slope = g * (WAVE_SLOPE * frame.water.x);
        normal = normalize(vec3(-slope.x, 1.0, -slope.y));
        // The reflection is measured against a calmer surface than the light is,
        // and the reason is that Fresnel near grazing is a cliff: the same eight
        // degrees of tilt that are barely visible from above swing the mirror
        // from a third to nearly all of it, and the water came out banded white
        // and blue from the shore rather than rippled. Two things are missing
        // from a single sample of the slope, and both say the same. A crest
        // near grazing hides its own trough, so less of the slope is on show
        // than there is; and a pixel of water out there covers a great many
        // waves, so what it should carry is the average of the curve over them,
        // which for a curve this steep is far flatter than the curve at the
        // average. So the wave is believed in full where the surface faces the
        // eye — where the reflection is weak anyway and nothing bands — and
        // fades to a quarter of itself as the view flattens, which leaves the
        // horizon the smooth mirror it was before the waves and turns the
        // banding into glitter.
        float facing = clamp(dot(still, normalize(-vRelative)), 0.0, 1.0);
        mirrorNormal = normalize(mix(still, normal, 0.25 + 0.75 * facing));
        // What the tilt does to the light the surface catches. The fresnel term
        // alone would leave the water flat wherever the reflection is weak —
        // looking down at it, which is most of the time — so a facet turned
        // towards the light also brightens. The direction is fixed rather than
        // taken from the sun, which is what vanilla does for its own faces: it
        // shades the four sides of a block differently and none of them follow
        // the sky.
        waveShade = 1.0 + WAVE_SHADE * frame.water.x * dot(g, vec2(-0.82, -0.57));
    }
    float backFace = foliage ? BACK_FACE_LIGHT_FOLIAGE : BACK_FACE_LIGHT;
    int maxTracedLights = int(frame.sunParams.w);
    int tracedLights = 0;
    float tracedBlock = 0.0;
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
        if (level > 0.0 && tracedLights < maxTracedLights) {
            // Counted rather than bounded by the loop: a fragment usually has
            // one light close enough to matter and sometimes none, so the limit
            // is a ceiling on the unlucky fragment and not a cost every one
            // pays.
            tracedLights += 1;
            // How much this surface is entitled to a shadow at all.
            //
            // A face turned away from the light receives nothing from it in a
            // physical model, and everything from it in this game's — vanilla's
            // block light is a number per block with no idea which way anything
            // points. Taking the light away outright is therefore right by
            // physics and wrong by every expectation the world sets: a wall one
            // block high went completely black on top while its sides were lit,
            // which is not a shadow, it is a surface that was never included.
            //
            // So a face-on surface believes the shadow fully, a face turned
            // away keeps its light, and between them it fades. The same term
            // takes the hard band off the top of a block near a carried torch,
            // where the light runs almost along the surface and the answer was
            // all or nothing across a pixel.
            float belief = clamp(0.35 + dot(normal, normalize(toSource)), 0.0, 1.0);
            level *= 1.0 - lightBlocked(normal, toSource, distance) * belief;
        }
        if (level > 0.0) {
            // The light map is sampled at (level * 16 + 8) / 256, which is the
            // texel centre of that row.
            tracedBlock = max(tracedBlock, (level * 16.0 + 8.0) / 256.0);
        }
    }
    // What the game says, what the rays say, and how much to prefer the rays.
    //
    // Vanilla's block light reaches around corners and is flat; the traced
    // light has a direction and a shadow and reaches only as far as the sources
    // this frame knows about. Giving up the first entirely is what makes a room
    // look lit rather than filled, and it is also what makes a cave lit from
    // beyond that range go dark — so which of the two wins is the player's to
    // choose and not ours.
    blockLight = max(blockLight * (1.0 - frame.lightShadow.y), tracedBlock);
    // The sun's shadow, and it moves the sky light rather than multiplying the
    // result.
    //
    // This game has no sun in its lighting: a surface is lit by a pair of
    // numbers, how much block light and how much sky light reach it, and the
    // light map turns that pair into a colour. Multiplying a shadow over that
    // colour would darken a torch-lit cave wall that the sky never reached,
    // and would look like a filter laid on the world rather than like shade.
    // Moving down the sky axis is what the game itself does when a cloud of
    // night comes over, so a shadow made this way is a shade this world
    // already knows how to draw.
    float skyLight = vLight.y;
    skyLight = mix(skyLight, skyLight * frame.sunParams.y, sunShadow(normal));
    vec3 light = texture(lightmap, vec2(blockLight, skyLight)).rgb;
    vec3 shaded = tex.rgb * vColor.rgb * light * waveShade;
    if (frame.frameInfo.w > 0.5) {
        shaded = materialColor(material) * light;
    }

    // Rounded, not truncated, and the sprite shader does the same. Both read
    // this one field of this one buffer, and a particle taking a different fog
    // mode from the terrain behind it would be a hard thing to see and a
    // harder one to explain. Today the value is written as a whole number and
    // either reading gives the same answer; agreeing costs nothing and stops
    // that from being load-bearing.
    int mode = int(frame.fogColor.a + 0.5);
    // Kept for the emissive mask below: how much of this surface survived the
    // fog. Light that the fog swallowed must not glow either — inside lava,
    // where the fog is thick enough to hide the world, the silhouettes of
    // distant blocks still had burning edges, because their colour had been
    // taken to the fog colour and their claim to be a light had not.
    float fogKeep = 1.0;
    if (mode != 0) {
        fogKeep = clamp(fogFactor(mode), 0.0, 1.0);
        shaded = mix(frame.fogColor.rgb, shaded, fogKeep);
    }
    // After the distance fog and only where the game already has fog of its
    // own: with fog switched off there is no colour to thicken towards, and
    // inventing one would make this the only surface in the scene fading into
    // something the sky never does.
    if (mode != 0 && frame.heightFog.x > 0.0) {
        float thickened = clamp(heightFogAmount(), 0.0, 1.0);
        shaded = mix(shaded, frame.fogColor.rgb, thickened);
        fogKeep *= 1.0 - thickened;
    }
    if (BLEND) {
        float alpha = tex.a * vColor.a;
        // What is under the water, moved by the surface it is seen through.
        //
        // Reflection and refraction are the two halves of the same thing and
        // only one of them was here. A still pond with a perfect mirror in it
        // and a riverbed that does not budge reads as glass laid over a
        // photograph — the giveaway is precisely that the bed stays put while
        // the reflection moves.
        //
        // The blend would have taken what is behind straight from the frame,
        // unmoved. So it is fetched here instead, from the same picture the
        // reflection searches, displaced by the tilt of the wave; and then the
        // pixel is handed over opaque, because it now carries both halves
        // itself.
        if (frame.lightShadow.w > 0.0 && material == MATERIAL_WATER && normal.y > 0.9) {
            vec4 clip = frame.mvp * vec4(vRelative, 1.0);
            if (clip.w > 0.0001) {
                vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
                float here = clip.z / clip.w;
                // Divided by how far away the surface is: the same tilt covers
                // fewer pixels the further off it is, and without this a lake
                // shears at the horizon while a puddle at your feet barely
                // moves.
                // The wave's own tilt, and nothing else. By this point `normal`
                // is already the wavy one and its horizontal part *is* the
                // tilt, because the face it stands on points straight up.
                //
                // This read `mirrorNormal - normal` first, and that was
                // backwards. `mirrorNormal` is the deliberately calmed normal
                // the reflection uses, and how far it is calmed depends on how
                // squarely you are facing the water — so the difference went to
                // zero looking straight down, which is exactly where ripples
                // are plainest, and grew towards grazing, where the bed is
                // barely visible at all. Refraction was strongest where it
                // could not be seen and absent where it could.
                vec2 tilt = normal.xz
                            * frame.lightShadow.w * REFRACT_REACH / max(1.0, clip.w);
                vec2 shifted = clamp(uv + tilt, vec2(0.0), vec2(1.0));
                // Only if what is there is really behind the water. A sample in
                // front of it is something standing between the eye and the
                // surface, and smearing that across the water is the artefact
                // every refraction gets wrong first: a reed on the bank waving
                // about inside the pond.
                if (textureLod(sceneDepth, shifted, 0.0).r < here) {
                    shifted = uv;
                }
                vec3 behind = textureLod(sceneColor, shifted, 0.0).rgb;
                // Exactly what the blend would have done, done here: the frame
                // times what the water lets through, plus the water itself.
                shaded = shaded * alpha + behind * (1.0 - alpha);
                alpha = 1.0;
            }
        }
        // frame.heightFog.w: how much of the Fresnel term to believe, 0 off.
        float water = frame.heightFog.w;
        if (water > 0.0 && material == MATERIAL_WATER) {
            float mirror = fresnel(mirrorNormal) * water;
            // What the surface shows: the horizon by default, and whatever is
            // actually standing there when the ray finds it.
            vec3 mirrored = frame.fogColor.rgb;
            // How much of what is being mixed in is really there, as against
            // being the sky colour standing in for it.
            float confidence = 0.0;
            // Only the top of the water reflects. The sides of a water block
            // are the walls of the channel it runs in, and a ray sent off one
            // of those travels along the surface rather than away from it —
            // which is where a good part of the smearing was coming from. The
            // waves have always known this; the mirror did not.
            if (frame.screenMirror.x > 0.0 && normal.y > 0.9) {
                vec3 toEye = normalize(-vRelative);
                vec3 ray = reflect(-toEye, mirrorNormal);
                // A ray heading back towards the eye is looking at the side of
                // the world that was never drawn. Faded rather than cut, so a
                // surface does not change its mind along a line.
                float outward = clamp(1.0 - dot(ray, toEye) * 2.5, 0.0, 1.0);
                // A ray that leaves almost along the surface is the one that
                // smears. It skims the top edge of whatever is on the bank and
                // finds the same few pixels over and over, drawn down the water
                // as a streak — the reflection is not wrong there so much as
                // there is one pixel of answer being asked to cover a hundred.
                // A ray that leaves steeply has room underneath it and comes
                // back with a picture. Believed in proportion to which it is.
                // Loosened from a quarter of a right angle to nearer a half.
                // The steepness test was set when the ray reached eighteen
                // blocks and nothing averaged frames: a shallow ray had one
                // pixel of answer to spread over a hundred, so it was faded
                // out — and with it went every reflection at any distance,
                // because distance *is* a shallow angle. The reach is now
                // thirty-four blocks and successive frames are averaged, so a
                // shallow ray has both more to find and less to lose by
                // finding it roughly.
                float rise = clamp(dot(ray, mirrorNormal) * 2.2, 0.0, 1.0);
                vec4 found = outward > 0.0 && rise > 0.0
                        ? traceReflection(vRelative, ray) : vec4(0.0);
                found.a *= rise;
                confidence = found.a * outward * frame.screenMirror.x;
                mirrored = mix(mirrored, found.rgb, confidence);
                // Shown on its own when asked. What the ray found, at full
                // strength, with no fresnel deciding how much of it to use and
                // no water colour under it — deep blue wherever it found
                // nothing at all. Three rounds have now been spent describing
                // this to each other in words, which is two more than a
                // picture costs.
                if (frame.screenMirror.y > 0.5) {
                    shaded = found.a > 0.0 ? found.rgb : vec3(0.02, 0.02, 0.22);
                    outColor = vec4(shaded, 1.0);
                    return;
                }
            }
            // Believed in proportion to there being something to reflect. See
            // FLAT_SKY_LIMIT: a perfect mirror of nothing is pale paint, and
            // that is what a whole lake turned into.
            mirror *= mix(FLAT_SKY_LIMIT, 1.0, confidence);
            // Both together, because they are the same fact: where the surface
            // turns into a mirror it stops showing what is under it, and a
            // reflection that let the riverbed through would be a colour laid
            // over water rather than water behaving like water.
            shaded = mix(shaded, mirrored, mirror);
            alpha = mix(alpha, 1.0, mirror);
        }
        // The sun on the surface. Water first, then ice — which the game side
        // has labelled all along while nothing here ever read the label: ice
        // was drawn as an ordinary translucent quad, and a frozen lake is the
        // one surface in this world that ought to flash when the sun is on it.
        float glintStrength = frame.lightInfo.y;
        if (glintStrength > 0.0) {
            float g = 0.0;
            if (material == MATERIAL_WATER) {
                g = sunGlint(mirrorNormal, WATER_GLINT_SHARPNESS);
            } else if (material == MATERIAL_ICE) {
                // The flat face, not a calmed ripple: a sheet of ice has no
                // ripple, and its highlight is broad and sudden rather than
                // scattered — which is what makes it read as ice and not water.
                g = sunGlint(normal, ICE_GLINT_SHARPNESS);
            }
            g *= glintStrength * GLINT_MAX;
            if (g > 0.0) {
                shaded += SUN_TINT * g;
                // Raised with it, because the frame is premultiplied below: a
                // glint added to a surface that lets most light through would
                // otherwise be scaled down by exactly the amount that makes it
                // worth having.
                alpha = mix(alpha, 1.0, clamp(g, 0.0, 1.0));
            }
        }
        outColor = vec4(shaded * alpha, alpha);
    } else {
        // The alpha of an opaque pixel was the constant 1.0 and nothing else,
        // and the composite only ever asks whether it is greater than zero —
        // whether there is terrain here at all. So it carries how much of a
        // light this surface is, in the upper half of its range, where that
        // question still answers the same way.
        //
        // Bloom is what reads it, and it has to be told rather than left to
        // guess. Guessing means thresholding on brightness, and snow and sand
        // in sunlight are as bright on screen as lava is without being lights.
        //
        // What it is told is the block's own light level, decided where the
        // chunk was built and carried in the material byte. Two earlier
        // versions guessed instead and both were wrong in the same way: being
        // a light is a fact about a block, and neither of the things a
        // fragment can see is. Judging by the texture's brightness lit the
        // pale texels of a glowstone block and left its dark ones to receive
        // the glow from their neighbours, so a light source came out mottled;
        // and folding in the vertex colour meant vanilla's own face shading
        // decided it, which multiplies the sides of a block by 0.8 and 0.6 and
        // its underside by 0.5 — so a block glowed from its top and two sides
        // and not the other two. Both were reported from a screenshot.
        outColor = vec4(shaded, 0.5 + 0.5 * emits * fogKeep);
    }
}
