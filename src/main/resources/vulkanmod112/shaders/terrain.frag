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
    // x = wave strength (0 = off), yz = the camera's own world x and z reduced
    // modulo the wave lattice. See waveGradient for what that is for.
    vec4 water;
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
    if (material == MATERIAL_FOLIAGE || material == MATERIAL_PLANT) {
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
    uint material = vMaterial;
    if (BLEND && material == MATERIAL_PLAIN) {
        material = spriteMaterial(vUV);
    }
    bool foliage = material == MATERIAL_FOLIAGE || material == MATERIAL_PLANT;
    // Both of these come from the frame's uniform buffer, so every fragment in
    // the draw takes the same branch — which is what makes it safe to ask for
    // derivatives inside it.
    float directional = frame.frameInfo.y;
    // BLEND is a compiled-in constant, so adding it here keeps the branch the
    // same for every fragment of the draw, which is what makes the derivatives
    // inside it legal. The translucent pipeline always needs the normal: water
    // is in it, and water is asked which way it faces even in the dark.
    vec3 normal = (BLEND || (lightCount > 0 && directional > 0.0))
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
    vec3 shaded = tex.rgb * vColor.rgb * light * waveShade;
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
        // frame.heightFog.w: how much of the Fresnel term to believe, 0 off.
        float water = frame.heightFog.w;
        if (water > 0.0 && material == MATERIAL_WATER) {
            float mirror = fresnel(mirrorNormal) * water;
            // Both together, because they are the same fact: where the surface
            // turns into a mirror it stops showing what is under it, and a
            // reflection that let the riverbed through would be a colour laid
            // over water rather than water behaving like water.
            shaded = mix(shaded, frame.fogColor.rgb, mirror);
            alpha = mix(alpha, 1.0, mirror);
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
        // What separates them is here and nowhere later: block light, which is
        // the game's own answer to "is this lit from outside or is it the
        // source", and the texture's own colour before anything shaded it.
        // A torch is bright and at block light 14; the stone it stands on is
        // at 13 and grey, and grey is what rules it out.
        float lit = clamp((vLight.x - 0.78) / 0.19, 0.0, 1.0);
        vec3 own = tex.rgb * vColor.rgb;
        float bright = clamp((max(max(own.r, own.g), own.b) - 0.5) * 2.0, 0.0, 1.0);
        outColor = vec4(shaded, 0.5 + 0.5 * lit * bright);
    }
}
