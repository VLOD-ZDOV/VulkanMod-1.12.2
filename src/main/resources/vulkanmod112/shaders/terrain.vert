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
    // y = how far dynamic light should respect which way a surface faces.
    // z = 1 when the material buffer is bound and worth reading.
    // w = 1 to paint the world by material instead of by texture.
    vec4 frameInfo;
    // x = how much of the colour the low ground gives up, 0 turns it off.
    // y = how quickly it thickens with each block below the camera.
    // z = how many entries of materialSprites are in use.
    vec4 heightFog;
    // Pairs: a rectangle of the block atlas, then the material it stands for
    // in .x. Only the fragment stage reads them; they are declared here because
    // both stages must see the same block.
    vec4 materialSprites[16];
    // x = wave strength on water, read by the fragment stage.
    // yz = the camera's own world x and z reduced modulo the lattice below.
    // w = how far the top of a plant leans in the wind, 0 turns it off.
    vec4 water;
} frame;

// What actually differs between draws.
layout(push_constant) uniform Draw {
    vec4 params; // x = alpha cutoff
} draw;

layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUV;
layout(location = 3) in vec2 inLight;
// What this vertex is made of: 0 plain, 1 water, 2 foliage, 3 glass, 4 lava.
// Its own buffer, one byte a vertex, because the game's vertex is 28 bytes
// mirrored unchanged and there is nowhere in it to put this. Meaningless
// unless frame.frameInfo.z says the buffer is really there.
layout(location = 4) in uint inMaterial;

layout(set = 0, binding = 2, std430) readonly buffer ChunkOffsets { vec4 origins[]; } chunkOffsets;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vUV;
layout(location = 2) out vec2 vLight;
layout(location = 3) out float vDistance;
layout(location = 4) out vec3 vRelative;
// flat: a material is a fact about the surface, not a value to blend across
// it. Interpolating between water and glass would produce something that is
// neither, on the one triangle where they meet.
layout(location = 5) flat out uint vMaterial;

const uint MATERIAL_PLANT = 6u;
const uint MATERIAL_PLANT_TALL_LOWER = 7u;
const uint MATERIAL_PLANT_TALL_UPPER = 8u;
const uint MATERIAL_LEAVES = 9u;

/**
 * How far a leaf block drifts, against how far a blade of grass leans.
 *
 * Much less, and not for taste: a leaf cube moves as a whole while its
 * neighbours move by their own reading of the same wave, so whatever they
 * differ by is a seam opening between them. Small amplitude against a long
 * wavelength keeps that difference under a pixel, and a canopy that breathes
 * is the whole of what this is for — a forest that visibly sways is a forest
 * with holes in it.
 */
const float LEAF_REACH = 0.035;

// The same lattice terrain.frag builds its waves on, for the same reason: the
// phase has to come from a world position, single precision cannot hold one at
// Minecraft's range, and a phase is periodic — so the camera's position arrives
// already reduced modulo this, and every term below repeats over it exactly.
const float SWAY_LATTICE = 16.0;
const float SWAY_K = 6.2831853 / SWAY_LATTICE;
// How far the top of a plant may lean, in blocks, at full strength. A tuft of
// grass is about a foot across; this is deliberately less than that, because
// what reads as wind is that the field moves together, not that any one blade
// travels far.
const float SWAY_REACH = 0.11;

/**
 * Which way the wind has bent the plants at this spot, at most one on each axis.
 *
 * Four sines rather than one, at unrelated angles and speeds, so the field
 * moves in gusts crossing each other instead of everything in sight leaning the
 * same way at the same moment. No random phase per plant: neighbours moving
 * independently reads as noise, and what a field of grass in wind actually does
 * is bend in waves that travel across it.
 */
vec2 swayOffset(vec2 p, float t) {
    vec2 o = vec2(sin(SWAY_K * (p.x + 2.0 * p.y) + 1.6 * t),
                  sin(SWAY_K * (2.0 * p.x - p.y) + 1.9 * t));
    o += 0.45 * vec2(sin(SWAY_K * 3.0 * p.x + 2.7 * t),
                     sin(SWAY_K * 3.0 * p.y + 2.3 * t));
    return o * (1.0 / 1.45);
}

void main() {
    // Every indirect command has exactly one instance; firstInstance is the
    // index of this chunk's camera-relative origin in the storage buffer.
    // That origin is already relative to the camera, so the sum below is the
    // position in eye space and its length is the distance fog needs.
    vec4 chunk = chunkOffsets.origins[gl_InstanceIndex];
    vec3 relative = inPos + chunk.xyz;
    // Wind, and it moves the vertex rather than pretending in the shading.
    //
    // Only the top of a plant may travel: the bottom is in the ground. There is
    // nothing in the vertex that says which is which — a cross model spans a
    // whole block, so its top and its bottom are both at whole numbers — but
    // there is no need to store it. The game builds every quad's four corners
    // in one fixed order, and its own table of them (EnumFaceDirection) gives
    // the same answer for all four vertical faces: corners 0 and 3 are the top
    // pair. So the marker is the corner number, which costs nothing to know.
    //
    // gl_VertexIndex has the draw's vertexOffset added in and a chunk does not
    // have to begin on a quad boundary, so where this chunk's own count starts
    // is handed over in the origin's spare fourth float.
    if (frame.water.w > 0.0 && frame.frameInfo.z > 0.5) {
        if (inMaterial == MATERIAL_LEAVES) {
            // Read at the centre of the block rather than at the vertex, so
            // every corner of the cube takes the same offset and the cube
            // stays a cube. The field is already world-aligned — the camera's
            // own place on the lattice is added into it — so flooring it lands
            // on block boundaries and not on some offset of the camera.
            vec2 field = relative.xz + frame.water.yz;
            relative.xz += swayOffset(floor(field) + 0.5, frame.frameInfo.x)
                    * (LEAF_REACH * frame.water.w);
        } else if (inMaterial == MATERIAL_PLANT
                || inMaterial == MATERIAL_PLANT_TALL_LOWER
                || inMaterial == MATERIAL_PLANT_TALL_UPPER) {
            int corner = (gl_VertexIndex - int(chunk.w)) & 3;
            bool top = corner == 0 || corner == 3;
            // How far up the stem this vertex is, in half-block steps. A plant
            // one block high leans one step at its head and none at its foot;
            // a plant two blocks high carries on from where its lower half
            // ended, so the seam is one place moving one amount rather than
            // two edges pulling apart.
            float along = inMaterial == MATERIAL_PLANT_TALL_UPPER
                    ? (top ? 2.0 : 1.0)
                    : (top ? 1.0 : 0.0);
            if (along > 0.0) {
                vec2 field = relative.xz + frame.water.yz;
                relative.xz += swayOffset(field, frame.frameInfo.x)
                        * (SWAY_REACH * frame.water.w * along);
            }
        }
    }
    gl_Position = frame.mvp * vec4(relative, 1.0);
    vColor = inColor;
    vUV = inUV;
    vLight = (inLight + 8.0) / 256.0;
    vDistance = length(relative);
    // Camera-relative world position, which is what anything positional in the
    // fragment stage needs and what the fog distance is already derived from.
    vRelative = relative;
    vMaterial = frame.frameInfo.z > 0.5 ? inMaterial : 0u;
}
