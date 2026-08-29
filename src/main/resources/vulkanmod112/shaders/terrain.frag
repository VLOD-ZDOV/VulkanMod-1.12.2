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
    // x = how many of lights[] are in use.
    // y = how brightly the sun and moon glint off water and ice, 0 = off.
    // z = how much deeper the sky gets away from the horizon, 0 = off. Read by
    //     skyAlong, which is what a water surface reflects along its ray.
    // w is spare and zeroed.
    vec4 lightInfo;
    vec4 lights[32]; // xyz = position relative to the camera, w = light level
    // x = seconds, y = directional light strength (0 = off),
    // z = 1 when vMaterial is real, w = 1 to paint the world by material
    vec4 frameInfo;
    // x = strength (0 = off), y = thickening per block,
    // z = how many entries of materialSprites are in use,
    // w = how much of the water's Fresnel term to believe, 0 = off. It lives
    //     here for want of a fourth component anywhere better; see where the
    //     mirror is mixed for what it means.
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
    // x = how much sky a sheet of ice gathers on itself. 0 off.
    // y = how hard the bed under water is banded by the surface above it.
    //     Zero unless refraction is fetching that bed — there is no other
    //     picture of it to brighten.
    // z = how wet an upward face is: the setting and the weather already
    //     multiplied together, because neither is any use here without the
    //     other.
    // w = how far the fog leans towards the sun's own colour. 0 off.
    vec4 surface;
    // x = how far the camera is above this world's sea level, in blocks.
    //     Adding vRelative.y to it gives the fragment's own height above the
    //     sea, which is what the height fog is measured from.
    // y = how much light a canopy lets through, 0 = a leaf stops a ray like
    //     stone. Read only where rays are traced.
    // z = 1 when the frame carries more than eight bits a channel, which is
    //     what decides how far a glint is allowed to go past white.
    // w is spare and zeroed.
    vec4 world;
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

/**
 * How little water the refraction fetch below needs to find between the
 * surface and whatever is behind it before it stops trusting that sample, in
 * blocks.
 *
 * This pass fixes its picture of the opaque world before creatures are
 * drawn, so a squid or a pair of villager legs under the surface is not in
 * it — only the lake bed underneath them is. The two cannot be told apart by
 * depth alone, but they do not need to be: a creature sits close under the
 * surface, and so does a shallow bed, so treating "not much water here" as
 * "do not trust this sample" catches the one this pass cannot see without
 * costing the one it can. On a lake bed several blocks down this changes
 * nothing.
 */
const float CREATURE_LIKELY_DEPTH = 3.0;

/*
 * WHY_NOT_WITH_RAY_QUERY
 *
 * Two effects below — the shine on ice and the caustics on a riverbed — are
 * compiled out of the tracing variant of this shader, and that is not tidiness.
 *
 * The translucent pipeline is the one with no room left in it. It carries an
 * unconditional shadow ray per fragment, up to eight more towards moving
 * lights, and a marched reflection of up to forty dependent samples. All of
 * that is latency-bound work, which does not scale with how much arithmetic is
 * added to it — it scales with how many waves the card can keep in flight to
 * hide the waiting, and that is decided by registers.
 *
 * This was measured, once, expensively. A specular glint on water — a
 * normalize, a dot and a pow, under ten operations — was added, and the card
 * was lost outright (Xid 109, a context switch timeout) reproducibly, over
 * open water, with tracing on. Three arms of the experiment: tracing and
 * glint together lost the device, tracing alone was clean, glint alone was
 * clean. The one measured fact was that the glint grew this shader's tracing
 * variant by 7.4%.
 *
 * Both effects here are translucent-only, so gating them on BLEND would not
 * help — the translucent pipeline is exactly where they would land. Compiled
 * out of the tracing variant, they cost that pipeline nothing at all, and the
 * settings screen says out loud that they are switched on and inert when rays
 * are being traced. See SettingsHealth on the game side.
 *
 * What would let them back in is not a smaller version of them: it is making
 * the water branch itself cheaper, at which point this can be measured again.
 * The full account is in docs/CRASH-GLINT.md.
 */

/**
 * How much darker a fully wet surface gets, and how much sky it gathers.
 *
 * A film of water carries light down into the material rather than scattering
 * it straight back, so wet stone is darker than dry stone — and it is smooth
 * where the stone is rough, so it catches the sky at a grazing angle. Both, or
 * the effect reads as a dusting of snow. The darkening is the larger of the
 * two on purpose: it is the half people recognise without being told.
 */
const float WET_DARKEN = 0.30;
/*
 * How much of the sky a wet floor gathers, at the angle where it gathers most.
 *
 * Was 0.55, and measured against the same frame with the effect off, a wet
 * floor came out *brighter* than a dry one: up to twenty-one levels of two
 * hundred and fifty-five brighter on the ground nearest the camera. Water on a
 * surface darkens it — that is the whole of what makes it read as wet — and
 * the sheen was more than undoing the darkening, so rain painted the world a
 * pale blue instead of wetting it. Reported twice in those words, and the
 * second time as the reason a block one step up looks untouched while the
 * floor at your feet does not: the two are seen at different angles, and this
 * term was the only thing in the effect that depends on the angle.
 *
 * Chosen so that the net is a darkening at every angle. With ground at half
 * brightness and a sky of about two thirds: dry 0.50, wet looking straight
 * down 0.37, wet at a grazing angle 0.42. It still lifts towards the sky as
 * the angle flattens, which is the thing worth having, but it can no longer
 * cross back over dry.
 */
const float WET_SHEEN = 0.28;
/**
 * What is left of the sheen when looking straight down at a wet surface.
 *
 * Fresnel to the fifth is all or nothing: look along a floor and it is
 * essentially one, look down at it and it is essentially zero. That is right
 * for a mirror and wrong for a wet block, whose film sits on something rough
 * and scatters some of the sky back whatever the angle — and getting it wrong
 * this way is worse than it sounds, because the darkening on its own is easy
 * to miss in the dark scene rain brings with it. Reported as rain wetting the
 * ground only while standing level with it and drying the moment you jumped,
 * which is a description of this curve rather than of the weather.
 */
const float WET_SHEEN_FLOOR = 0.18;
/*
 * And the most, at the angle where a film of water is nearly a mirror.
 *
 * Was one, which is what a mirror returns and what made the effect swing by a
 * factor of five between looking down at a floor and looking along it. A
 * block a step up is seen along, the floor at your feet is seen down at, and
 * two neighbouring blocks in the same rain came out visibly different.
 */
const float WET_SHEEN_GRAZE = 0.80;

/**
 * Which way the haze leans, per unit of leaning towards the sun.
 *
 * Added to one rather than being a colour of its own, so this tilts whatever
 * the sky is already doing instead of replacing it: the same numbers give a
 * warm haze at noon and a warmer one under an overcast sky, and neither is a
 * colour nobody in this world has seen. Positive towards the sun, negative
 * away from it, and the same three numbers do both — the sky opposite the sun
 * is the same air seen from the other side.
 */
const vec3 HAZE_LEAN = vec3(0.32, 0.13, -0.17);

/**
 * How much of the sky a sheet of ice may gather, at most.
 *
 * Less than water's, and deliberately: water reaches nearly all of it at a
 * grazing angle because water really is a mirror there, and ice in this game
 * is a translucent pane with a texture that has to stay legible. Past about a
 * half the block stops looking like ice and starts looking like a hole in the
 * world with the sky behind it.
 */
const float ICE_MIRROR_MAX = 0.55;

/**
 * What is left of the ice sheen when looking straight down at it.
 *
 * Reported as "the ice stops reflecting if you stand on it", and that was
 * exactly what the code did: the sheen was a bare Fresnel term, which is
 * (1 - facing) to the fifth, and standing on a frozen lake makes facing one.
 * Five times zero is zero, and the block went flat under the player's feet
 * while the same block twenty away still held the sky.
 *
 * A floor rather than a different curve, because the wet-surface sheen forty
 * lines down already needed one for the same reason and settled it the same
 * way. Ice is smoother than a wet block, so this is lower than its 0.18.
 *
 * Not the physical number. Real ice reflects about two per cent of what
 * arrives straight on, and two per cent of a sky is not visible on a texture
 * this legible — the block would still read as having gone flat. This is the
 * smallest value at which standing on the lake does not look like the effect
 * switching itself off.
 */
const float ICE_SHEEN_FLOOR = 0.22;

/**
 * How steep the surface has to be before a caustic band is fully dark, and how
 * much brighter the flat parts get.
 *
 * Caustics are the surface acting as a lens on the light coming through it,
 * and the light gathers where the surface is flat and thins where it is
 * steeply tilted — so the pattern is already in the slope this shader computed
 * for the waves, and no second field of noise is needed for it. Squared to
 * pull the bright parts into cells with dark lines between them, which is what
 * the eye recognises; the alternative is a smooth mottling that reads as dirty
 * water.
 *
 * The edge is a fraction of the steepest slope the current wave setting can
 * produce rather than an absolute tilt — at 2.6 a band is fully dark by the
 * time the surface reaches about four tenths of that. Absolute is what the
 * first version used, and at any wave strength anyone plays with, the water
 * never got near it.
 */
/**
 * What a block of water takes out of the light passing through it.
 *
 * Red first, then green, then blue, which is why deep water is blue and why a
 * red thing a few blocks down looks grey. The numbers are chosen for a game
 * whose seas are a handful of blocks deep rather than from any table: the real
 * coefficients would do nothing visible over five blocks.
 */
const vec3 WATER_ABSORB = vec3(0.42, 0.16, 0.09);
/** What is left when the bed is too far down to contribute anything at all. */
const vec3 WATER_DEEP = vec3(0.05, 0.16, 0.24);
/** How thin the water has to be for foam, in blocks, and how white it gets. */
const float FOAM_REACH = 1.6;
const float FOAM_MAX = 0.55;

const float CAUSTIC_EDGE = 2.6;
const float CAUSTIC_GAIN = 0.85;

/** How tight the glint is: water ripples broadly, a sheet of ice sharply. */
const float WATER_GLINT_SHARPNESS = 48.0;
const float ICE_GLINT_SHARPNESS = 96.0;
/**
 * Full strength at the setting's maximum. Above this the sun becomes a lamp.
 *
 * Two ceilings, because there are two frames this can land in.
 *
 * It was brought down from 2.5 once: that put the core of the highlight far
 * enough over white that it clipped to a flat sheet of it, and in a frame of
 * eight bits a channel everything spent past one buys nothing and costs the
 * shape of the thing. What is wanted is a bright core with ripple still
 * visible inside it.
 *
 * With headroom the opposite is true: a highlight held under one is a
 * highlight that never reads as the sun, and the film curve at the end of
 * the frame is there precisely to bring a number like four back down along a
 * shoulder. So the ceiling follows the frame rather than being chosen once.
 */
const float GLINT_MAX_LDR = 1.8;
const float GLINT_MAX_HDR = 4.0;
/** Not white: sunlight is warm, and a neutral glint reads as a specular bug. */
const vec3 SUN_TINT = vec3(1.0, 0.96, 0.88);
/** Moonlight is the same sunlight twice reflected: cooler, and far dimmer. */
const vec3 MOON_TINT = vec3(0.62, 0.70, 0.95);
const float MOON_SHARE = 0.30;
/**
 * How far out the glint is allowed to reach, in blocks.
 *
 * This is the fix for the one thing everybody noticed about the first version:
 * flying up over an ocean made the sun on the water grow until it filled the
 * view. That is not a bug in the highlight, it is what a specular lobe does —
 * the higher the eye, the more of the sea is at an angle that returns the sun,
 * so the glitter path widens towards the horizon exactly as it does in a
 * photograph. Physically right, and wrong for this game: nothing else in this
 * world grows when you climb, and a light that does reads as an error.
 *
 * Faded with distance instead, squared, so what grows on the way up is what
 * disappears. It also bounds how much of the screen the effect can ever cover,
 * which is the quantity that was rising when the graphics device was lost.
 */
const float GLINT_REACH = 72.0;

/**
 * The sun or the moon itself on a surface, rather than the sky it reflects.
 *
 * Water in the open shows two different things at once. One is the horizon,
 * which the fresnel term mixes in and the screen reflection sharpens — that is
 * the surface acting as a mirror. The other is a narrow bright glint sliding
 * along the ripples, and no reflection can ever produce it: the sun is a
 * light, not a surface that was drawn into the scene for a ray to find.
 * Reflecting the sky where the sun is gives its colour, not its shape.
 *
 * @param n         the normal to measure against
 * @param sharpness how tight the lobe is
 * @param toLight   direction to the sun, or to the moon at night
 * @param up        how far above the horizon that light is, 0 at it
 */
float celestialGlint(vec3 n, float sharpness, vec3 toLight, float up) {
    if (up <= 0.0) {
        return 0.0;
    }
    vec3 sum = normalize(-vRelative) + toLight;
    // Guarded, and not because it was ever seen to fail. The two are unit
    // vectors, so their sum is zero exactly when they oppose — which happens
    // on one point of the screen, the one directly away from the sun, and over
    // open water from a height that point is on the water. normalize of zero
    // is NaN across the whole surface rather than a wrong shade on one pixel
    // of it, and this shader already carries the same guard on foliage normals
    // for the same reason. One comparison closes it whether or not it was ever
    // the cause of anything.
    float length2 = dot(sum, sum);
    if (length2 < 1.0e-8) {
        return 0.0;
    }
    vec3 h = sum * inversesqrt(length2);
    float lobe = pow(max(dot(n, h), 0.0), sharpness) * min(up * 4.0, 1.0);
    // See GLINT_REACH: this is what stops it growing as you climb.
    float near = clamp(1.0 - vDistance / GLINT_REACH, 0.0, 1.0);
    return lobe * near * near;
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
    // The turn is only ever right where something averages the frames it
    // produces, and that is the opaque target alone: the translucent one has no
    // motion vectors, so nothing reprojects it and nothing accumulates it.
    //
    // This is the same rule that made the turn exist, read from the other end.
    // A dither that never moves averages a hundred copies of one answer, which
    // is why the turn was added; a dither that moves with nothing to average it
    // is a different answer every frame with nothing to settle it, which is not
    // grain but flicker — and flicker on water while frame averaging is *on*
    // reads as the averaging having made things worse.
    //
    // BLEND is a specialization constant, so the pipeline that draws water is
    // compiled with the turn folded away to nothing rather than branching on it.
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715)))
                 + (BLEND ? 0.0 : frame.lightShadow.z));
}

// True only in the build that can trace, and a compile-time constant in both —
// so the branch it guards costs nothing in the ordinary shader and the
// derivatives inside it stay legal.
#ifdef RAY_QUERY
#define SUN_SHADOWS_WANTED (frame.sun.w > 0.0)
#else
#define SUN_SHADOWS_WANTED false
#endif

#ifdef RAY_QUERY
/**
 * What a structure is made of, matching the kinds the Java side writes into
 * each instance's custom index.
 */
const int KIND_SOLID = 0;
const int KIND_FOLIAGE = 1;
const int KIND_CUTOUT = 2;

/**
 * How much of the light a quad of each kind stops, as a probability.
 *
 * A ray cannot read a texture — not without carrying the atlas, the UVs and a
 * buffer address into every shadow test, which is a great deal of machinery on
 * the one path in this shader with no headroom left. So a leaf quad is not
 * asked *where* its holes are; it is asked *how much* of it is holes, and light
 * passes with that probability. Averaged over the frames the accumulation pass
 * already blends, a canopy comes out dappled rather than solid, which is the
 * thing that was missing.
 *
 * The numbers are what the textures look like: a leaf block is mostly leaf with
 * gaps, a tuft of grass is mostly gap with a few blades in it.
 */
const float FOLIAGE_STOPS = 0.72;
const float CUTOUT_STOPS = 0.34;

/**
 * A number that belongs to this quad and this pixel, and moves between frames.
 *
 * Per primitive, so the speckle sits on the leaf rather than swimming across
 * it when the camera turns. Per pixel, so neighbouring pixels do not all decide
 * the same way and turn the dapple into a hard edge. And offset by the dither
 * rotation, which is nonzero exactly when the accumulation pass is running —
 * so where the frames are being averaged this varies and dissolves into shade,
 * and where they are not it holds still instead of boiling.
 */
float leafChance(uint primitive, vec2 pixel) {
    vec3 seed = vec3(float(primitive & 0xFFFFu), pixel);
    return fract(sin(dot(seed, vec3(12.9898, 78.233, 37.719))) * 43758.5453
            + frame.lightShadow.z);
}

/**
 * Whether anything stops a ray between two points.
 *
 * The loop is what makes see-through geometry possible at all. With everything
 * opaque the traversal commits the first thing it meets and never comes back to
 * ask — which is correct for stone and wrong for every leaf in the world. Now
 * anything that is not solid arrives as a candidate this shader may refuse, and
 * the refusal is what light coming through a canopy is.
 *
 * Solid geometry never reaches the loop: it is marked opaque in the structure,
 * so the driver commits it without asking, exactly as before.
 */
bool rayBlocked(vec3 from, vec3 direction, float start, float reach) {
    rayQueryEXT query;
    // BLEND keeps the old, cheaper test, and that is a measurement rather than
    // a preference: the loop below costs the traced translucent pipeline 6% more
    // code, and that pipeline — the one carrying the water, the marched
    // reflection and the traced lights — is where this renderer lost the
    // graphics device once, for 7.7%. What is bought by paying it there is
    // dappled light on the surface of a pond under a tree; what is bought on
    // the opaque pipelines is dappled light on the whole forest floor. The
    // constant is a specialisation constant, so the driver removes whichever
    // half does not apply.
    if (BLEND) {
        rayQueryInitializeEXT(query, terrainStructure,
                gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
                0xFFu, from, start, direction, reach);
        rayQueryProceedEXT(query);
        return rayQueryGetIntersectionTypeEXT(query, true)
                != gl_RayQueryCommittedIntersectionNoneEXT;
    }
    rayQueryInitializeEXT(query, terrainStructure,
            // No gl_RayFlagsOpaqueEXT here, and that is the whole change: as a
            // ray flag it overrules what each structure says about itself, so
            // with it set the loop below could never run.
            gl_RayFlagsTerminateOnFirstHitEXT,
            0xFFu, from, start, direction, reach);
    // frame.world.z: one when this renderer's own colour target has room above
    // white in it, zero when it is eight bits a channel. What reads it is the
    // ceiling of the sun's highlight, which has to be two different numbers
    // for the two cases and cannot be told apart any other way from inside a
    // shader.
    // frame.world.y: how much light leaves are allowed to let through, 0 for
    // the old behaviour. A switch rather than a rebuild, because a slider that
    // recompiles a pipeline is a slider that stutters.
    float seeThrough = frame.world.y;
    while (rayQueryProceedEXT(query)) {
        if (seeThrough <= 0.0) {
            rayQueryConfirmIntersectionEXT(query);
            continue;
        }
        int kind = rayQueryGetIntersectionInstanceCustomIndexEXT(query, false);
        float stops = kind == KIND_FOLIAGE ? FOLIAGE_STOPS
                : (kind == KIND_CUTOUT ? CUTOUT_STOPS : 1.0);
        // Eased towards opaque as the setting comes down, so the slider moves
        // the shade from dappled to solid rather than switching between two
        // pictures.
        stops = mix(1.0, stops, seeThrough);
        uint primitive = uint(rayQueryGetIntersectionPrimitiveIndexEXT(query, false));
        if (leafChance(primitive, gl_FragCoord.xy) < stops) {
            rayQueryConfirmIntersectionEXT(query);
        }
    }
    return rayQueryGetIntersectionTypeEXT(query, true)
            != gl_RayQueryCommittedIntersectionNoneEXT;
}
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
    return rayBlocked(from, direction, 0.01, reach) ? frame.sun.w * fade : 0.0;
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
    // Stopping short of the source, and never inside half of the way: a torch
    // is a piece of geometry standing in front of the light it emits, and a
    // fixed margin that is right for a lamp across the room is most of the
    // distance to one held in the hand.
    return rayBlocked(from, direction, 0.02, max(travel - 0.5, travel * 0.5))
            ? 1.0 : 0.0;
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
    // Not turned towards the eye here. That is done at the end of this
    // function, after the snapping, and the difference is the whole point:
    // before the snap it is a dot product of two noisy vectors and comes out
    // either way exactly when the face is hardest to measure — which for a
    // block top means the shading that keys off "does this face up" loses it
    // at grazing angles, and rain stops wetting the floor you are standing on.
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
    // The quad's own direction, put back on the diagonal it is really built on.
    //
    // faceNormal snaps a measured direction to an axis when it is clearly along
    // one, and deliberately does not when it is not — a crossed quad sits at
    // forty-five degrees and its largest component is 0.71, so no axis is near
    // it. That leaves foliage as the one surface in the world shaded from the
    // raw cross product of two screen derivatives, and a third of what is mixed
    // in here is that raw vector. Two derivatives determine a plane badly
    // wherever the plane is seen edge-on, and worse still across the line where
    // the two quads of a cross meet on screen, where a block of four pixels
    // straddles both of them and the difference is taken over two planes at
    // once. What comes back moves with the camera, so grass dimmed and lifted
    // slightly as you turned or walked — the same class of fault the axis snap
    // was written for, in the one case it was written to skip.
    //
    // A cross has four possible directions and they are known in advance, so
    // the same argument applies: the measurement does not have to be believed
    // to any precision, only enough to say which of four it is, and that
    // survives noise that would ruin the direction itself. The quads are
    // vertical, so the answer has no height in it.
    vec2 sideways = geometric.xz;
    vec3 stable = dot(sideways, sideways) < 1.0e-6
            ? vec3(0.0, geometric.y < 0.0 ? -1.0 : 1.0, 0.0)
            : vec3(sign(sideways.x), 0.0, sign(sideways.y)) * 0.7071068;
    vec3 bent = mix(stable, vec3(0.0, 1.0, 0.0), FOLIAGE_UPRIGHT);
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

/*
 * There was a screen-space march here that asked whether the sun this
 * fragment is about to glint for is standing behind something, and it has
 * been taken out. What it produced was the single most reported defect on
 * water in this mod.
 *
 * The march walked from the water towards the sun and asked the depth buffer,
 * at each step, whether it had ended up behind what was drawn there. A depth
 * buffer records a surface and not a solid, so that question has no answer:
 * every ray that clears the far bank passes behind the far bank on its way
 * up, and "went into it" and "went over it" look identical. Neighbouring
 * pixels sample different texels at every step and so disagree — one keeps
 * all of its glint, the other loses all of it — and the result was a field of
 * dots in an ordered grid with a hard edge, sitting in the middle of the
 * sun's own reflection.
 *
 * Five things were tried against a fixed camera, a fixed hour and a frozen
 * clock: a thickness slab, a cap on that slab, the share of the march that
 * was blocked rather than the first hit, a near-field march of eight blocks,
 * and a lower ramp clear of rounding. Every one of them left the speckle;
 * removing the march left a clean highlight and nothing else changed.
 *
 * The price is that the glint can appear on water the sun cannot actually
 * reach — behind a hill, most visibly near sunrise. Nobody has reported that;
 * the speckle was reported twice. The proper answer to it is a shadow map,
 * which is a thing this renderer can now have.
 */


/**
 * The sky along a direction, built the way the sky pass builds it.
 *
 * What a water surface shows where the ray found nothing used to be one flat
 * colour: the game's fog, which is the horizon. At a grazing angle that is
 * exactly right and there is nothing to improve — the horizon is what a flat
 * mirror shows you when you look along it. Look *down* at the water, though,
 * and the ray leaves steeply into a part of the sky that is nothing like the
 * horizon, and answering with the horizon anyway is what makes a lake read as
 * paint rather than as water. It is the single largest difference between this
 * and a shader pack's water, and it costs no march, no buffer and no pass.
 *
 * Deliberately the same arithmetic and the same three constants as the pass
 * that paints the sky itself, rather than a second sky invented here. Two
 * skies that disagree is a worse fault than one flat one, and this project has
 * paid for that lesson elsewhere: a reference copy that does not match the
 * composite loses the effect entirely and says nothing.
 *
 * Gated on the sky gradient's own strength, and that is not a spare switch
 * being borrowed. With the gradient off the player's sky really is flat —
 * vanilla paints one blue from horizon to zenith — so the flat answer is the
 * honest reflection of it, and the old behaviour is what comes back.
 *
 * @param horizon the colour the game is fogging to, which is the horizon
 */
vec3 skyAlong(vec3 dir, vec3 horizon) {
    float strength = frame.lightInfo.z;
    if (strength <= 0.0) {
        return horizon;
    }
    float up = clamp(dir.y, 0.0, 1.0);
    float deep = pow(up, 0.65);
    float day = clamp(frame.sun.y * 4.0, 0.0, 1.0);
    float toSun = clamp(dot(dir, frame.sun.xyz), 0.0, 1.0);
    float glow = pow(toSun, 6.0) * (1.0 - up) * day;
    vec3 zenith = horizon * vec3(0.42, 0.46, 0.62);
    vec3 warm = min(horizon * vec3(1.35, 1.12, 0.86), vec3(1.0));
    return mix(horizon, mix(zenith, warm, glow), strength * max(deep, glow));
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
    // How far below the sea this fragment is — not how far below the eye.
    //
    // It used to be measured from the camera, which reads as the same thing
    // while you are standing on the ground and is not the same thing at all
    // once you leave it. Flying at a height of three hundred puts the whole
    // world "far below", the exponential saturates everywhere at once, and the
    // effect stops being fog and becomes a flat wash of the fog colour over
    // every block on screen. With Distant Horizons installed that wash had a
    // visible edge, because the far terrain is drawn by that mod and never saw
    // it: a coloured disc exactly the size of the vanilla render distance,
    // centred on the player, following them about.
    //
    // Measured from the sea it means what it says. Fog gathers in ravines,
    // canyons and the deep parts of the ocean floor, hills stay clear, and how
    // high the camera happens to be does not change what the ground looks
    // like — which is the whole idea of fog that depends on height.
    float below = max(0.0, -(vRelative.y + frame.world.x));
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
    // frame.surface.z is in this list because rain only wets a face that
    // points up, and which way this one points is the whole question.
    vec3 normal = (BLEND || (lightCount > 0 && directional > 0.0) || SUN_SHADOWS_WANTED
            || (lightCount > 0 && frame.sunParams.w > 0.0) || frame.surface.z > 0.0)
            ? faceNormal() : vec3(0.0, 1.0, 0.0);
    // Kept before the bending below, because two different questions are being
    // asked of this vector and only one of them is about light. Which way the
    // geometry actually points is a fact about the block, and rain lands on it
    // rather than on the direction a tuft of grass is shaded from.
    vec3 geometricNormal = normal;
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
    // Which sources are allowed a traced shadow, decided by distance to this
    // surface rather than by their place in the list.
    //
    // The list arrives sorted by distance to the *camera*, and the loop below
    // used to take the first few of it that reached this fragment at all. So
    // which torch cast a shadow was a fact about where the player stood: walk
    // one block, the order changes, a different torch is chosen, and the shadow
    // on the wall in front of you appears or vanishes without anything in the
    // scene having moved. Reported as exactly that — shadows coming and going
    // from a step up or down.
    //
    // The nearest source to a surface is the one whose shadow the eye expects,
    // and it does not change when the player moves a block away. One pass to
    // find the cutoff distance, which costs the same arithmetic the loop below
    // already does and no memory.
    float tracedCutoff = 1.0e9;
    // !BLEND is not tidiness, it is the whole reason this is affordable. BLEND
    // is a specialisation constant, so the driver folds this block out of the
    // translucent pipeline completely — and that pipeline is the one carrying
    // the traced shadows, the marched reflection and the water, the one place
    // in this shader with no headroom left. What is being fixed here is torch
    // shadows on blocks, which live in the opaque pipelines; water gains
    // nothing from it either way. Measured: +3.0% of the traced translucent
    // pipeline with this in it, zero with the gate.
    if (!BLEND && maxTracedLights > 0 && maxTracedLights < lightCount) {
        // One pass, not one per allowed light: this sits in the branch that
        // carries the traced shadows, the marched reflection and the water, and
        // that branch is where this renderer lost the graphics device once. A
        // selection of the exact nearest few costs a pass each and measured
        // +4.2% of the traced translucent pipeline; this costs +1% and answers
        // the same question well enough — the sources close to the surface are
        // in, the ones far behind them are out, and neither answer moves when
        // the player takes a step.
        float nearest = 1.0e9;
        for (int i = 0; i < lightCount; ++i) {
            vec4 source = frame.lights[i];
            float d = length(source.xyz - vRelative);
            if (source.w - d > 0.0) {
                nearest = min(nearest, d);
            }
        }
        // Half again as far as the closest source that reaches here. Anything
        // inside that is a light this surface is genuinely near; anything past
        // it was being traced only because it happened to come first in a list
        // sorted by where the camera stands.
        tracedCutoff = nearest * 1.6;
    }
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
        if (level > 0.0 && tracedLights < maxTracedLights && distance <= tracedCutoff) {
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
    // Worked out once. The translucent pass needs the same answer again at
    // the very end, after the water code has finished repainting the surface,
    // and asking materialColor twice puts the whole chain of comparisons into
    // the one pipeline here with no room to spare.
    vec3 materialView = vec3(0.0);
    if (frame.frameInfo.w > 0.5) {
        materialView = materialColor(material) * light;
        shaded = materialView;
    }

    // Rain, on the faces it can land on.
    //
    // A wet surface does two things at once and both are needed for it to read
    // as wet rather than as pale: it darkens, because the water film carries
    // light down into the material instead of scattering it straight back, and
    // it gathers the sky at a grazing angle, because the film is smooth where
    // the block is rough. Doing only the second makes stone look dusted with
    // snow.
    //
    // Only upward faces, and only in proportion to the sky light the surface
    // already receives. There is no test for whether this particular block has
    // anything over it — that would be a ray, and this is meant to cost almost
    // nothing — so sky light stands in for one: a floor deep in a cave has none
    // and stays dry, a cave mouth has some and dampens a little, which is
    // wrong and looks like weather rather than like an error.
    //
    // !BLEND is not a tidying-up: it is what keeps this out of the translucent
    // pipeline altogether. BLEND is a specialisation constant, so the driver
    // folds this whole block away when it compiles that pipeline — and that
    // pipeline is the one carrying the traced shadows, the traced lights and
    // the marched reflection, the one path in this shader with no headroom
    // left. Water and ice live only in that pass anyway and are excluded here
    // by their own nature rather than by a test.
    //
    // Measured against the geometric normal rather than the shaded one, and
    // that is not a detail. Foliage is lit as a volume here: the normal of a
    // tuft of grass is bent most of the way to standing up, because that is
    // what makes a torch held above it light it. Rain then landed on every
    // blade of grass, every flower and every leaf as though each were a floor
    // — a field turning blue in the rain, and tree canopies bluest of all.
    // The crossed quads a plant is made of are vertical; the bend is about
    // where light comes from, not about what is above the surface.
    if (!BLEND && frame.surface.z > 0.0 && geometricNormal.y > 0.9
            && !foliage && material != MATERIAL_LAVA) {
        // Only a face the sky can actually reach, and "actually" is the whole
        // of the fix. This was the sky light itself, which is not the same
        // question: a cave with a mouth twenty blocks away still carries sky
        // light eight, and eight of fifteen made that floor half wet. It was
        // reported exactly that way — the floor of a cave with an opening got
        // rained on, and only burying yourself deep enough made it stop.
        //
        // The numbers are the lightmap's own: the game writes a sky level
        // times sixteen and the vertex shader adds half a texel over 256, so
        // level fifteen is 0.96875 and level fourteen is 0.90625. Taking the
        // step between exactly those two means one block of overhang is dry
        // and open ground is wet, with the interpolation across a face giving
        // the boundary a soft edge for free. A canopy of leaves also stops it,
        // which is right: rain does not fall through a tree either.
        float skyOpen = smoothstep(0.90625, 0.96875, vLight.y);
        float wet = frame.surface.z * skyOpen;
        shaded *= 1.0 - WET_DARKEN * wet;
        // The same Fresnel the water uses, against the same fog colour that
        // stands in for the sky everywhere else in this shader — but only
        // where the game has fog of its own, because that colour is only
        // refreshed while it does. With fog off it holds whatever was last
        // captured, possibly in another dimension, and a floor sheened with a
        // remembered sky is worse than a floor that only darkens.
        if (frame.fogColor.a > 0.5) {
            shaded = mix(shaded, frame.fogColor.rgb,
                    mix(WET_SHEEN_FLOOR, WET_SHEEN_GRAZE, fresnel(geometricNormal))
                            * wet * WET_SHEEN);
        }
    }

    // Rounded, not truncated, and the sprite shader does the same. Both read
    // this one field of this one buffer, and a particle taking a different fog
    // mode from the terrain behind it would be a hard thing to see and a
    // harder one to explain. Today the value is written as a whole number and
    // either reading gives the same answer; agreeing costs nothing and stops
    // that from being load-bearing.
    int mode = int(frame.fogColor.a + 0.5);
    // The fog, leaning towards the sun.
    //
    // The game fogs everything to one colour whichever way you are facing, and
    // the sky it hangs under does not: air scatters short wavelengths sideways
    // and long ones forwards, so haze towards the sun is warm and bright and
    // haze away from it is cool. This is that, and only that — one colour
    // mixed by how squarely you are looking at the sun, with no scattering
    // integral anywhere near it.
    //
    // Read from the eye rather than from the surface: what is being tinted is
    // the air between the two, and the air does not care which block is at the
    // far end of it.
    vec3 fogRgb = frame.fogColor.rgb;
    if (frame.surface.w > 0.0 && mode != 0 && frame.sun.y > 0.0) {
        float facing = dot(normalize(vRelative), frame.sun.xyz);
        // Squared with its sign kept: -1 looking away from the sun, 0 across
        // it, +1 into it, and the square narrows both ends without a second
        // curve or a pow. One signed number covers warm and cool, which is
        // what they are — one lean, not two effects.
        float lean = facing * abs(facing);
        // Nothing while the sun is on the horizon or under it: the game's own
        // fog is already doing the sunset, and a second warmth over it turns
        // the whole sky orange.
        float risen = min(frame.sun.y * 4.0, 1.0);
        // Exactly neutral at lean = 0, so a player facing across the sun sees
        // the fog the game chose and nothing added to it.
        fogRgb *= 1.0 + HAZE_LEAN * (lean * frame.surface.w * risen);
    }
    // Kept for the emissive mask below: how much of this surface survived the
    // fog. Light that the fog swallowed must not glow either — inside lava,
    // where the fog is thick enough to hide the world, the silhouettes of
    // distant blocks still had burning edges, because their colour had been
    // taken to the fog colour and their claim to be a light had not.
    float fogKeep = 1.0;
    if (mode != 0) {
        fogKeep = clamp(fogFactor(mode), 0.0, 1.0);
        shaded = mix(fogRgb, shaded, fogKeep);
    }
    // After the distance fog and only where the game already has fog of its
    // own: with fog switched off there is no colour to thicken towards, and
    // inventing one would make this the only surface in the scene fading into
    // something the sky never does.
    if (mode != 0 && frame.heightFog.x > 0.0) {
        float thickened = clamp(heightFogAmount(), 0.0, 1.0);
        shaded = mix(shaded, fogRgb, thickened);
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
                // Shifted by moving the point in the world and asking where
                // that lands, rather than by adding world x and z to a screen
                // coordinate.
                //
                // Those are different spaces. Screen x is to the right of the
                // camera and screen y is up it; world x and z are north and
                // east and do not care where the camera is pointing. Added
                // together, the bed slid the wrong way as the view turned —
                // north on the water became right on the screen only while
                // facing one direction, and reversed when facing the other.
                // The projection is the conversion, and it also does the
                // distance falloff that the divide by w was standing in for.
                vec3 tilted = vRelative + vec3(normal.x, 0.0, normal.z)
                        * frame.lightShadow.w * REFRACT_REACH;
                vec4 tiltedClip = frame.mvp * vec4(tilted, 1.0);
                vec2 shifted = tiltedClip.w > 0.0001
                        ? clamp(tiltedClip.xy / tiltedClip.w * 0.5 + 0.5, vec2(0.0), vec2(1.0))
                        : uv;
                // Only if what is there is really behind the water. A sample in
                // front of it is something standing between the eye and the
                // surface, and smearing that across the water is the artefact
                // every refraction gets wrong first: a reed on the bank waving
                // about inside the pond.
                float behindDepth = textureLod(sceneDepth, shifted, 0.0).r;
                if (behindDepth < here) {
                    shifted = uv;
                    // Re-read for the pixel actually being used now, not the
                    // tilted one the check above was for. Only in the build
                    // that goes on to use it — see the mix() a few lines
                    // down, which is the one place this second sample pays
                    // for itself.
#ifndef RAY_QUERY
                    behindDepth = textureLod(sceneDepth, uv, 0.0).r;
#endif
                }
                vec3 behind = textureLod(sceneColor, shifted, 0.0).rgb;
                // The bed, banded by the surface above it.
                //
                // Not a second pattern: `normal` is the wave normal by this
                // point, so its horizontal part is the slope of the surface,
                // and light passing through gathers where that slope is small
                // and thins where it is large. Costs a dot, a couple of
                // multiplies and no trigonometry at all — the waves were
                // already evaluated for the shading above.
                //
                // It brightens rather than redistributing: the light taken out
                // of the dark lines is not put back into the bright cells, so
                // a bed with this on is a little brighter overall. That is the
                // honest simplification here, and the reason the gain is under
                // one.
                //
                // Not built into the tracing variant of this shader. See
                // WHY_NOT_WITH_RAY_QUERY.
#ifndef RAY_QUERY
                // Waves are a condition rather than a nicety: the pattern is
                // read out of the slope of the wave, so with the waves off
                // there is no slope, no pattern, and all this would do is make
                // the riverbed uniformly brighter.
                if (frame.surface.y > 0.0 && frame.water.x > 0.0) {
                    // Measured against the steepest slope this wave setting can
                    // actually produce, which is the fix for the first version:
                    // it compared the tilt against a fixed number, and at the
                    // wave strengths anybody uses the surface never came near
                    // it. Every fragment landed in the flat part of the curve,
                    // the whole bed brightened by the same amount, and the
                    // cells and dark lines that are the entire point of the
                    // effect were a few per cent of contrast that nobody could
                    // see. Now the pattern keeps its shape at any wave setting,
                    // and the setting decides how the water moves rather than
                    // whether this is visible at all.
                    float reach = max(WAVE_SLOPE * frame.water.x, 1.0e-3);
                    float tilted = clamp(length(normal.xz) * (CAUSTIC_EDGE / reach),
                            0.0, 1.0);
                    // Not named "flat": that is a storage qualifier here, and
                    // the error it gives names the line after the one it is on.
                    float level = 1.0 - tilted;
                    behind *= 1.0 + frame.surface.y * CAUSTIC_GAIN * level * level;
                }
#endif
                // Not built into the tracing variant, and measured rather
                // than assumed: absorption and foam together cost the traced
                // translucent pipeline 5.3% more code — the same pass, and
                // very nearly the same figure, that lost the graphics device
                // when the glint was added to it. Ice, caustics and the glint
                // are all out of that variant for the same reason, and the
                // settings screen says so rather than leaving a slider that
                // appears to do nothing. See WHY_NOT_WITH_RAY_QUERY.
#ifndef RAY_QUERY
                // How much water the light came through, and what that does to
                // it.
                //
                // This is the one thing a pond in this renderer never had, and
                // it is the difference between water and a blue window: a
                // puddle and an ocean were shaded identically, because nothing
                // anywhere asked how deep the water was. Real water takes the
                // long wavelengths out first — red goes within a metre or two,
                // green survives further, blue further still — so shallow
                // water shows the sand almost as it is and deep water is a
                // colour of its own with nothing of the bed left in it.
                //
                // The thickness comes free: the depth of the bed was already
                // sampled a few lines up, as `behindDepth`, to decide whether
                // the refracted sample is really behind the surface, and the
                // depth of the surface is `here`. The difference between
                // them, in blocks, is how much water is in the way.
                // Straight down through the surface, not along the refracted
                // line. These differ by more than a nicety at a shore: the
                // shift is taken from the wave normal, a flat-topped water
                // block carries very nearly one normal across its whole face,
                // so an entire block's worth of fragments shift together and
                // either land on the bed or clear the shore altogether. When
                // they clear it they read the far bank or the sky, the water
                // is reported as hundreds of blocks thick, absorption
                // saturates, and shallow water over sand comes out the colour
                // of an ocean — in flat rectangles with block-straight edges,
                // because the whole face flipped at once.
                //
                // The refracted sample is still the right thing to *look*
                // through, and is kept for that. It is the wrong thing to
                // measure with: how much water is above this bed is a question
                // about the column under this pixel.
                float straightDepth = textureLod(sceneDepth, uv, 0.0).r;
                // Straight down, and now actually straight down.
                //
                // Both samples lie on one ray from the eye, so the bed is the
                // surface point scaled along that ray — and the drop between
                // them is the y of the difference, not its length. Taking the
                // difference of the two linearised depths instead measured
                // along the ray, which at a grazing angle is several times the
                // depth of the water: the same shallows read as an ocean when
                // looked at from across the pond and as a puddle from above,
                // and absorption and foam both ride on this number.
                float dHere = max(distanceOf(here), 1.0e-4);
                float dBed = distanceOf(straightDepth);
                float through = max(vRelative.y * (1.0 - dBed / dHere), 0.0);
                // Per block, and each channel its own. Not physical constants:
                // the sea in this game is a handful of blocks deep, so the real
                // ones would do nothing at all over that distance.
                vec3 absorb = exp(-through * WATER_ABSORB);
                behind = mix(WATER_DEEP * dot(behind, vec3(0.333)), behind, absorb);
                // Foam where the water is shallow enough that the bed is nearly
                // touching the surface, which along any shore is a band a
                // couple of blocks wide. Rides on the same number: there is no
                // test here for "is this the edge of the water", and there does
                // not need to be, because the edge is where the water is
                // thinnest.
                // Driven by the refraction setting rather than one of its
                // own: foam is a thing you see through the surface at the
                // shore, so it lives and dies with seeing through the surface
                // at all. `water.z` is not free — it carries the wave lattice.
                if (frame.lightShadow.w > 0.0) {
                    float shore = clamp(1.0 - through / FOAM_REACH, 0.0, 1.0);
                    // Squared to keep it a band at the edge rather than a haze
                    // over the whole shallows, and broken up by the same wave
                    // slope the caustics use, so it moves with the surface
                    // instead of lying on it like paint.
                    float ripple = 0.6 + 0.4 * clamp(length(normal.xz) * 6.0, 0.0, 1.0);
                    behind = mix(behind, vec3(1.0),
                            shore * shore * ripple * frame.lightShadow.w * FOAM_MAX);
                }
#endif
                // How far behind the surface this depth actually is. Trusted
                // fully once there is real water between the two — a lake bed
                // several blocks down — and faded out as that gap closes to
                // nothing. See CREATURE_LIKELY_DEPTH: a creature under the
                // surface and a shallow lake bed look identical to this pass,
                // and only the second one is actually sitting in `behind`.
                // Fading the forced opacity below over the gap, instead of
                // forcing it outright, lets the game's own blend show
                // whatever is really there the rest of the time.
                //
                // Not built into the tracing variant: it is one more sample
                // and a smoothstep on the one pass already found to have no
                // room left for exactly that kind of cost. There the old,
                // unconditional opacity stays — a creature under the surface
                // keeps being redrawn over there, same as before this fix.
                // See WHY_NOT_WITH_RAY_QUERY.
#ifndef RAY_QUERY
                float trustBehind = smoothstep(0.0, CREATURE_LIKELY_DEPTH,
                        distanceOf(behindDepth) - distanceOf(here));
#endif
                // Exactly what the blend would have done, done here: the frame
                // times what the water lets through, plus the water itself.
#ifdef RAY_QUERY
                shaded = shaded * alpha + behind * (1.0 - alpha);
                alpha = 1.0;
#else
                // Mixed in only as far as the sample is believed, which is the
                // same measure that decides the opacity two lines down.
                //
                // Both lines are about one thing — whether what the refraction
                // fetched is really the bed — and only one of them was asking.
                // The opacity was let down where something stands close under
                // the surface, so the game's own blend could show what is
                // really there; and then the colour underneath it was mixed
                // with the fetched sample anyway, unconditionally. That sample
                // comes from a copy of the world taken before the game draws a
                // single creature, so a villager standing in the shallows was
                // being painted a third of the way towards the sand it is
                // standing on, however far the opacity was let down for it.
                vec3 refracted = shaded * alpha + behind * (1.0 - alpha);
                shaded = mix(shaded, refracted, trustBehind);
                alpha = mix(alpha, 1.0, trustBehind);
#endif
            }
        }
        // Ice, which has carried a material tag since this pass was written
        // and never had a line of shading to go with it.
        //
        // The same Fresnel water uses and nothing else: no waves, because ice
        // does not ripple, and no ray, because a marched reflection on a
        // surface this small is a smear and the sky is what is above it
        // anyway. That leaves an effect that is cheap in the one branch of
        // this shader where cheap matters — the translucent pass is the
        // expensive one, and a sheet of ice is not a reason to make it more
        // so.
        //
        // Sky light gates it: ice in a cave is not lit by a sky it cannot see,
        // and vanilla's own lighting is the only thing here that knows the
        // difference.
        //
        // Not built into the tracing variant of this shader. See
        // WHY_NOT_WITH_RAY_QUERY.
#ifndef RAY_QUERY
        if (frame.surface.x > 0.0 && material == MATERIAL_ICE
                && frame.fogColor.a > 0.5) {
            float sheen = mix(ICE_SHEEN_FLOOR, 1.0, fresnel(normal))
                    * frame.surface.x * ICE_MIRROR_MAX * vLight.y;
            // The sky along the reflected ray, the way the water below does
            // it, rather than the one horizon colour for every direction.
            // Standing on a frozen lake reflects the zenith, and the zenith is
            // a third darker than the horizon it used to be given — which is
            // why looking down at ice used to change nothing that could be
            // measured, let alone seen.
            vec3 toIceEye = normalize(-vRelative);
            vec3 iceSky = skyAlong(reflect(-toIceEye, normal), fogRgb);
            shaded = mix(shaded, iceSky, sheen);
            // Where it turns into sky it stops being see-through, exactly as
            // the water above does — a mirror that lets the riverbed through
            // is a colour laid over the surface rather than the surface.
            alpha = mix(alpha, 1.0, sheen);
        }
#endif
        // frame.heightFog.w: how much of the Fresnel term to believe, 0 off.
        float water = frame.heightFog.w;
        // The view that paints water with what the ray found has to reach the
        // march whatever the two sliders in front of it are set to. Nested
        // inside them, "switch the view on and nothing changes" is the same
        // picture whether the ray found nothing or the march was never asked
        // to run — and on the preset this was last looked at on, screen
        // reflections are at zero, so it could only ever have been the second.
        bool mirrorView = frame.screenMirror.y > 0.5;
        if ((water > 0.0 || mirrorView) && material == MATERIAL_WATER) {
            float mirror = fresnel(mirrorNormal) * water;
            // Worked out before the march rather than inside it, because the
            // sky is the answer whether or not the march ever runs.
            vec3 toEye = normalize(-vRelative);
            vec3 ray = reflect(-toEye, mirrorNormal);
            // What the surface shows: the sky along that ray by default, and
            // whatever is actually standing there when the march finds it.
            vec3 mirrored = skyAlong(ray, fogRgb);
            // How much of what is being mixed in is really there, as against
            // being the sky colour standing in for it.
            float confidence = 0.0;
            // Only the top of the water reflects. The sides of a water block
            // are the walls of the channel it runs in, and a ray sent off one
            // of those travels along the surface rather than away from it —
            // which is where a good part of the smearing was coming from. The
            // waves have always known this; the mirror did not.
            if ((frame.screenMirror.x > 0.0 || mirrorView) && normal.y > 0.9) {
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
                if (mirrorView) {
                    shaded = found.a > 0.0 ? found.rgb : vec3(0.02, 0.02, 0.22);
                    outColor = vec4(shaded, 1.0);
                    return;
                }
            } else if (mirrorView) {
                // The side of a water block never reflects, and this view has
                // nothing to report there. Said in the same colour as a ray
                // that found nothing, because leaving the surface as ordinary
                // water would read as the view being broken rather than as
                // the answer.
                outColor = vec4(0.02, 0.02, 0.22, 1.0);
                return;
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
        // The sun, or the moon, on the surface itself.
        //
        // Not built into the tracing variant, and this one is measured rather
        // than assumed: the glint costs the traced translucent pipeline 7.7%
        // more code, which is more than every other effect on this pass put
        // together, and that pipeline is where the graphics device was lost.
        // Without tracing the same glint ran clean in every arm of the
        // experiment. See WHY_NOT_WITH_RAY_QUERY.
#ifndef RAY_QUERY
        float glintStrength = frame.lightInfo.y;
        if (glintStrength > 0.0
                && (material == MATERIAL_WATER || material == MATERIAL_ICE)) {
            // Day and night are the same highlight from opposite ends of the
            // same axis: the game hangs the moon exactly across the sky from
            // the sun, so one direction answers for both.
            bool byDay = frame.sun.y > 0.0;
            vec3 toLight = byDay ? frame.sun.xyz : -frame.sun.xyz;
            float above = byDay ? frame.sun.y : -frame.sun.y;
            // The full ripple for water, the flat face for ice.
            //
            // Not mirrorNormal, which is what this used at first and what made
            // it wrong on the screen: that normal is deliberately calmed
            // towards flat as the view flattens, because the Fresnel term is a
            // cliff near grazing and the reflection bands without it. A
            // highlight is not a reflection and wants the opposite. Calmed, the
            // sea answers the sun as one smooth mirror would — a single
            // straight column of white running to the horizon, hard-edged,
            // with no ripple in it anywhere. The scatter *is* the effect: a
            // glitter path is a great many separate facets each catching the
            // sun for a moment, and the facets are exactly what the calming
            // removes.
            //
            // Sky light gates it for the same reason it gates the ice sheen,
            // and this was found in a screenshot rather than reasoned about:
            // a sheet of ice in a sealed cave, no way out to the surface, with
            // the sun's streak lying across it. The sun cannot reach a surface
            // that receives no sky, and vanilla's own light map is the only
            // thing here that knows the difference.
            float g = material == MATERIAL_WATER
                    ? celestialGlint(normal, WATER_GLINT_SHARPNESS, toLight, above)
                    : celestialGlint(normal, ICE_GLINT_SHARPNESS, toLight, above);
            // The angles alone cannot tell a clear horizon from a sun sitting
            // behind a mountain — both return the same lobe. Checked only
            // here, after the lobe is known nonzero, so the march below runs
            // on the sliver of a lake that is actually glinting rather than
            // on every wet pixel on screen.
            g *= glintStrength * mix(GLINT_MAX_LDR, GLINT_MAX_HDR, frame.world.z)
                    * vLight.y * (byDay ? 1.0 : MOON_SHARE);
            if (g > 0.0) {
                shaded += (byDay ? SUN_TINT : MOON_TINT) * g;
                // Raised with it, because the frame is premultiplied below: a
                // glint added to a surface that lets most light through would
                // otherwise be scaled down by exactly the amount that makes it
                // worth having.
                alpha = mix(alpha, 1.0, clamp(g, 0.0, 1.0));
            }
        }
#endif
        // The material view, answered here rather than where the opaque pass
        // answers it. Everything above re-decides the colour of a water
        // surface — the mirror, the refraction, the glint — so the answer
        // written before them was painted over by exactly the surface this
        // view exists to name, and water was the one thing missing from a
        // picture of what the world is made of.
        if (frame.frameInfo.w > 0.5) {
            shaded = materialView;
            alpha = 1.0;
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
        //
        // And when it is not a light, the alpha carries how much of the sky
        // reaches this surface instead, in the lower half of the range that
        // nothing was using.
        //
        // This is what lets a shadow drawn over the finished frame behave like
        // the traced one. The traced shadow lowers sky light and leaves block
        // light alone, so a cave wall lit by a torch is not darkened by the
        // sun — the screen-space shadows could not do that, because by the
        // time the frame exists the two halves of the light have been
        // multiplied into one colour and nothing downstream can tell them
        // apart. The share is known exactly here and only here.
        //
        // Written after the traced shadow rather than before it, which also
        // settles the third disagreement: a surface the ray already put in
        // shadow arrives with its sky light lowered, so the screen-space
        // shadow has little left to take. The two stop compounding without
        // either of them being told about the other.
        //
        // A light keeps the old meaning and gives up the sky share, which
        // costs nothing worth having: a glowstone block is not a surface
        // anybody looks at for a shadow. The floor of 0.006 is the composite's
        // test for "is there terrain here" — a black sky share must not read
        // as no terrain.
        outColor = vec4(shaded, emits > 0.0
                ? 0.5 + 0.5 * emits * fogKeep
                : max(0.006, skyLight * 0.49));
    }
}
