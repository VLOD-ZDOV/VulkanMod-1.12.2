package net.vulkanmod112.client;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Small client-only configuration shared by the settings screen and hooks.
 *
 * Every default here is the conservative choice: the settings that trade
 * safety for speed start off, so a fresh install behaves the same on every
 * driver. The presets in the settings screen are how you opt into the rest.
 */
public final class VulkanConfig {

    private static final String CATEGORY_GENERAL = "general";
    private static final String CATEGORY_OPTIMIZATION = "optimization";
    private static final String CATEGORY_ADVANCED = "advanced";

    // Defaults, named so "reset" and "what shipped" cannot drift apart.
    static final boolean DEF_TERRAIN = true;
    static final boolean DEF_OVERLAY = false;
    static final int DEF_ENTITY_DISTANCE = 0;
    static final int DEF_TILE_ENTITY_DISTANCE = 0;
    static final boolean DEF_ANIMATIONS = true;
    static final int DEF_BACKGROUND_FPS = 10;
    static final boolean DEF_ULTRA_LOG = false;
    static final int DEF_ULTRA_LOG_SECONDS = 10;
    static final boolean DEF_DEPTH_BLIT = true;
    static final boolean DEF_CULLING = true;
    static final int DEF_GEOMETRY_BUDGET = 0;
    static final int DEF_FRAMES_IN_FLIGHT = 2;
    /**
     * Off by default. Measured at render distance 64: 330 fps without it,
     * 120-140 with. Filling the world in costs continuous chunk building, and
     * that is not a price to charge anyone who did not ask for it.
     */
    static final boolean DEF_CHUNK_PRELOAD = false;
    /**
     * 0 = leave vanilla's own count alone.
     *
     * Vanilla derives the number of chunk-building threads from the heap
     * rather than from the CPU, and takes whichever is smaller. On a 4 GiB
     * heap that ceiling lands at 24 threads no matter how many cores the
     * machine has, so a large CPU sits partly idle while chunk building is
     * measurably what the frame is waiting for.
     */
    static final int DEF_CHUNK_BUILD_THREADS = 0;
    /**
     * Near clipping plane in hundredths of a block; 0 keeps vanilla's 0.05.
     *
     * Depth precision at a distance z is roughly {@code z² / (near · 2²⁴)} with
     * the 24-bit buffer the game uses. At vanilla's 0.05 that is 0.107 blocks
     * three hundred out — wider than the 0.125 a snow layer sits above what it
     * covers, which is why distant snow speckles with the block underneath.
     *
     * 0.1 rather than the 0.2 first shipped. The plane clips whatever is nearer
     * to the eye than itself, and at 0.2 standing against a wall could open a
     * view straight through it. Halving it halves that reach while leaving the
     * resolvable gap at 0.054 blocks, still less than half of the 0.125 the
     * ripple needs — the fix keeps its margin and the side effect does not.
     */
    static final int DEF_NEAR_PLANE_HUNDREDTHS = 10;
    /**
     * Memoise the seed of the visibility walk. On by default: the key is exact
     * (camera block position plus the identity of that section's CompiledChunk,
     * which the game replaces on every rebuild), so the cached answer is the
     * answer vanilla would have computed. The switch exists to rule it out if
     * something ever looks wrong, not because it is a trade.
     */
    static final boolean DEF_VISIBILITY_SEED_CACHE = true;
    /**
     * Replace the game's visibility flood fill with our own. On by default,
     * and it was not always: this is a rewrite of vanilla logic rather than of
     * our renderer, and the way it fails is by quietly not drawing something,
     * so it shipped off until a season of that not happening.
     *
     * The game's own profiler puts that flood fill at a quarter to a half of the
     * frame at render distance 64, against 4.5% for drawing the world. Running
     * it less often was measured and does not pay — 97% of the requests come
     * from the camera moving, which is exactly when the answer has changed. What
     * is left is to do the same work without allocating for it.
     */
    static final boolean DEF_OWN_VISIBILITY_WALK = true;
    /**
     * Hand the two passes inside {@code renderEntities} only the sections that
     * can hold something, instead of every section on screen.
     *
     * On by default: the set of creatures the game ends up drawing is the same
     * one by construction — a section reaches it only because a creature named
     * it and the search had it on screen — and the pass is worth about 1.1 ms
     * of a 2.6 ms frame at thirty-two chunks with five creatures in sight. It
     * is a switch rather than a certainty because the failure it could have is
     * a creature that quietly stops being drawn, and a switch is what tells
     * that apart from a creature that walked away.
     */
    static final boolean DEF_SHORT_ENTITY_SECTIONS = true;
    /**
     * Give the game's own layer filter the sections that hold blocks, rather
     * than every section on screen.
     *
     * Off by default while it is new, and the reason is the failure it would
     * have: a section missing from that list is a chunk that stops being drawn,
     * which looks exactly like terrain that has not finished building. The list
     * is exact by construction and checked against the full scan under a build
     * flag, but "off first, on once it has been flown" is the rule this project
     * already follows for anything that decides what gets drawn.
     */
    static final boolean DEF_SHORT_LAYER_SECTIONS = false;
    /**
     * Pack a chunk vertex into sixteen bytes instead of mirroring vanilla's
     * twenty-eight unchanged.
     *
     * Off by default and read only when the game starts: the layout decides the
     * shader that is loaded and the stride every offset in the geometry buffer
     * is measured in, and a buffer holding both layouts at once draws the world
     * as spikes reaching to the horizon. That is not a hypothetical — it is
     * what the first build of this did, because it settled the question a few
     * seconds after the sky had already been mirrored.
     */
    static final boolean DEF_COMPACT_VERTICES = false;
    /**
     * Shortlist the chunks the rebuild pass at the end of {@code setupTerrain}
     * can act on, instead of letting it scan every visible chunk.
     *
     * Off by default while it is new: like the visibility search, this replaces
     * vanilla logic rather than this mod's renderer, and the way it would fail
     * is that a chunk somebody dug into never gets queued for rebuilding and
     * stays stale on screen. The flag it reads is mirrored into a flat bitset,
     * and every path that can change it is hooked, so a missed transition would
     * have to come from somewhere that cannot write the field at all.
     */
    static final boolean DEF_FAST_REBUILD_NEAR = false;
    /**
     * Record what each stretch of a chunk's geometry is made of while the chunk
     * is being built.
     *
     * Off by default, and on its own it changes nothing on screen: this is the
     * groundwork every remaining effect needs and has none of them yet. Water
     * cannot be told from stained glass, nor grass from torches, because
     * vanilla's four layers are not materials and the vertex carries nothing
     * else. The one place the answer exists is the chunk rebuild loop, and it
     * exists there for the length of one call.
     *
     * What it costs is a branch and a counter read per block rendered, on the
     * build threads rather than the render thread. The diagnostics report says
     * what that measured, which is the point of shipping it switchable and
     * silent before anything depends on it.
     */
    static final boolean DEF_MATERIAL_TAGS = false;
    static final boolean DEF_SMART_ANIMATIONS = false;
    /**
     * Paint the terrain by what it is made of instead of by its texture.
     *
     * A diagnostic, off by default. The material of a vertex is decided on the
     * game side, carried to the GPU in a buffer of its own and read back in the
     * shader, and every step of that is invisible when it works and just as
     * invisible when it is off by a chunk. This makes the answer something you
     * can look at: water blue, foliage green, glass yellow, lava orange,
     * everything else grey.
     */
    static final boolean DEF_SHOW_MATERIALS = false;
    /**
     * Queue a chunk that changed near the camera instead of rebuilding it on
     * the render thread.
     *
     * This is where the rebuild pass actually spends its time. The scan around
     * it measures 0.02 to 0.07 ms a frame; the synchronous builds measure 0.4
     * to 0.7 ms a frame in the windows where they happen, and they are 89% to
     * 97% of the section when they do. Forge has the same switch, so the
     * behaviour is known ground; the cost is that a chunk you just changed
     * appears a frame or two late instead of at once.
     */
    static final boolean DEF_BUILD_NEAR_OFF_THREAD = false;
    /**
     * Test boxes against the frustum by their far corner. On by default: the
     * answer is the same one vanilla computes, by the same arithmetic, for
     * strictly fewer corners — this is not a trade between speed and accuracy,
     * and the switch is here to rule it out rather than to choose.
     */
    static final boolean DEF_FAST_FRUSTUM_TEST = true;
    /**
     * Draw water and glass in Vulkan instead of leaving them to OpenGL.
     *
     * On, once water, glass and mobs seen through them were checked by eye and
     * the layer was confirmed to reach this renderer at all — 201 snapshots
     * where vanilla drew none of it, no refusals, no Vulkan errors.
     *
     * Measured, it is not a speed setting: the layer costs 2.6% of a frame
     * either way, and recording the extra pass adds about 0.15 ms of CPU. What
     * it buys is that the terrain finally has fog on all of it, and that the
     * vanilla chunk buffers stop being needed for anything, which is what D6
     * waits on.
     */
    static final boolean DEF_VULKAN_TRANSLUCENT = true;
    /** Show a frame-time graph in the corner of the screen. */
    static final boolean DEF_FRAME_GRAPH = false;
    /** How often the graph's numbers are recomputed, in milliseconds. */
    static final int DEF_FRAME_GRAPH_INTERVAL = 1000;
    /**
     * Light from carried torches and burning entities, added while the terrain
     * is shaded instead of written into the world.
     *
     * Off by default, like every setting here that changes what the world looks
     * like. It costs no chunk rebuilds — which is the whole reason it is done
     * this way — but it is arithmetic per fragment per source, and it lights
     * only what this renderer draws.
     */
    static final boolean DEF_DYNAMIC_LIGHTS = false;
    /**
     * How far a light source may be and still be drawn, in blocks. Not how far
     * its light reaches — that is set by the source's own level, and a torch
     * lights about fifteen blocks around itself whatever this says. What this
     * decides is whether a distant torch's pool of light appears at all, and a
     * pool of light on the ground is visible from any distance you can see the
     * ground from. An early version culled at 24 blocks on the reasoning that a
     * level-15 light reaches 15, and the result was lights winking out as you
     * flew away from them while the torch itself stayed in plain view.
     */
    static final int DEF_DYNAMIC_LIGHT_DISTANCE = 160;
    /**
     * Stop filling the game's own chunk buffers once Vulkan holds the geometry.
     *
     * The world is currently stored twice in video memory — once in the game's
     * OpenGL buffers and once in the mirror this renderer draws from — and this
     * is what removes the first copy. It also takes the second of the two
     * uploads out of the per-frame budget vanilla reserves for them, which is
     * the one place chunk loading is actually gated.
     *
     * On by default, and it was off before for a reason that has since been
     * removed rather than out of caution. Every failure path in this mod ends
     * in "fall back to vanilla rendering", which works only while the vanilla
     * buffers still hold the world; with them empty the fallback has to rebuild
     * the entire grid first. That is still true — it is the price of this
     * setting — and what changed is that the second condition was met. Until
     * the translucent layer went through Vulkan on cards without sampleable
     * 24-bit depth, dropping these buffers on every AMD machine left water and
     * glass with nobody drawing them at all.
     *
     * What it saves is one whole copy of the world, the largest single line in
     * what this renderer asks of the card. The measurement is in the roadmap.
     */
    static final boolean DEF_DROP_VANILLA_BUFFERS = true;
    /**
     * Draw particles with Vulkan instead of OpenGL.
     *
     * The vertices are the game's own, built by the game's own code; what
     * changes is that they go into a buffer the card owns rather than through a
     * client-side vertex array, which is the slowest path OpenGL has and the
     * one this game has always used for every particle in the world.
     *
     * On by default, and it is not a visual setting — a particle looks the same
     * either way. It costs nothing extra to hand over: the quads join the
     * translucent Vulkan pass, which already exists, already has the game's
     * depth, and already ends in a composite.
     */
    static final boolean DEF_VULKAN_PARTICLES = true;
    /** The same, for rain and snow. Kept apart so one can be ruled out alone. */
    static final boolean DEF_VULKAN_WEATHER = true;
    /**
     * Which GPU Vulkan renders on. -1 lets the mod choose.
     *
     * Automatic is not "the fastest card" — it is "the card OpenGL is already
     * on", because sharing memory between two devices is not slow, it is
     * impossible, and the game's context was placed by the driver long before
     * this mod loaded. On a hybrid laptop that is the difference between the
     * terrain going through Vulkan and it refusing outright.
     *
     * A number rather than a name: the list is only known once Vulkan has
     * started, and the names are in the log and the diagnostics report beside
     * their numbers.
     */
    static final int DEF_VULKAN_DEVICE = -1;
    /**
     * Build acceleration structures over the terrain, so that rays can be
     * traced against it.
     *
     * Off by default and nothing reads them yet. That is the point of this
     * stage: the obstacle to ray tracing in this game has always been that the
     * structure has to be rebuilt whenever a chunk is, and rebuilding chunks is
     * already the largest cost in a moving frame — a claim that has never been
     * a number. This makes it one. What it draws is exactly what it drew
     * before; what it adds is a line in the diagnostics saying how many
     * structures exist, what they cost in memory, and how long a frame's builds
     * take to record.
     *
     * Needs Vulkan 1.2 and the acceleration-structure extension. Anything
     * missing is reported and the setting does nothing.
     */
    static final boolean DEF_RAY_TRACING = false;
    /**
     * Read the geometry and pose of every entity part the game draws, without
     * drawing any of it.
     *
     * The first step of taking entities into Vulkan, and it takes nothing:
     * entities are the part of the game other mods hook hardest, so what
     * matters before anything is replaced is how much of a real scene arrives
     * through the ordinary model path at all, and what asking OpenGL for a
     * pose per part costs. Both are numbers nothing in the source can supply.
     */
    static final boolean DEF_ENTITY_CAPTURE = false;
    /**
     * How dark the sun's own shadow is, in percent. 0 turns it off.
     *
     * Traced against the terrain structures, so it needs those and a card that
     * can trace from a fragment shader. Off by default because it is new and
     * because it costs a ray per lit pixel.
     *
     * The shadow moves the surface down the sky-light axis rather than
     * multiplying over the finished colour. This game has no sun in its
     * lighting — a surface is lit by how much block light and sky light reach
     * it — and a multiply would darken a torch-lit cave the sky never touched.
     */
    static final int DEF_SUN_SHADOWS = 0;
    /**
     * How soft the edge of a shadow is, in percent.
     *
     * Not a physical quantity. One ray gives one answer per pixel, so a shadow
     * made of one ray has an edge that follows the pixel grid; spreading that
     * ray over a disc instead trades the staircase for a dithered band. This
     * chooses how wide the band is, and it costs nothing either way — the ray
     * count does not change.
     */
    static final int DEF_SHADOW_SOFTNESS = 35;
    /**
     * How far from the camera the terrain carries structures a ray can hit, in
     * blocks.
     *
     * The one number that decides both what a shadow can be cast by and what
     * the whole feature costs in memory and build time. Past it there is
     * nothing to hit, so shadows fade out over the last quarter rather than
     * ending at a circle drawn around the player.
     */
    static final int DEF_RAY_TRACING_RADIUS = 96;
    /**
     * How many moving lights a surface may ask whether anything is in the way.
     *
     * The lights this mod adds — a carried torch, a burning creature, a dropped
     * glowing block — are a straight line from the source with a falloff,
     * because nothing in this game's lighting knows what stands between two
     * points. That is why a torch lights the far side of a wall. With
     * structures to trace against, the question can finally be asked; this is
     * how many times per surface it may be, because it is one ray each.
     */
    static final int DEF_TRACED_LIGHTS = 2;
    /**
     * How much of vanilla's own block light to give up in favour of traced
     * light, in percent. 0 changes nothing.
     *
     * Vanilla's block light is a flood fill through air: correct around
     * corners, and completely flat, because it is a number per block with no
     * idea where the light came from. A torch on one wall lights a room exactly
     * as a torch on the other does. Replacing it with light traced from the
     * blocks that emit it turns that number back into a direction and a shadow.
     *
     * What it costs is honesty about range: only the sources near the camera
     * are known, and only the strongest thirty-two of those fit. Turned up
     * fully, a cave lit from beyond that range goes dark. That is the trade,
     * and it is why this is a slider and not a switch.
     */
    static final int DEF_TRACED_BLOCK_LIGHT = 0;
    /** How far around the camera light-emitting blocks are looked for. */
    static final int DEF_BLOCK_LIGHT_RADIUS = 24;
    /** How soft the edge of a shadow cast by a torch is, apart from the sun's. */
    static final int DEF_LIGHT_SOFTNESS = 30;
    /**
     * How much of a pixel's history it keeps between frames.
     *
     * On by default, and it costs nothing where nothing is traced: the pass is
     * skipped entirely unless a ray is being cast, because there is no grain to
     * average away and averaging a clean picture only risks smearing it.
     */
    static final int DEF_TEMPORAL_ACCUMULATION = 60;
    /** Diagnostic: paint how much history each pixel is keeping. */
    static final boolean DEF_SHOW_ACCUMULATION = false;
    /**
     * Draw creatures through Vulkan instead of letting the game draw them.
     *
     * On. It was off while it was the newest and largest thing this renderer
     * takes over, and it is still the place mods reach into most and the only
     * one that replaces vanilla's drawing rather than adding to it — but the
     * split path turned out to cost more than the takeover does. With the game
     * drawing creatures, the player reaches the shadow passes twice: once as
     * captured geometry and once as something vanilla drew, and two shadows
     * over one another leave a rim that reads as a trail of light following
     * whoever is flying. Handing the drawing over removes the second of the
     * two, and the trail with it.
     */
    static final boolean DEF_VULKAN_ENTITIES = true;
    /** A round, warm sun instead of vanilla's square one. */
    static final boolean DEF_ROUND_SUN = false;
    /** A round moon with real phases instead of vanilla's sheet. */
    static final boolean DEF_ROUND_MOON = false;
    /** How much of its cell the moon disc fills. */
    static final int DEF_MOON_SIZE = 40;
    /** How much water bends what is seen through it. */
    static final int DEF_WATER_REFRACTION = 0;
    /** The sun's or moon's own highlight on water and ice. */
    static final int DEF_CELESTIAL_GLINT = 0;
    /** How much sky ice gathers on its surface. */
    static final int DEF_ICE_SHINE = 0;
    /** How much light gathers into bands on a shallow bed. */
    static final int DEF_WATER_CAUSTICS = 0;
    /** How much rain makes an upward face gather the sky. */
    static final int DEF_WET_SURFACES = 0;
    /** How much fog warms towards the sun and cools away from it. */
    static final int DEF_SUN_HAZE = 0;
    static final int DEF_SKY_GRADIENT = 0;
    static final boolean DEF_SCENE_OCCLUSION = false;
    static final int DEF_LEAF_SHADOWS = 0;
    /** How dark a short shadow towards the sun, over the finished picture. */
    static final int DEF_CONTACT_SHADOWS = 0;
    /** How much a creature's own faces shade themselves against the sun. */
    static final int DEF_CREATURE_LIGHT = 0;
    static final boolean DEF_SHOW_CREATURE_LIGHT = false;
    /**
     * Ask once a session whether a newer build exists.
     *
     * On, unlike the effects, and for a different kind of reason: this one does
     * not trade anything away, and the cost of it being off is that somebody
     * plays a version whose bugs were fixed a month ago and reports them again.
     */
    static final boolean DEF_UPDATE_CHECK = true;
    /** How dark the shadow of the game's own clouds may go. */
    static final int DEF_CLOUD_SHADOWS = 0;
    /** How bright the shafts of light from the sun may be. */
    static final int DEF_GOD_RAYS = 0;
    /** Whether the game's frame is asked for with room above white in it. */
    static final boolean DEF_HDR_FRAME = false;
    /** Middle of the slider is no change; it only means anything with HDR on. */
    static final int DEF_EXPOSURE = 50;
    /** How much of the sky's colour the clouds take. */
    static final int DEF_CLOUD_TINT = 0;
    /** How many chunks the offscreen preloader keeps queued. */
    static final int DEF_PRELOAD_QUEUE = 16;
    /** How much of the chunk grid the preloader looks at per frame. */
    static final int DEF_PRELOAD_SCAN = 4096;
    /** Which shader pack to borrow sky pictures from; empty is none. */
    static final String DEF_SKIN_PACK = "";
    /** How much of its square the disc fills, as a percentage of the range. */
    static final int DEF_SUN_SIZE = 50;
    static final int DEF_SCENE_TONE = 0;
    static final int DEF_SCENE_WARMTH = 50;
    static final int DEF_FRAME_GRAPH_CORNER = 0;
    static final boolean DEF_CACHE_BLOCK_ENTITY_MODELS = true;
    static final int DEF_EXPLOSION_PARTICLES = 0;
    static final int DEF_TIME_CONTROL = 0;
    static final int DEF_TIME_OF_DAY = 12;
    static final int DEF_WEATHER_CONTROL = 0;
    /** How warm the rim goes, 0 = white. */
    static final int DEF_SUN_WARMTH = 60;
    /**
     * Let the render-distance slider go past 64, up to 128.
     *
     * Off by default because what it unlocks is not "more of the same". The
     * game allocates a render chunk for every cell of a
     * {@code (2d+1) x (2d+1) x 16} grid before anything is drawn: 266 256 of
     * them at 64, and 1 056 784 at 128 — four times the objects, four times the
     * heap, and four times the OpenGL buffer names, all of it up front and
     * whether or not the world out there is loaded. The chunks themselves have
     * to come from somewhere too, and a server decides how far it will send
     * them; past that limit the extra grid is paid for and empty.
     */
    static final boolean DEF_EXTREME_RENDER_DISTANCE = false;
    /**
     * How far dynamic light goes towards caring which way a surface is turned,
     * in percent.
     *
     * Half by default, which is a measured choice rather than a shrug. A light
     * the player carries drags its own terminator across every nearby surface
     * whenever they move, and nothing else in this game does that, so at full
     * strength a jump reads as the world blinking rather than as a lamp being
     * lifted. Tested down to a sixth, where the effect is gone entirely; half
     * keeps the part worth having and lands the swing where it stops drawing
     * the eye. Vanilla's light is a number per block with no idea of
     * orientation, so a dropped torch lit the underside of the floor it was
     * lying on exactly as brightly as the top of it. This renderer can work the
     * face normal out from how the world position changes across the screen —
     * every quad in a block model is flat, so that is the exact normal rather
     * than an approximation — and dim a face that is turned away. It costs a
     * cross product per lit fragment and nothing at all when dynamic lights are
     * off.
     *
     * A percentage rather than a switch because the right amount is a matter of
     * taste against a game whose own shading is a fixed number per face
     * direction, and because the difference between the two ends is a thing you
     * judge by standing in a lit room rather than by reading about it.
     */
    static final int DEF_DIRECTIONAL_LIGHT = 50;
    /**
     * How much colour the ground below the camera gives up to fog, in percent.
     *
     * Off by default, and it is a look rather than a fix. The colour it fades
     * towards is the game's own fog colour, and it only applies where the game
     * already has fog, so it cannot invent a haze the sky disagrees with. What
     * it cannot do is reach the entities and particles vanilla draws: they are
     * fogged by OpenGL's own fixed-function fog, which knows nothing about
     * height, so a mob standing in a fogged valley stays clearer than the
     * ground it is on.
     */
    static final int DEF_HEIGHT_FOG = 0;
    /**
     * The drop below the camera, in blocks, over which the fog reaches nearly
     * all of the strength above.
     *
     * Twenty-four blocks is about the floor of a ravine seen from its lip, and
     * that was the shape this started with. It is the other half of the
     * setting: the strength says how much colour the low ground gives up in the
     * end, and this says how far down you have to look before it does. A lower
     * number is a valley that turns to haze a few blocks under your feet.
     */
    static final int DEF_HEIGHT_FOG_DEPTH = 24;
    /**
     * How far the game's own distance fog reaches, as a percentage of vanilla.
     *
     * A hundred is untouched. Above it the world stays clear further out, which
     * is what anybody raising their render distance actually wanted — vanilla
     * ties the fog to the distance, so twice the chunks arrive wrapped in twice
     * the haze and look no further away than before.
     */
    static final int DEF_FOG_DISTANCE = 100;
    /**
     * How much of a water surface turns into a reflection of the sky as you
     * look along it, in percent.
     *
     * Off by default. Looking straight down into water you see the bottom;
     * looking along it you see the horizon, and the change between the two is
     * steep and happens near the end. What it reflects is the game's own fog
     * colour, which is what the horizon actually is, so it follows sunrise,
     * weather and being underwater without being told about any of them.
     * Needs the translucent layer drawn in Vulkan; OpenGL's copy of the water
     * knows nothing about this.
     */
    static final int DEF_WATER_REFLECTION = 0;
    /**
     * How much the water surface is tilted by a moving wave pattern.
     *
     * Off by default. Nothing is displaced — the water stays exactly where the
     * game put it and a boat floats where it always did; what moves is which
     * way the surface is treated as facing, so the reflection breaks up along
     * the crests and light scatters across it. Needs the translucent layer
     * drawn in Vulkan.
     */
    static final int DEF_WATER_WAVES = 0;
    /**
     * How far the top of a plant leans in the wind.
     *
     * Off by default. Moves the vertex rather than faking it in the shading,
     * and only the top pair of corners of each quad, so the plant stays rooted.
     * Needs Material Tags: nothing else can tell grass from a torch, and they
     * share a layer.
     */
    static final int DEF_FOLIAGE_SWAY = 0;
    /**
     * How much light spills off a glowing surface into the pixels around it.
     *
     * Off by default. Terrain only: this mod's frame is composited into the
     * game's before the game draws entities or particles, so a torch glows and
     * a burning creature does not.
     */
    static final int DEF_BLOOM = 0;
    /**
     * How much a point is darkened by how little of its surroundings it can see.
     *
     * Off by default. The game shades a block face by which way it points and
     * by nothing else, so an inside corner is lit exactly like an open wall.
     * Worked out from the depth buffer this renderer already has, so it costs
     * no geometry and no second pass over the world.
     */
    static final int DEF_SCREEN_REFLECTIONS = 0;
    static final int DEF_AMBIENT_OCCLUSION = 0;
    /**
     * How far a corner's shadow reaches, in blocks. Two is a little under the
     * height of a doorway, which is the scale the eye reads a room's corners at;
     * a smaller reach draws a line along the seam rather than a shadow.
     */
    static final int DEF_AO_RADIUS = 2;
    static final boolean DEF_SHOW_OCCLUSION = false;
    static final boolean DEF_SHOW_MOTION = false;
    static final boolean DEF_MOTION_OVER_WORLD = false;
    static final boolean DEF_SHOW_REFLECTIONS = false;
    static final boolean DEF_FOG = true;
    static final boolean DEF_ZOOM = true;
    /** Stored as an integer so it fits the config and the slider; 4 = quarter FOV. */
    static final int DEF_ZOOM_FACTOR = 4;

    private static Configuration config;
    private static volatile boolean terrainEnabled = DEF_TERRAIN;
    private static boolean overlayEnabled = DEF_OVERLAY;
    /** 0 = leave vanilla's own limit alone. */
    private static int entityDistance = DEF_ENTITY_DISTANCE;
    private static int tileEntityDistance = DEF_TILE_ENTITY_DISTANCE;
    private static volatile boolean animationsEnabled = DEF_ANIMATIONS;
    private static int backgroundFpsLimit = DEF_BACKGROUND_FPS;
    private static boolean ultraLogEnabled = DEF_ULTRA_LOG;
    private static int ultraLogSeconds = DEF_ULTRA_LOG_SECONDS;
    private static boolean depthBlitEnabled = DEF_DEPTH_BLIT;
    private static boolean cullingEnabled = DEF_CULLING;
    /** Every block face reduced to its average colour; see the sampler that reads it. */
    private static boolean flatBlockColours = false;
    /** MiB of VRAM the chunk geometry buffer may take; 0 = derive from the GPU. */
    private static int geometryBudgetMiB = DEF_GEOMETRY_BUDGET;
    private static int framesInFlight = DEF_FRAMES_IN_FLIGHT;
    private static boolean chunkPreloadEnabled = DEF_CHUNK_PRELOAD;
    private static int chunkBuildThreads = DEF_CHUNK_BUILD_THREADS;
    private static int nearPlaneHundredths = DEF_NEAR_PLANE_HUNDREDTHS;
    private static boolean visibilitySeedCache = DEF_VISIBILITY_SEED_CACHE;
    private static volatile boolean ownVisibilityWalk = DEF_OWN_VISIBILITY_WALK;
    private static volatile boolean shortEntitySections = DEF_SHORT_ENTITY_SECTIONS;
    private static volatile boolean shortLayerSections = DEF_SHORT_LAYER_SECTIONS;
    private static boolean compactVertices = DEF_COMPACT_VERTICES;
    private static volatile boolean fastRebuildNear = DEF_FAST_REBUILD_NEAR;
    private static volatile boolean materialTags = DEF_MATERIAL_TAGS;
    private static volatile boolean smartAnimations = DEF_SMART_ANIMATIONS;
    private static boolean showMaterials = DEF_SHOW_MATERIALS;
    private static boolean showOcclusion = DEF_SHOW_OCCLUSION;
    private static boolean showMotion = DEF_SHOW_MOTION;
    private static boolean motionOverWorld = DEF_MOTION_OVER_WORLD;
    private static boolean showReflections = DEF_SHOW_REFLECTIONS;
    private static volatile boolean buildNearOffThread = DEF_BUILD_NEAR_OFF_THREAD;
    private static boolean fastFrustumTest = DEF_FAST_FRUSTUM_TEST;
    private static boolean vulkanTranslucent = DEF_VULKAN_TRANSLUCENT;
    private static boolean vulkanParticles = DEF_VULKAN_PARTICLES;
    private static boolean vulkanWeather = DEF_VULKAN_WEATHER;
    private static int vulkanDevice = DEF_VULKAN_DEVICE;
    private static boolean rayTracing = DEF_RAY_TRACING;
    private static boolean entityCapture = DEF_ENTITY_CAPTURE;
    private static int sunShadows = DEF_SUN_SHADOWS;
    private static int shadowSoftness = DEF_SHADOW_SOFTNESS;
    private static int rayTracingRadius = DEF_RAY_TRACING_RADIUS;
    private static int tracedLights = DEF_TRACED_LIGHTS;
    private static int tracedBlockLight = DEF_TRACED_BLOCK_LIGHT;
    private static int blockLightRadius = DEF_BLOCK_LIGHT_RADIUS;
    private static int temporalAccumulation = DEF_TEMPORAL_ACCUMULATION;
    private static boolean showAccumulation = DEF_SHOW_ACCUMULATION;
    private static boolean vulkanEntities = DEF_VULKAN_ENTITIES;
    private static boolean roundSun = DEF_ROUND_SUN;
    private static boolean roundMoon = DEF_ROUND_MOON;
    private static int moonSize = DEF_MOON_SIZE;
    private static int waterRefraction = DEF_WATER_REFRACTION;
    private static volatile int celestialGlint = DEF_CELESTIAL_GLINT;
    private static volatile int iceShine = DEF_ICE_SHINE;
    private static volatile int waterCaustics = DEF_WATER_CAUSTICS;
    private static volatile int wetSurfaces = DEF_WET_SURFACES;
    private static volatile int sunHaze = DEF_SUN_HAZE;
    private static volatile int skyGradient = DEF_SKY_GRADIENT;
    private static volatile boolean sceneOcclusion = DEF_SCENE_OCCLUSION;
    private static volatile int leafShadows = DEF_LEAF_SHADOWS;
    private static volatile int contactShadows = DEF_CONTACT_SHADOWS;
    private static volatile int creatureLight = DEF_CREATURE_LIGHT;
    private static boolean showCreatureLight = DEF_SHOW_CREATURE_LIGHT;
    private static boolean updateCheck = DEF_UPDATE_CHECK;
    private static volatile int cloudShadows = DEF_CLOUD_SHADOWS;
    private static volatile int godRays = DEF_GOD_RAYS;
    private static volatile boolean hdrFrame = DEF_HDR_FRAME;
    private static volatile int exposure = DEF_EXPOSURE;
    private static volatile int cloudTint = DEF_CLOUD_TINT;
    private static volatile int preloadQueue = DEF_PRELOAD_QUEUE;
    private static volatile int preloadScan = DEF_PRELOAD_SCAN;
    private static String skinPack = DEF_SKIN_PACK;
    private static int sunSize = DEF_SUN_SIZE;
    private static int sceneTone = DEF_SCENE_TONE;
    private static int sceneWarmth = DEF_SCENE_WARMTH;
    private static int frameGraphCorner = DEF_FRAME_GRAPH_CORNER;
    private static boolean cacheBlockEntityModels = DEF_CACHE_BLOCK_ENTITY_MODELS;
    private static int explosionParticles = DEF_EXPLOSION_PARTICLES;
    private static int timeControl = DEF_TIME_CONTROL;
    private static int timeOfDay = DEF_TIME_OF_DAY;
    private static int weatherControl = DEF_WEATHER_CONTROL;
    private static int sunWarmth = DEF_SUN_WARMTH;
    private static int lightSoftness = DEF_LIGHT_SOFTNESS;
    private static boolean frameGraph = DEF_FRAME_GRAPH;
    private static int frameGraphInterval = DEF_FRAME_GRAPH_INTERVAL;
    private static boolean dynamicLights = DEF_DYNAMIC_LIGHTS;
    private static int dynamicLightDistance = DEF_DYNAMIC_LIGHT_DISTANCE;
    private static volatile boolean dropVanillaBuffers = DEF_DROP_VANILLA_BUFFERS;
    private static boolean extremeRenderDistance = DEF_EXTREME_RENDER_DISTANCE;
    private static int directionalLight = DEF_DIRECTIONAL_LIGHT;
    private static int heightFog = DEF_HEIGHT_FOG;
    private static int heightFogDepth = DEF_HEIGHT_FOG_DEPTH;
    private static int fogDistance = DEF_FOG_DISTANCE;
    private static int waterReflection = DEF_WATER_REFLECTION;
    private static int waterWaves = DEF_WATER_WAVES;
    private static int foliageSway = DEF_FOLIAGE_SWAY;
    private static int bloom = DEF_BLOOM;
    private static int screenReflections = DEF_SCREEN_REFLECTIONS;
    private static int ambientOcclusion = DEF_AMBIENT_OCCLUSION;
    private static int aoRadius = DEF_AO_RADIUS;
    private static boolean fogEnabled = DEF_FOG;
    private static boolean zoomEnabled = DEF_ZOOM;
    private static int zoomFactor = DEF_ZOOM_FACTOR;

    private VulkanConfig() {
    }

    public static void load(File configDirectory) {
        config = new Configuration(new File(configDirectory, "vulkanmod112.cfg"));
        VulkanProfiles.setDirectory(configDirectory);
        // Where the driver may keep its compiled pipelines between runs. It
        // travels as a property because the Vulkan half runs under its own
        // class loader and cannot see the game, so a string is the whole of the
        // interface. Nothing reads it but that half, and nothing breaks if the
        // file is missing, stale or unwritable.
        System.setProperty("vulkanmod112.pipelineCache",
                new File(configDirectory, "vulkanmod112-pipelines.bin").getAbsolutePath());
        config.load();
        readAll();
    }

    /**
     * Copies every value out of the settings file into the fields above.
     *
     * Split out from {@link #load} so that a profile can be applied without
     * building a new configuration object: a profile writes its values into the
     * same file this reads from, and then this puts them where the game can see
     * them. One reader means a setting cannot exist in the file and be invisible
     * to profiles, which is exactly what went wrong when profiles listed the
     * settings they knew about by hand.
     */
    private static void readAll() {
        terrainEnabled = config.getBoolean("terrainEnabled", CATEGORY_GENERAL, DEF_TERRAIN,
                "Render supported terrain layers through Vulkan. Disabling immediately returns terrain to vanilla OpenGL.");
        overlayEnabled = config.getBoolean("overlayEnabled", CATEGORY_GENERAL, DEF_OVERLAY,
                "Show the legacy Vulkan diagnostic overlay.");
        entityDistance = config.getInt("entityDistance", CATEGORY_OPTIMIZATION, DEF_ENTITY_DISTANCE, 0, 256,
                "Stop drawing entities past this many blocks. 0 keeps vanilla's per-entity limit.");
        tileEntityDistance = config.getInt("tileEntityDistance", CATEGORY_OPTIMIZATION, DEF_TILE_ENTITY_DISTANCE, 0, 128,
                "Stop drawing chests, signs and other block entities past this many blocks. 0 keeps vanilla's.");
        animationsEnabled = config.getBoolean("animatedTextures", CATEGORY_OPTIMIZATION, DEF_ANIMATIONS,
                "Update animated block textures. Off skips the per-tick frame uploads for every animated sprite.");
        backgroundFpsLimit = config.getInt("backgroundFpsLimit", CATEGORY_OPTIMIZATION, DEF_BACKGROUND_FPS, 0, 60,
                "Framerate cap while the game window is not active. 0 disables the cap.");
        ultraLogEnabled = config.getBoolean("ultraLog", CATEGORY_ADVANCED, DEF_ULTRA_LOG,
                "Write a detailed diagnostics report to logs/vulkanmod112-diagnostics.log.");
        // The command line wins, and says so. A measurement run that asks for
        // the diagnostics file and gets nothing because the setting happened to
        // be off is a session spent for no answer — and the silence looks
        // exactly like the run having failed.
        if (Boolean.getBoolean("vulkanmod112.ultraLog") && !ultraLogEnabled) {
            ultraLogEnabled = true;
            net.vulkanmod112.VulkanMod112.LOGGER.info(
                    "Diagnostics file turned on by the command line, over the setting in the config");
        }
        ultraLogSeconds = config.getInt("ultraLogSeconds", CATEGORY_ADVANCED, DEF_ULTRA_LOG_SECONDS, 1, 120,
                "Seconds between diagnostics snapshots.");
        depthBlitEnabled = config.getBoolean("depthBlitEnabled", CATEGORY_ADVANCED, DEF_DEPTH_BLIT,
                "Copy Vulkan depth into the game's depth buffer with glBlitFramebuffer instead of a shader.");
        cullingEnabled = config.getBoolean("cullingEnabled", CATEGORY_ADVANCED, DEF_CULLING,
                "Skip triangles facing away from the camera. Off is for diagnosing geometry only.");
        flatBlockColours = config.getBoolean("flatBlockColours", CATEGORY_ADVANCED, false,
                "Draw every block face in one flat colour by reading the smallest level of the "
                        + "block atlas. The cheapest a texture read can be; the world stops having "
                        + "textures.");
        geometryBudgetMiB = config.getInt("geometryBudgetMiB", CATEGORY_ADVANCED, DEF_GEOMETRY_BUDGET, 0, 8192,
                "VRAM in MiB the chunk geometry buffer may take before growth becomes cautious. "
                        + "0 derives it from the amount of memory the GPU reports.");
        framesInFlight = config.getInt("framesInFlight", CATEGORY_ADVANCED, DEF_FRAMES_IN_FLIGHT, 1, 3,
                "How many terrain frames the CPU may run ahead of the GPU. Higher smooths out stalls "
                        + "at the cost of one frame of input latency and more memory.");
        chunkPreloadEnabled = config.getBoolean("chunkPreload", CATEGORY_OPTIMIZATION,
                DEF_CHUNK_PRELOAD,
                "Let chunks outside the view be rebuilt. Vanilla only ever schedules chunks that are "
                        + "currently on screen, so at high render distances the world fills in along "
                        + "whatever you are looking at.");
        chunkBuildThreads = config.getInt("chunkBuildThreads", CATEGORY_OPTIMIZATION,
                DEF_CHUNK_BUILD_THREADS, 0, 64,
                "How many threads build chunk geometry. 0 keeps vanilla's count, which it derives from "
                        + "the heap rather than the CPU and so caps well below a large core count. "
                        + "Takes effect on the next world load.");
        nearPlaneHundredths = config.getInt("nearPlaneHundredths", CATEGORY_OPTIMIZATION,
                DEF_NEAR_PLANE_HUNDREDTHS, 0, 50,
                "Near clipping plane in hundredths of a block. 0 keeps vanilla's 0.05, which at long "
                        + "render distances leaves the depth buffer unable to separate a snow layer "
                        + "from the block under it. Larger values fix that and clip geometry very "
                        + "close to the eye, which can open a hole when the head is inside a block. "
                        + "Costs nothing either way: it is one number in the projection matrix.");
        visibilitySeedCache = config.getBoolean("visibilitySeedCache", CATEGORY_OPTIMIZATION,
                DEF_VISIBILITY_SEED_CACHE,
                "Reuse the seed of the chunk visibility search while the camera stays in the same "
                        + "block and that block's chunk has not been rebuilt. Vanilla reads all 4096 "
                        + "block states of the camera's own chunk section every time it redoes the "
                        + "search, which at long render distances is hundreds of thousands of reads a "
                        + "second for an answer that did not change.");
        ownVisibilityWalk = config.getBoolean("ownVisibilityWalk", CATEGORY_OPTIMIZATION,
                DEF_OWN_VISIBILITY_WALK,
                "Run this mod's own chunk visibility search instead of the game's. Same answer, "
                        + "same every frame, but out of reused buffers rather than a fresh queue, "
                        + "set and one object per visited chunk. The game's profiler puts its "
                        + "version at a quarter to a half of the frame at render distance 64. On "
                        + "by default now, having shipped off while it was new: it replaces "
                        + "vanilla logic, and the way that goes wrong is that something quietly "
                        + "stops being drawn, so turn it off first if anything is missing.");
        shortEntitySections = config.getBoolean("shortEntitySections", CATEGORY_OPTIMIZATION,
                DEF_SHORT_ENTITY_SECTIONS,
                "Hand the entity and block-entity passes only the sections that can hold "
                        + "something. Both of them walk every section on screen every frame — "
                        + "around 17 700 at render distance 32, of which some 2 700 have any "
                        + "blocks in them at all — and ask the world about each one before "
                        + "knowing whether anything stands there. Measured with the scene held "
                        + "still at five drawn creatures, that walk alone costs 0.07 ms at eight "
                        + "chunks and 1.10 at thirty-two. The creature list is turned inside out "
                        + "instead: sixty entities each name the section they are filed in, and "
                        + "the visibility search says in one array read whether it is on screen. "
                        + "Needs Own Visibility Search on, and turn it off first if a creature or "
                        + "a chest is not drawn where it should be.");
        shortLayerSections = config.getBoolean("shortLayerSections", CATEGORY_OPTIMIZATION,
                DEF_SHORT_LAYER_SECTIONS,
                "Give the game's own layer filter the sections that hold blocks instead of every "
                        + "section on screen. That filter runs four times a frame, once per render "
                        + "layer, and asks each section whether that layer is empty; at render "
                        + "distance 32 it asks around 17 700 and keeps under 2 700. Whether a "
                        + "section holds anything at all is one bit on the same object the "
                        + "visibility search already reads, so the list is kept while that search "
                        + "walks. It takes that step from 0.72 ms a frame to 0.45, and on the "
                        + "machine it was measured on that bought no frames at all: once the "
                        + "entity passes were shortened the frame stopped waiting on this thread. "
                        + "Worth turning on if the processor rather than the card is what holds "
                        + "your frames back. Off by default: what it could get wrong is a chunk "
                        + "that stops being drawn, and that looks exactly like terrain still "
                        + "building.");
        compactVertices = config.getBoolean("compactVertices", CATEGORY_ADVANCED,
                DEF_COMPACT_VERTICES,
                "Pack each chunk vertex into 16 bytes instead of the 28 the game uses. The "
                        + "position keeps 1/2048 of a block, which is 128 times finer than a "
                        + "texture pixel; the light is exact; the colour is untouched. Measured at "
                        + "render distance 32: the card's terrain time falls from 0.49 ms a frame "
                        + "to 0.42 and the route from 521 frames a second to 547, and the geometry "
                        + "buffer from 428 MiB of video memory to 240. The memory is the larger "
                        + "half of that and it is what decides whether a long render distance fits "
                        + "at all. Takes effect on the next start, and stands aside while ray "
                        + "tracing is on because the acceleration structures read the same buffer.");
        // The command line wins, so the two arms of a comparison differ by one
        // word on it rather than by an edit to the config between runs.
        String shortSectionsPin = System.getProperty("vulkanmod112.shortEntitySections");
        if (shortSectionsPin != null) {
            shortEntitySections = Boolean.parseBoolean(shortSectionsPin);
            net.vulkanmod112.VulkanMod112.LOGGER.info(
                    "Short entity section lists {} by the command line, over the config",
                    shortEntitySections ? "on" : "off");
        }
        String layerSectionsPin = System.getProperty("vulkanmod112.shortLayerSections");
        if (layerSectionsPin != null) {
            shortLayerSections = Boolean.parseBoolean(layerSectionsPin);
            net.vulkanmod112.VulkanMod112.LOGGER.info(
                    "Short layer filter list {} by the command line, over the config",
                    shortLayerSections ? "on" : "off");
        }
        fastRebuildNear = config.getBoolean("fastRebuildNear", CATEGORY_OPTIMIZATION,
                DEF_FAST_REBUILD_NEAR,
                "Hand the last loop of the terrain setup only the chunks it can act on. That loop "
                        + "walks every chunk on screen every frame — some 8 600 at render distance "
                        + "64, and the game's own profiler puts it at 19% of the frame — to find "
                        + "the few a block was broken in. The answer for each one is a single bit, "
                        + "and it now lives in a flat array beside the grid rather than inside a "
                        + "chunk object somewhere else in memory. Off by default because it "
                        + "replaces vanilla logic, and the way that goes wrong is that something "
                        + "stops being rebuilt.");
        smartAnimations = config.getBoolean("smartAnimations", CATEGORY_OPTIMIZATION,
                DEF_SMART_ANIMATIONS,
                "Update only the animated block textures that are actually on screen. Vanilla "
                        + "uploads a new frame for every animated sprite in the atlas every "
                        + "tick, whether or not one block using it is in sight, and a modpack "
                        + "has hundreds. A chunk records what it uses while it is built, so "
                        + "chunks in view have to be rebuilt — F3+A — before this saves "
                        + "anything. Fluids, fire and portals are never skipped, nor is any "
                        + "sprite that no chunk has ever used, which is what keeps an item in a "
                        + "menu moving. Experimental: a texture that is both a block and an "
                        + "item can stand still in your hand while no such block is in sight.");
        materialTags = config.getBoolean("materialTags", CATEGORY_OPTIMIZATION,
                DEF_MATERIAL_TAGS,
                "Record what each stretch of a chunk's geometry is made of while the chunk is "
                        + "being built. On its own this changes nothing on screen: it is the "
                        + "groundwork the remaining effects need. Water cannot be told from "
                        + "stained glass, nor grass from torches, because vanilla's four render "
                        + "layers are not materials and the vertex carries nothing else; the one "
                        + "place the answer exists is the rebuild loop, for the length of one "
                        + "call. Costs a branch and a counter read per block rendered, on the "
                        + "build threads. The diagnostics report says what that measured.");
        showMaterials = config.getBoolean("showMaterials", CATEGORY_ADVANCED, DEF_SHOW_MATERIALS,
                "Paint the terrain by what it is made of instead of by its texture: water blue, "
                        + "foliage green, glass yellow, lava orange, everything else grey. A "
                        + "diagnostic for the material buffer, which is invisible whether it is "
                        + "right or wrong. Needs Material Tags on and a chunk rebuild to fill in.");
        showOcclusion = config.getBoolean("showOcclusion", CATEGORY_ADVANCED, DEF_SHOW_OCCLUSION,
                "Draw the ambient occlusion on its own, as flat grey, instead of applying it to "
                        + "the world. Vanilla darkens the corners of its own blocks and darkens a "
                        + "face by which way it points, so a dark seam in a lit room says nothing "
                        + "until those two are out of the picture. This takes them out. Needs "
                        + "Ambient Occlusion above zero.");
        showMotion = config.getBoolean("showMotion", CATEGORY_ADVANCED, DEF_SHOW_MOTION,
                "Paint the world with how each pixel moved since the last frame instead of with "
                        + "itself: which way it went is the colour, how fast is the brightness, "
                        + "and anything that did not move is black. Frame averaging is what "
                        + "reads it — the pass that turns one traced ray per pixel from grain "
                        + "into a soft edge — so it is worked out only while something is being "
                        + "traced or while this view is open. It is what "
                        + "reflections and any effect that remembers previous frames are built "
                        + "on, and this is how to see whether it is right.");
        motionOverWorld = config.getBoolean("motionOverWorld", CATEGORY_ADVANCED,
                DEF_MOTION_OVER_WORLD,
                "Show the motion over a dim ghost of the world instead of over black. Black "
                        + "answers whether anything is moving; the ghost answers which part of "
                        + "it is.");
        showReflections = config.getBoolean("showReflections", CATEGORY_ADVANCED,
                DEF_SHOW_REFLECTIONS,
                "Paint the water with what the reflected ray found and nothing else: no fresnel "
                        + "deciding how much of it to show, no water colour under it, deep blue "
                        + "wherever the ray found nothing. Needs Screen Reflections above zero.");
        buildNearOffThread = config.getBoolean("buildNearOffThread", CATEGORY_OPTIMIZATION,
                DEF_BUILD_NEAR_OFF_THREAD,
                "Queue a chunk that changed close to you for a builder thread instead of "
                        + "rebuilding it on the thread that draws. Vanilla rebuilds anything "
                        + "dirty within about 28 blocks of the eye right there in the middle of "
                        + "setting the frame up, and the frame waits for it: measured, that is "
                        + "0.4 to 0.7 ms a frame while it is happening, which is nearly all of "
                        + "what that step costs. The price is that a chunk you just changed "
                        + "catches up a frame or two later rather than instantly. Forge has the "
                        + "same switch and it wins when it is on.");
        fastFrustumTest = config.getBoolean("fastFrustumTest", CATEGORY_OPTIMIZATION,
                DEF_FAST_FRUSTUM_TEST,
                "Decide whether a box is off screen from its far corner rather than from all "
                        + "eight of them. The same answer by the same arithmetic: the corner "
                        + "furthest along a plane's normal is the last one to leave it, so if it "
                        + "is outside, all of them are. Vanilla checks up to forty-eight corner "
                        + "positions to reject one box, and the chunk visibility search does this "
                        + "once for every chunk it reaches. On by default; the switch is for "
                        + "ruling it out, not for choosing.");
        vulkanTranslucent = config.getBoolean("vulkanTranslucent", CATEGORY_OPTIMIZATION,
                DEF_VULKAN_TRANSLUCENT,
                "Draw water and glass in Vulkan rather than leaving them on the OpenGL path. Not "
                        + "a speed setting: the layer measures 2.6% of a frame either way. It is "
                        + "here because the Vulkan terrain has fog and the OpenGL leftovers do "
                        + "not, so water is currently the one surface that stays clear when "
                        + "everything around it fades. On by default, once water, glass and the "
                        + "creatures seen through them had been checked by eye.");
        dropVanillaBuffers = config.getBoolean("dropVanillaBuffers", CATEGORY_OPTIMIZATION,
                DEF_DROP_VANILLA_BUFFERS,
                "Stop filling the game's own chunk buffers once Vulkan has the geometry. The world "
                        + "is held twice in video memory today; this removes one of the copies and "
                        + "halves what a render distance costs there. It also takes the second "
                        + "upload out of the budget the game reserves each frame for getting "
                        + "chunks onto the card, which is what actually limits how fast a world "
                        + "fills in. On by default since the translucent layer started going "
                        + "through Vulkan everywhere: what it costs is that a fallback to vanilla "
                        + "rendering has to rebuild the entire world first, which is a pause, not "
                        + "a hole.");
        vulkanParticles = config.getBoolean("vulkanParticles", CATEGORY_OPTIMIZATION,
                DEF_VULKAN_PARTICLES,
                "Draw particles with Vulkan. The game still decides where every particle is and "
                        + "what it looks like — this changes only how the finished quads reach "
                        + "the card. Vanilla hands them over as a client-side vertex array, "
                        + "which the driver has to copy in full before it can start, and the "
                        + "game allows itself up to sixteen thousand particles in each of six "
                        + "queues. Costs no extra work between the two APIs: they join the pass "
                        + "that draws water.");
        vulkanWeather = config.getBoolean("vulkanWeather", CATEGORY_OPTIMIZATION,
                DEF_VULKAN_WEATHER,
                "The same for rain and snow. A separate switch from the one above so that either "
                        + "can be ruled out on its own.");
        sunShadows = config.getInt("sunShadows", CATEGORY_GENERAL, DEF_SUN_SHADOWS, 0, 100,
                "How dark the sun's shadow is, traced against the terrain. Needs Terrain "
                        + "Acceleration Structures and a card that can trace from a shader. The "
                        + "shadow lowers how much sky light a surface gets rather than darkening "
                        + "the finished colour, which is how the game itself shades and why it "
                        + "does not touch a cave lit by a torch.");
        shadowSoftness = config.getInt("shadowSoftness", CATEGORY_GENERAL, DEF_SHADOW_SOFTNESS,
                0, 100,
                "How soft the edge of a traced shadow is. One ray gives one answer per pixel, so "
                        + "at zero the edge follows the pixel grid; higher values spread that same "
                        + "ray over a disc and trade the staircase for a dithered band. Costs "
                        + "nothing either way — the number of rays does not change.");
        tracedBlockLight = config.getInt("tracedBlockLight", CATEGORY_GENERAL,
                DEF_TRACED_BLOCK_LIGHT, 0, 100,
                "How much of the game's own block light to replace with light traced from the "
                        + "blocks that emit it. Vanilla's is a flood fill: right around corners "
                        + "and completely flat, with no idea where the light came from. Traced "
                        + "light has a direction and a shadow. Only sources near you are known "
                        + "and only the nearest thirty-two fit, so turned up fully a cave lit "
                        + "from further off goes dark.");
        blockLightRadius = config.getInt("blockLightRadius", CATEGORY_ADVANCED,
                DEF_BLOCK_LIGHT_RADIUS, 4, 50,
                "How far around you light-emitting blocks are looked for, in blocks. The search "
                        + "runs a few times a second rather than every frame, is spread over "
                        + "several frames, and skips whole sixteen-block sections that hold no "
                        + "block light at all — so what widening it costs is far less than the "
                        + "volume suggests.");
        temporalAccumulation = config.getInt("temporalAccumulation", CATEGORY_GENERAL,
                DEF_TEMPORAL_ACCUMULATION, 0, 100,
                "How much of what a pixel looked like last frame it keeps. A traced shadow is "
                        + "worked out from one ray per pixel, which is grain; the rays are aimed "
                        + "differently each frame and averaged here, which is a soft edge. Costs "
                        + "one fullscreen pass and does nothing at all unless something is being "
                        + "traced.");
        skinPack = config.getString("skinPack", CATEGORY_GENERAL, DEF_SKIN_PACK,
                "Which shader pack in the shaderpacks folder to take the sun and moon pictures "
                        + "from. Only pictures: a pack's shader code is written against a loader "
                        + "that does not exist here and cannot be run, but a sun is a PNG, and "
                        + "reading one out of a pack you already have copies nothing anywhere. A "
                        + "pack that ships no sun has none to lend, and the game's own is used.");
        waterRefraction = config.getInt("waterRefraction", CATEGORY_GENERAL,
                DEF_WATER_REFRACTION, 0, 100,
                "How much the surface of water bends what is seen through it. Reflection and "
                        + "refraction are two halves of one thing and only one of them was here: "
                        + "a pond whose mirror moves while its bed stays put reads as glass laid "
                        + "over a photograph. What is behind the water is fetched from the same "
                        + "picture the reflection searches, so it shows the world but not "
                        + "creatures, which this renderer does not draw.");
        celestialGlint = config.getInt("celestialGlint", CATEGORY_GENERAL,
                DEF_CELESTIAL_GLINT, 0, 100,
                "The sun itself sliding along the ripples, and the moon doing the same at "
                        + "night. A reflection cannot produce this: the sun is a light rather "
                        + "than a surface that was drawn for a ray to find, so reflecting the sky "
                        + "where it is gives its colour and not its shape. Fades with distance, "
                        + "which is deliberate - a specular highlight physically widens towards "
                        + "the horizon as the eye rises, and a sun that grows when you fly up "
                        + "reads as an error however correct it is.");
        iceShine = config.getInt("iceShine", CATEGORY_GENERAL, DEF_ICE_SHINE, 0, 100,
                "How much of the sky ice gathers on its surface. The game draws ice as a flat "
                        + "blue pane; every shader pack makes it the most recognisable surface in "
                        + "the world by giving it the one thing water already has here - a "
                        + "fresnel term, so it looks along itself the way a polished floor does. "
                        + "Cheaper than water: ice does not ripple, so there are no waves to "
                        + "shade and no ray to march.");
        waterCaustics = config.getInt("waterCaustics", CATEGORY_GENERAL, DEF_WATER_CAUSTICS, 0, 100,
                "How much light gathers into moving bands on the bed of shallow water. Real "
                        + "caustics are the surface acting as a lens on the light going through "
                        + "it; this brightens what is already being fetched from under the water "
                        + "in the same pattern the waves are shaded by, which is the same picture "
                        + "for a fraction of the price. Needs Water Refraction, because that is "
                        + "what fetches the bed in the first place.");
        wetSurfaces = config.getInt("wetSurfaces", CATEGORY_GENERAL, DEF_WET_SURFACES, 0, 100,
                "How much rain makes upward-facing surfaces gather the sky. Only faces pointing "
                        + "up, and only in proportion to how much sky light they already receive "
                        + "- there is no test for whether this particular block is under a roof, "
                        + "so a lit cave mouth will damp a little too. A mood rather than a "
                        + "simulation, like the height fog.");
        leafShadows = config.getInt("leafShadows", CATEGORY_GENERAL, DEF_LEAF_SHADOWS, 0, 100,
                "How much light gets through leaves and plants in a traced shadow. A ray cannot "
                        + "read a texture, so this is how often a leaf quad stops light rather "
                        + "than where its holes are - averaged over frames it comes out as "
                        + "dapple. Needs ray tracing and sun shadows; costs more the more of the "
                        + "screen is under a tree.");
        hdrFrame = config.getBoolean("hdrFrame", CATEGORY_GENERAL, DEF_HDR_FRAME,
                "Ask the game for a frame with room above white in it. Minecraft draws the world into eight bits a channel, so anything brighter than white is cut off before any effect here ever sees it - which is why the glow has no light to add, a highlight on water arrives already flattened into a white patch, and the tone curve can only tilt colours "
                        + "rather than shape the light. With this on the frame holds sixteen bits a channel and the tone pass closes the range back down along a film curve at the end. Applies at once, costs video memory, and is asked of the driver first - if it will not have it, the log says so and nothing changes. Goes back to eight bits by itself if the Vulkan renderer stops drawing, because the pass that closes the range back down lives there.");
        exposure = config.getInt("exposure", CATEGORY_GENERAL, DEF_EXPOSURE, 0, 100,
                "How much light is let in before the film curve closes the range back down. The middle is no change. Only means anything with the frame above turned on.");
        godRays = config.getInt("godRays", CATEGORY_GENERAL, DEF_GOD_RAYS, 0, 100,
                "How bright the shafts of light from the sun may be. Gathered from the finished picture: the walk from a pixel towards the sun adds up what the sky shows through, so anything standing in the way leaves a dark lane and a gap in a canopy leaves a bright one. No geometry and no rays are involved, so it cannot break another mod - and whatever a mod drew is in the picture and casts its own shafts for free. Needs the sun above the horizon and roughly in front of you; fades out rather than switching off as it leaves the screen.");
        cloudShadows = config.getInt("cloudShadows", CATEGORY_GENERAL, DEF_CLOUD_SHADOWS,
                0, 100,
                "How dark a shadow the clouds overhead cast on the world. Read from the very sheet the game draws its clouds from, at the height the world reports and with the drift the game itself counts - so the dark patch lands under the cloud that cast it rather than beside it. Needs the clouds turned on and the sun above the horizon; fades out near the horizon, where the journey up to the cloud layer is long enough that the shadow lands nowhere near what is overhead.");
        contactShadows = config.getInt("contactShadows", CATEGORY_GENERAL,
                DEF_CONTACT_SHADOWS, 0, 100,
                "How dark a short shadow cast along the ground towards the sun may go. It is worked out from the depth of the picture rather than from geometry, so whatever drew into that depth casts one - a chest, a creature, another mod's machine - and nothing is taken away from any mod to get it. It can only find something that is itself on the screen and within about a block of the surface, which is why it is a contact shadow and not a shadow: it fills the gap where a thing meets the floor, and the sun's own long shadows are the traced ones. Shares the ambient occlusion pass, so it costs a loop rather than a pass, and turn on Occlusion Over Everything for it to see anything but blocks.");
        creatureLight = config.getInt("creatureLight", CATEGORY_GENERAL,
                DEF_CREATURE_LIGHT, 0, 100,
                "How much a creature shades its own faces against the sun, so that a cow in a lit world is lit like the world instead of flat against it. The face is taken from the geometry being drawn rather than from the depth of the picture, so it is exact and has no outline around it. Only the sky half of the game's own lighting is moved, never the block half - a creature in a cave beside a torch is left exactly as the game drew it, whatever this is set to, and that is by construction rather than by tuning. Needs Draw Creatures in Vulkan.");
        updateCheck = config.getBoolean("updateCheck", CATEGORY_GENERAL, DEF_UPDATE_CHECK,
                "Ask once when the game starts whether a newer build of this mod exists, and say so at the top of this screen. Both the page it is published on and the repository it is built from are asked, because a build can be on one and not yet on the other, and the button then leads to whichever of them actually has it. Two GET requests with no query, no body and no identifier: the only thing said about you is a user agent naming this mod and its version, which one of the two services refuses a request without. Nothing about the machine, the player, the world or the other mods is collected or sent. Off means the requests are never made.");
        showCreatureLight = config.getBoolean("showCreatureLight", CATEGORY_ADVANCED,
                DEF_SHOW_CREATURE_LIGHT,
                "Paint creatures with the shading term on its own, flat grey, and nothing else. "
                        + "White is a face turned to the sun and dark grey one turned away. The "
                        + "world around them is left alone, which is the point: it shows whether "
                        + "the faces are being found at all, separately from whether the setting "
                        + "is strong enough to notice.");
        sceneOcclusion = config.getBoolean("sceneOcclusion", CATEGORY_GENERAL, DEF_SCENE_OCCLUSION,
                "Darken the corners of the whole picture rather than of the blocks alone. The "
                        + "occlusion is otherwise computed inside this mod's own pass, from a "
                        + "depth image holding terrain and nothing else, so a chest, a mob or a "
                        + "modded block casts nothing into the floor under it. This reads the "
                        + "game's finished depth instead, where all of them are. Costs one more "
                        + "pass over the frame and takes nothing away from anyone: the picture is "
                        + "already drawn by then.");
        skyGradient = config.getInt("skyGradient", CATEGORY_GENERAL, DEF_SKY_GRADIENT, 0, 100,
                "How much deeper the sky gets away from the horizon. Vanilla's sky is one colour "
                        + "from the horizon to straight overhead; this deepens the top of it "
                        + "towards a night sky, using the game's own fog colour so it cannot "
                        + "disagree with the horizon under it. Painted only where the terrain "
                        + "drew nothing, and each pixel is asked where it actually looks rather "
                        + "than how high it sits on the screen, so the deepest part stays "
                        + "overhead however the camera is tilted.");
        sunHaze = config.getInt("sunHaze", CATEGORY_GENERAL, DEF_SUN_HAZE, 0, 100,
                "How much the fog warms towards the sun and cools away from it. The game fogs "
                        + "everything to one colour whichever way you face; the sky it hangs "
                        + "under does not. Needs the game to have fog of its own to tint, so it "
                        + "does nothing where fog is switched off, and nothing at night.");
        cloudTint = config.getInt("cloudTint", CATEGORY_GENERAL, DEF_CLOUD_TINT, 0, 100,
                "How much of the sky's own colour the clouds take. Vanilla clouds are white at "
                        + "noon and white at sunset, hanging in an orange sky. This mixes the "
                        + "colour the game has already worked out for the horizon into them, "
                        + "which is the cheap half of what a shader pack does to a sky - the "
                        + "expensive half is drawing the clouds again as volumes.");
        preloadQueue = config.getInt("preloadQueue", CATEGORY_GENERAL, DEF_PRELOAD_QUEUE, 4, 128,
                "How many chunks Offscreen Chunk Preload keeps queued for building at once. "
                        + "Higher fills the world in faster and takes more of the frame while it "
                        + "does. Does nothing unless that setting is on.");
        preloadScan = config.getInt("preloadScan", CATEGORY_GENERAL, DEF_PRELOAD_SCAN, 512, 32768,
                "How much of the chunk grid Offscreen Chunk Preload looks through each frame "
                        + "while hunting for something to build. The whole grid at a render "
                        + "distance of 64 is a quarter of a million cells, so scanning all of it "
                        + "in one frame would trade a slow fill for a stutter; this is how much "
                        + "of that walk is paid for per frame. Does nothing unless that setting "
                        + "is on.");
        moonSize = config.getInt("moonSize", CATEGORY_GENERAL, DEF_MOON_SIZE, 0, 100,
                "How large the moon is drawn. Same trick as the sun: the quad the game gives it "
                        + "cannot be resized from here, but how much of its picture the disc "
                        + "fills can.");
        roundMoon = config.getBoolean("roundMoon", CATEGORY_GENERAL, DEF_ROUND_MOON,
                "Draw the moon as a round disc with a soft glow. The game does not draw a moon so "
                        + "much as one cell of an eight-picture sheet chosen by tonight's phase, "
                        + "so all eight are drawn here — a lit disc with the shadow creeping "
                        + "across it, which is one circle cut by a moving ellipse. A single disc "
                        + "in its place would be full every night of the month.");
        roundSun = config.getBoolean("roundSun", CATEGORY_GENERAL, DEF_ROUND_SUN,
                "Draw the sun as a round, warm disc instead of vanilla's square. The picture is "
                        + "built here rather than shipped, which is what makes its size and its "
                        + "warmth sliders instead of a file. Nothing else about the sky changes: "
                        + "the quad, its place, its blend and the moon are all still the game's.");
        sunSize = config.getInt("sunSize", CATEGORY_GENERAL, DEF_SUN_SIZE, 0, 100,
                "How large the disc is drawn. The quad the game gives the sun cannot be resized "
                        + "from here, but how much of it the disc fills can, which comes to the "
                        + "same thing. The middle of the range is close to where vanilla put it.");
        sceneTone = config.getInt("sceneTone", CATEGORY_GENERAL, DEF_SCENE_TONE, 0, 100,
                "How strongly the finished picture is graded — contrast in the middle, warmth in "
                        + "the balance. Applied after the whole world is drawn, so it reaches "
                        + "creatures and particles as well as blocks, and never the interface.");
        sceneWarmth = config.getInt("sceneWarmth", CATEGORY_GENERAL, DEF_SCENE_WARMTH, 0, 100,
                "Which way the grading leans. The middle is neutral, above it warm, below it "
                        + "cold. Red gains what blue gives up, so the frame does not get brighter.");
        frameGraphCorner = config.getInt("frameGraphCorner", CATEGORY_ADVANCED,
                DEF_FRAME_GRAPH_CORNER, 0, 3,
                "Which corner the frame time graph sits in. The default is the bottom left, "
                        + "which is where the chat window is.");
        cacheBlockEntityModels = config.getBoolean("cacheBlockEntityModels", CATEGORY_OPTIMIZATION,
                DEF_CACHE_BLOCK_ENTITY_MODELS,
                "Record the primed TNT cube once and replay it, instead of looking the model up "
                        + "and rebuilding its twelve quads for every charge in every frame. The "
                        + "picture is identical — the list is recorded from the game's own call.");
        explosionParticles = config.getInt("explosionParticles", CATEGORY_OPTIMIZATION,
                DEF_EXPLOSION_PARTICLES, 0, 20000,
                "How many particles one tick's explosions may spawn before they are thinned. "
                        + "The client is sent the list of blocks an explosion destroyed and asks "
                        + "for two particles at each one, so a large charge of TNT is tens of "
                        + "thousands of them born in a single tick. 0 leaves it to the game.");
        timeControl = config.getInt("timeControl", CATEGORY_GENERAL, DEF_TIME_CONTROL, 0, 2,
                "Whether the time of day you see is the world's own, held where it was, or set "
                        + "by the slider below. Nothing is sent to the server and nothing is "
                        + "stored: mobs still burn at dawn whatever this says.");
        timeOfDay = config.getInt("timeOfDay", CATEGORY_GENERAL, DEF_TIME_OF_DAY, 0, 23,
                "Which hour to show when the control above is set to Fixed. The day is kept, so "
                        + "the moon keeps the phase it was going to have.");
        weatherControl = config.getInt("weatherControl", CATEGORY_GENERAL, DEF_WEATHER_CONTROL, 0, 3,
                "Whether the weather you see is the world's own or one you pick. Local to this "
                        + "screen: a storm the server believes in still charges a creeper.");
        sunWarmth = config.getInt("sunWarmth", CATEGORY_GENERAL, DEF_SUN_WARMTH, 0, 100,
                "How far towards orange the rim of the sun goes. 0 leaves it white. The centre "
                        + "stays bright either way — a sun that is one flat colour looks painted "
                        + "on, and the game's own is not flat either.");
        vulkanEntities = config.getBoolean("vulkanEntities", CATEGORY_ADVANCED,
                DEF_VULKAN_ENTITIES,
                "Draw creatures through Vulkan instead of letting the game draw them. "
                        + "Experimental, and the first thing here that replaces vanilla's own "
                        + "drawing rather than adding to it: a mod that builds its models some "
                        + "other way is untouched and draws as it always did, but anything using "
                        + "the ordinary model classes is taken. What it buys is not frames — it "
                        + "is that creatures exist in this renderer at all, which is what "
                        + "reflections and glow have been waiting for.");
        showAccumulation = config.getBoolean("showAccumulation", CATEGORY_ADVANCED,
                DEF_SHOW_ACCUMULATION,
                "Diagnostic: paint each pixel by how much of its history it kept instead of by "
                        + "the world. White is fully averaged, black is starting over — which is "
                        + "what the edges of the screen and everything moving quickly should be.");
        lightSoftness = config.getInt("lightSoftness", CATEGORY_GENERAL, DEF_LIGHT_SOFTNESS, 0, 100,
                "How soft the edge of a shadow cast by a torch or a fire is. Separate from the "
                        + "sun's, because a small flame close by and a star a long way off are "
                        + "not the same kind of source. 0 gives the perfectly crisp edge.");
        tracedLights = config.getInt("tracedLights", CATEGORY_GENERAL, DEF_TRACED_LIGHTS, 0, 8,
                "How many moving lights a surface may ask whether something is in the way. A "
                        + "carried torch or a burning creature is added as a straight line from "
                        + "the source, which is why it lights the far side of a wall; tracing the "
                        + "line is what stops it. One ray per light per surface, so this is the "
                        + "cost. Needs Terrain Acceleration Structures.");
        rayTracingRadius = config.getInt("rayTracingRadius", CATEGORY_ADVANCED,
                DEF_RAY_TRACING_RADIUS, 32, 256,
                "How far from you the terrain carries the structures a ray can hit, in blocks. "
                        + "Decides both what can cast a shadow and what the whole feature costs in "
                        + "video memory and build time; past it shadows fade out rather than "
                        + "stopping at a circle. Takes effect immediately.");
        entityCapture = config.getBoolean("entityCapture", CATEGORY_ADVANCED, DEF_ENTITY_CAPTURE,
                "Read what the game draws for every creature, and draw none of it. The first step "
                        + "of moving entities to Vulkan: it measures how much of a scene comes "
                        + "through the ordinary model path and what reading a pose per part "
                        + "costs. Nothing on screen changes.");
        rayTracing = config.getBoolean("rayTracing", CATEGORY_ADVANCED, DEF_RAY_TRACING,
                "Build acceleration structures over the terrain, which is what a ray needs "
                        + "something to hit. Four effects wait on this and do nothing without "
                        + "it: the sun's shadow, the shadow a moving light casts, traced block "
                        + "light, and the light that comes through a canopy. Needs Vulkan 1.2 "
                        + "and the acceleration-structure extension; the diagnostics report says "
                        + "what happened, and the structures cost video memory and build time "
                        + "whether or not anything is tracing against them. Takes effect on the "
                        + "next start.");
        vulkanDevice = config.getInt("vulkanDevice", CATEGORY_ADVANCED, DEF_VULKAN_DEVICE, -1, 7,
                "Which GPU Vulkan renders on, by the number the log gives it. -1 chooses "
                        + "automatically, and automatic means the card OpenGL is already running "
                        + "on rather than the fastest one present: memory cannot be shared between "
                        + "two devices at all. Takes effect on the next start.");
        frameGraph = config.getBoolean("frameGraph", CATEGORY_GENERAL, DEF_FRAME_GRAPH,
                "Show a frame-time graph in the bottom-left corner, with the worst and best frame "
                        + "of the last couple of seconds and the 1% low. An average framerate "
                        + "cannot tell a steady 120 from a 240 that stalls every tenth frame; the "
                        + "1% low and the shape of the graph can.");
        frameGraphInterval = config.getInt("frameGraphIntervalMs", CATEGORY_GENERAL,
                DEF_FRAME_GRAPH_INTERVAL, 100, 5000,
                "How often the frame graph recomputes the numbers above it, in milliseconds. The "
                        + "trace itself always moves every frame; this is only the text. Figures "
                        + "that change every frame cannot be read at all.");
        dynamicLights = config.getBoolean("dynamicLights", CATEGORY_GENERAL, DEF_DYNAMIC_LIGHTS,
                "Let a carried torch, a dropped glowing block or a burning creature light the "
                        + "terrain around it. The light is added while the world is being shaded, "
                        + "so no chunk is rebuilt for it — which is what makes this affordable, "
                        + "because rebuilding chunks is what the frame is already waiting on when "
                        + "you move. It lights terrain only: entities and anything else OpenGL "
                        + "still draws are unaffected.");
        dynamicLightDistance = config.getInt("dynamicLightDistance", CATEGORY_GENERAL,
                DEF_DYNAMIC_LIGHT_DISTANCE, 1, 200,
                "How far away a light source may be and still be drawn, in blocks. This is not how "
                        + "far the light reaches — that comes from the source's own level, and a "
                        + "torch lights about fifteen blocks around itself whatever this is set to. "
                        + "It decides whether a distant torch lights the ground it is standing on "
                        + "at all, and that pool of light is visible from as far as you can see the "
                        + "ground. Lower it if you would rather only nearby sources counted; there "
                        + "is no meaningful cost either way, because every loaded entity is looked "
                        + "at regardless and only the nearest 32 sources are ever drawn.");
        extremeRenderDistance = config.getBoolean("extremeRenderDistance", CATEGORY_GENERAL,
                DEF_EXTREME_RENDER_DISTANCE,
                "Let the render-distance slider go past 64, up to 128. The game builds a render "
                        + "chunk for every cell of a (2d+1) x (2d+1) x 16 grid before it draws "
                        + "anything: 266 256 of them at 64 and 1 056 784 at 128, four times the "
                        + "objects and four times the memory, whether or not there is a world out "
                        + "there to put in them. Turning this off again pulls the distance back to "
                        + "64 if it is above it.");
        directionalLight = config.getInt("directionalLightStrength", CATEGORY_GENERAL,
                DEF_DIRECTIONAL_LIGHT, 0, 100,
                "How far dynamic light goes towards caring which way a surface is turned, in "
                        + "percent. 0 is off. The game's own light is one number per block and "
                        + "knows nothing about orientation, so a dropped torch lights the "
                        + "underside of the floor it lies on as brightly as the top. This works "
                        + "the face out from the shape of the surface on screen and dims what is "
                        + "turned away. Costs nothing while dynamic lights are off.");
        heightFog = config.getInt("heightFog", CATEGORY_GENERAL, DEF_HEIGHT_FOG, 0, 100,
                "How much colour the ground below you gives up to fog, in percent. 0 is off. It "
                        + "fades towards the game's own fog colour and only where the game "
                        + "already has fog, so it cannot invent a haze the sky disagrees with. It "
                        + "reaches only what this renderer draws: entities and particles are "
                        + "fogged by OpenGL, which knows nothing about height.");
        heightFogDepth = config.getInt("heightFogDepth", CATEGORY_GENERAL, DEF_HEIGHT_FOG_DEPTH,
                4, 96,
                "The drop below the camera, in blocks, over which height fog reaches nearly all "
                        + "of its strength. The strength setting says how much colour the low "
                        + "ground gives up in the end; this says how far down you have to look "
                        + "before it does.");
        waterReflection = config.getInt("waterReflection", CATEGORY_GENERAL,
                DEF_WATER_REFLECTION, 0, 100,
                "How much of a water surface turns into a reflection of the sky as you look "
                        + "along it, in percent. 0 is off. What it reflects is the game's own fog "
                        + "colour, which at a grazing angle is what the horizon is, so it follows "
                        + "sunrise, weather and being underwater by itself. Needs Vulkan Water "
                        + "and Glass on.");
        waterWaves = config.getInt("waterWaves", CATEGORY_GENERAL, DEF_WATER_WAVES, 0, 100,
                "How much a moving wave pattern tilts the water surface, in percent. 0 is off. "
                        + "Nothing is displaced: the water stays flat and only the direction it "
                        + "is treated as facing moves, so the sky reflection breaks up along the "
                        + "crests and a torch scatters across it. Needs Vulkan Water and Glass "
                        + "on.");
        foliageSway = config.getInt("foliageSway", CATEGORY_GENERAL, DEF_FOLIAGE_SWAY, 0, 100,
                "How far the top of a plant leans in the wind, in percent. 0 is off. Grass, "
                        + "flowers, saplings and crops only: leaves are a solid cube and plants "
                        + "taller than one block would come apart at the seam. Needs Material "
                        + "Tags on.");
        bloom = config.getInt("bloom", CATEGORY_GENERAL, DEF_BLOOM, 0, 100,
                "How much light spills off a glowing surface into what is around it, in percent. "
                        + "0 is off. Lava, torches, glowstone and any modded block that gives off "
                        + "light. Terrain only: this mod's frame is finished before the game draws "
                        + "entities and particles, so a burning creature does not glow.");
        screenReflections = config.getInt("screenReflections", CATEGORY_GENERAL,
                DEF_SCREEN_REFLECTIONS, 0, 100,
                "How much of a water reflection is the world actually standing there rather than "
                        + "the fog colour, in percent. 0 is off. The ray is followed across the "
                        + "picture already drawn, so it can only find what is on screen: nothing "
                        + "off the edge of it, nothing hidden behind something nearer, and no "
                        + "creatures, which this renderer does not draw. Where the ray finds "
                        + "nothing the fog colour answers, as it did before.");
        ambientOcclusion = config.getInt("ambientOcclusion", CATEGORY_GENERAL,
                DEF_AMBIENT_OCCLUSION, 0, 100,
                "How much a point is darkened by how little of its surroundings it can see, in "
                        + "percent. 0 is off. Corners, the undersides of overhangs and the join "
                        + "between a wall and a floor pick up shadow the game has no way to "
                        + "express. Terrain only, and worked out from the depth buffer this "
                        + "renderer already has.");
        aoRadius = config.getInt("aoRadius", CATEGORY_GENERAL,
                DEF_AO_RADIUS, 1, 6,
                "How far a corner's shadow reaches, in blocks. Larger is softer and spreads "
                        + "further from the seam; smaller keeps the shading tight against it.");
        fogDistance = config.getInt("fogDistance", CATEGORY_GENERAL, DEF_FOG_DISTANCE, 1, 400,
                "How far the game's own distance fog reaches, as a percentage of what the game "
                        + "chose. Raising it is what a raised render distance was for: vanilla "
                        + "ties the haze to the distance, so more chunks arrive wrapped in more of "
                        + "it and look no further away than before. Fog that tells you something "
                        + "— blindness, being under water or in lava — is never rescaled.");
        fogEnabled = config.getBoolean("fog", CATEGORY_GENERAL, DEF_FOG,
                "Fade Vulkan terrain into the distance the way the rest of the scene already does. "
                        + "Off leaves the world ending in a hard edge, which is a little faster.");
        zoomEnabled = config.getBoolean("zoom", CATEGORY_GENERAL, DEF_ZOOM,
                "Hold-to-zoom on the key bound in Controls.");
        zoomFactor = config.getInt("zoomFactor", CATEGORY_GENERAL, DEF_ZOOM_FACTOR, 2, 10,
                "How far the zoom key narrows the field of view. 4 means a quarter of it.");
        applySystemProperties();
        save();
    }

    /** How a setting is named inside a profile: category, then the key. */
    public static final String PROFILE_PREFIX = "cfg.";

    /**
     * Writes every setting this mod owns into {@code out}, whatever they are.
     *
     * Enumerated from the settings file rather than listed here, and that is the
     * whole point of it. A profile that names the settings it captures is a
     * second list of every option in the mod, kept in step with the first by
     * nothing but memory — and it was not: the shader percentages, a dozen of
     * the most-used values in the mod, were silently left out of every profile
     * anyone saved. Anything that reaches the settings file now reaches a
     * profile, including settings that do not exist yet.
     */
    public static void snapshotInto(java.util.Properties out) {
        if (config == null) {
            return;
        }
        for (String category : config.getCategoryNames()) {
            net.minecraftforge.common.config.ConfigCategory values = config.getCategory(category);
            for (java.util.Map.Entry<String, net.minecraftforge.common.config.Property> entry
                    : values.entrySet()) {
                out.setProperty(PROFILE_PREFIX + category + "." + entry.getKey(),
                        entry.getValue().getString());
            }
        }
    }

    /**
     * Puts a snapshot back and makes the game see it.
     *
     * Values that no longer exist are ignored rather than created: a setting
     * removed from the mod must not come back as an orphan in the file, and a
     * profile written by a newer version has to be usable by an older one.
     *
     * @return how many settings were applied
     */
    public static int restoreFrom(java.util.Properties in) {
        if (config == null) {
            return 0;
        }
        int applied = 0;
        for (String name : in.stringPropertyNames()) {
            if (!name.startsWith(PROFILE_PREFIX)) {
                continue;
            }
            String path = name.substring(PROFILE_PREFIX.length());
            int split = path.lastIndexOf('.');
            if (split <= 0 || split == path.length() - 1) {
                continue;
            }
            String category = path.substring(0, split);
            String key = path.substring(split + 1);
            if (!config.hasCategory(category)) {
                continue;
            }
            net.minecraftforge.common.config.Property property =
                    config.getCategory(category).get(key);
            if (property == null) {
                continue;
            }
            property.set(in.getProperty(name));
            applied++;
        }
        if (applied > 0) {
            // Back through the one reader, so a profile cannot put a value into
            // the file that the running game never picks up.
            readAll();
        }
        return applied;
    }

    /**
     * Returns every mod-owned setting to its shipped value. Minecraft's own
     * settings are left alone: they are not ours to reset, and the screen only
     * borrows them.
     */
    /**
     * Puts every diagnostic view back, wherever the settings came from.
     *
     * These are the views that paint the world as something other than itself —
     * a mirror, a field of vectors, a flat colour per material. Any one of them
     * left on makes the picture look broken in a way no other setting explains,
     * and the two things a player reaches for when the picture looks broken are
     * Reset and a preset. Both go through here so that neither can leave one on.
     *
     * One list, called from both, rather than the same names written out beside
     * each caller: a seventh view added to one copy and not the other is a bug
     * that only appears months later, on somebody else's machine.
     */
    public static void clearDiagnosticViews() {
        setShowCreatureLight(DEF_SHOW_CREATURE_LIGHT);
        setShowMaterials(DEF_SHOW_MATERIALS);
        setShowAccumulation(DEF_SHOW_ACCUMULATION);
        setShowOcclusion(DEF_SHOW_OCCLUSION);
        setShowMotion(DEF_SHOW_MOTION);
        setShowReflections(DEF_SHOW_REFLECTIONS);
    }

    public static void resetToDefaults() {
        clearDiagnosticViews();
        setContactShadows(DEF_CONTACT_SHADOWS);
        setCreatureLight(DEF_CREATURE_LIGHT);
        setUpdateCheck(DEF_UPDATE_CHECK);
        setCloudShadows(DEF_CLOUD_SHADOWS);
        setGodRays(DEF_GOD_RAYS);
        setHdrFrame(DEF_HDR_FRAME);
        setExposure(DEF_EXPOSURE);
        setTerrainEnabled(DEF_TERRAIN);
        setOverlayEnabled(DEF_OVERLAY);
        setEntityDistance(DEF_ENTITY_DISTANCE);
        setTileEntityDistance(DEF_TILE_ENTITY_DISTANCE);
        setAnimationsEnabled(DEF_ANIMATIONS);
        setBackgroundFpsLimit(DEF_BACKGROUND_FPS);
        // Ultra logging is deliberately not in this list. It changes nothing
        // about the picture — it is the instrument somebody is holding while
        // they work through the presets — and a reset that silently puts the
        // instrument down leaves the next hour of testing producing a file
        // that stops where the interesting part starts. The switch is on the
        // same screen for anyone who wants it off.
        setFogDistance(DEF_FOG_DISTANCE);
        setDepthBlitEnabled(DEF_DEPTH_BLIT);
        setCullingEnabled(DEF_CULLING);
        setFlatBlockColours(false);
        setGeometryBudgetMiB(DEF_GEOMETRY_BUDGET);
        setFramesInFlight(DEF_FRAMES_IN_FLIGHT);
        setChunkPreloadEnabled(DEF_CHUNK_PRELOAD);
        setChunkBuildThreads(DEF_CHUNK_BUILD_THREADS);
        setNearPlaneHundredths(DEF_NEAR_PLANE_HUNDREDTHS);
        setVisibilitySeedCacheEnabled(DEF_VISIBILITY_SEED_CACHE);
        setOwnVisibilityWalk(DEF_OWN_VISIBILITY_WALK);
        setShortEntitySections(DEF_SHORT_ENTITY_SECTIONS);
        setShortLayerSections(DEF_SHORT_LAYER_SECTIONS);
        setCompactVertices(DEF_COMPACT_VERTICES);
        setFastRebuildNear(DEF_FAST_REBUILD_NEAR);
        setMaterialTags(DEF_MATERIAL_TAGS);
        setSmartAnimations(DEF_SMART_ANIMATIONS);
        setBuildNearOffThread(DEF_BUILD_NEAR_OFF_THREAD);
        setFastFrustumTest(DEF_FAST_FRUSTUM_TEST);
        setVulkanTranslucent(DEF_VULKAN_TRANSLUCENT);
        setFrameGraph(DEF_FRAME_GRAPH);
        setFrameGraphInterval(DEF_FRAME_GRAPH_INTERVAL);
        setDynamicLights(DEF_DYNAMIC_LIGHTS);
        setDynamicLightDistance(DEF_DYNAMIC_LIGHT_DISTANCE);
        setDropVanillaBuffers(DEF_DROP_VANILLA_BUFFERS);
        setVulkanParticles(DEF_VULKAN_PARTICLES);
        setVulkanWeather(DEF_VULKAN_WEATHER);
        setVulkanDevice(DEF_VULKAN_DEVICE);
        setRayTracing(DEF_RAY_TRACING);
        setEntityCapture(DEF_ENTITY_CAPTURE);
        setSunShadows(DEF_SUN_SHADOWS);
        setShadowSoftness(DEF_SHADOW_SOFTNESS);
        setRayTracingRadius(DEF_RAY_TRACING_RADIUS);
        setTracedLights(DEF_TRACED_LIGHTS);
        setTracedBlockLight(DEF_TRACED_BLOCK_LIGHT);
        setBlockLightRadius(DEF_BLOCK_LIGHT_RADIUS);
        setLightSoftness(DEF_LIGHT_SOFTNESS);
        setTemporalAccumulation(DEF_TEMPORAL_ACCUMULATION);
        // These four sat out of the reset while their neighbours in the same
        // group were in it, so Reset could leave the world painted in a
        // diagnostic colour with nothing in the screen admitting why.
        setMotionOverWorld(DEF_MOTION_OVER_WORLD);
        setVulkanEntities(DEF_VULKAN_ENTITIES);
        setRoundSun(DEF_ROUND_SUN);
        setRoundMoon(DEF_ROUND_MOON);
        setMoonSize(DEF_MOON_SIZE);
        setWaterRefraction(DEF_WATER_REFRACTION);
        setCelestialGlint(DEF_CELESTIAL_GLINT);
        setIceShine(DEF_ICE_SHINE);
        setWaterCaustics(DEF_WATER_CAUSTICS);
        setWetSurfaces(DEF_WET_SURFACES);
        setSunHaze(DEF_SUN_HAZE);
        setCloudTint(DEF_CLOUD_TINT);
        setPreloadQueue(DEF_PRELOAD_QUEUE);
        setPreloadScan(DEF_PRELOAD_SCAN);
        setSkinPack(DEF_SKIN_PACK);
        setSunSize(DEF_SUN_SIZE);
        setSceneTone(DEF_SCENE_TONE);
        setSceneWarmth(DEF_SCENE_WARMTH);
        setFrameGraphCorner(DEF_FRAME_GRAPH_CORNER);
        setCacheBlockEntityModels(DEF_CACHE_BLOCK_ENTITY_MODELS);
        setExplosionParticles(DEF_EXPLOSION_PARTICLES);
        setTimeControl(DEF_TIME_CONTROL);
        setTimeOfDay(DEF_TIME_OF_DAY);
        setWeatherControl(DEF_WEATHER_CONTROL);
        setSunWarmth(DEF_SUN_WARMTH);
        setExtremeRenderDistance(DEF_EXTREME_RENDER_DISTANCE);
        setDirectionalLight(DEF_DIRECTIONAL_LIGHT);
        setHeightFog(DEF_HEIGHT_FOG);
        setHeightFogDepth(DEF_HEIGHT_FOG_DEPTH);
        setWaterReflection(DEF_WATER_REFLECTION);
        setWaterWaves(DEF_WATER_WAVES);
        setFoliageSway(DEF_FOLIAGE_SWAY);
        setBloom(DEF_BLOOM);
        setScreenReflections(DEF_SCREEN_REFLECTIONS);
        setAmbientOcclusion(DEF_AMBIENT_OCCLUSION);
        setAoRadius(DEF_AO_RADIUS);
        setFogEnabled(DEF_FOG);
        setZoomEnabled(DEF_ZOOM);
        setZoomFactor(DEF_ZOOM_FACTOR);
    }

    public static boolean isChunkPreloadEnabled() {
        return chunkPreloadEnabled;
    }

    public static void setChunkPreloadEnabled(boolean value) {
        chunkPreloadEnabled = value;
        store(CATEGORY_OPTIMIZATION, "chunkPreload", value);
    }

    public static boolean isVisibilitySeedCacheEnabled() {
        return visibilitySeedCache;
    }

    public static void setVisibilitySeedCacheEnabled(boolean value) {
        visibilitySeedCache = value;
        store(CATEGORY_OPTIMIZATION, "visibilitySeedCache", value);
    }

    /** Near plane in hundredths of a block; 0 leaves vanilla's 0.05 alone. */
    public static int getNearPlaneHundredths() {
        return nearPlaneHundredths;
    }

    public static void setNearPlaneHundredths(int value) {
        nearPlaneHundredths = value;
        store(CATEGORY_OPTIMIZATION, "nearPlaneHundredths", value);
    }

    public static boolean isOwnVisibilityWalk() {
        return ownVisibilityWalk;
    }

    public static void setOwnVisibilityWalk(boolean value) {
        ownVisibilityWalk = value;
        store(CATEGORY_OPTIMIZATION, "ownVisibilityWalk", value);
    }

    public static boolean isShortEntitySections() {
        return shortEntitySections;
    }

    public static void setShortEntitySections(boolean value) {
        shortEntitySections = value;
        store(CATEGORY_OPTIMIZATION, "shortEntitySections", value);
    }

    public static boolean isShortLayerSections() {
        return shortLayerSections;
    }

    public static void setShortLayerSections(boolean value) {
        shortLayerSections = value;
        store(CATEGORY_OPTIMIZATION, "shortLayerSections", value);
    }

    public static boolean isCompactVertices() {
        return compactVertices;
    }

    public static void setCompactVertices(boolean value) {
        compactVertices = value;
        store(CATEGORY_ADVANCED, "compactVertices", value);
        applySystemProperties();
    }

    public static boolean isFastRebuildNear() {
        return fastRebuildNear;
    }

    public static void setFastRebuildNear(boolean value) {
        fastRebuildNear = value;
        store(CATEGORY_OPTIMIZATION, "fastRebuildNear", value);
    }

    public static boolean isMaterialTags() {
        return materialTags;
    }

    public static boolean isSmartAnimations() {
        return smartAnimations;
    }

    /**
     * Raises the same rebuild flag as the tags, and for the same reason: what
     * a chunk uses is written into it while it is built, so a chunk built
     * before the switch moved knows nothing and has to say so.
     */
    public static void setSmartAnimations(boolean value) {
        if (smartAnimations != value) {
            materialTagsNeedRebuild = true;
        }
        smartAnimations = value;
        store(CATEGORY_OPTIMIZATION, "smartAnimations", value);
    }

    /**
     * Raised when the tags are switched, and lowered once the world is rebuilt.
     *
     * What a block is made of is recorded into its geometry while the chunk is
     * built and read back out of it while the chunk is drawn — so switching
     * this on changes nothing about a chunk already built, and the world ends
     * up half tagged: grass sways in the chunks that happened to be rebuilt
     * since and stands still in the rest, and the same goes for the glow, the
     * shading and every water and ice effect. It reads as a broken effect
     * rather than as a stale chunk, which is why nobody looked here.
     *
     * A flag rather than a rebuild on the spot, because this setter is called
     * in the middle of applying a preset, which sets fifty of these in a row —
     * rebuilding from inside one of them would rebuild the world several times
     * over for a single button.
     */
    private static volatile boolean materialTagsNeedRebuild;

    public static void setMaterialTags(boolean value) {
        if (materialTags != value) {
            materialTagsNeedRebuild = true;
        }
        materialTags = value;
        store(CATEGORY_OPTIMIZATION, "materialTags", value);
    }

    /** Asked once a frame; true only for the frame after the switch moved. */
    public static boolean takeMaterialTagsRebuild() {
        if (!materialTagsNeedRebuild) {
            return false;
        }
        materialTagsNeedRebuild = false;
        return true;
    }

    public static boolean isShowMaterials() {
        return showMaterials;
    }

    public static void setShowMaterials(boolean value) {
        showMaterials = value;
        store(CATEGORY_ADVANCED, "showMaterials", value);
        applySystemProperties();
    }

    public static boolean isShowOcclusion() {
        return showOcclusion;
    }

    public static void setShowOcclusion(boolean value) {
        showOcclusion = value;
        store(CATEGORY_ADVANCED, "showOcclusion", value);
        applySystemProperties();
    }

    public static boolean isShowMotion() {
        return showMotion;
    }

    public static void setShowMotion(boolean value) {
        showMotion = value;
        store(CATEGORY_ADVANCED, "showMotion", value);
        applySystemProperties();
    }

    public static boolean isMotionOverWorld() {
        return motionOverWorld;
    }

    public static void setMotionOverWorld(boolean value) {
        motionOverWorld = value;
        store(CATEGORY_ADVANCED, "motionOverWorld", value);
        applySystemProperties();
    }

    public static boolean isShowReflections() {
        return showReflections;
    }

    public static void setShowReflections(boolean value) {
        showReflections = value;
        store(CATEGORY_ADVANCED, "showReflections", value);
        applySystemProperties();
    }

    public static boolean isBuildNearOffThread() {
        return buildNearOffThread;
    }

    public static void setBuildNearOffThread(boolean value) {
        buildNearOffThread = value;
        store(CATEGORY_OPTIMIZATION, "buildNearOffThread", value);
    }

    public static boolean isVulkanTranslucent() {
        return vulkanTranslucent;
    }

    public static void setVulkanTranslucent(boolean value) {
        vulkanTranslucent = value;
        store(CATEGORY_OPTIMIZATION, "vulkanTranslucent", value);
    }

    public static boolean isDropVanillaBuffers() {
        return dropVanillaBuffers;
    }

    public static void setDropVanillaBuffers(boolean value) {
        dropVanillaBuffers = value;
        store(CATEGORY_OPTIMIZATION, "dropVanillaBuffers", value);
    }

    public static boolean isVulkanParticles() {
        return vulkanParticles;
    }

    public static void setVulkanParticles(boolean value) {
        vulkanParticles = value;
        store(CATEGORY_OPTIMIZATION, "vulkanParticles", value);
    }

    public static int getShadowSoftness() {
        return shadowSoftness;
    }

    public static void setShadowSoftness(int value) {
        shadowSoftness = value < 0 ? 0 : (value > 100 ? 100 : value);
        store(CATEGORY_GENERAL, "shadowSoftness", shadowSoftness);
        applySystemProperties();
    }

    public static int getTracedBlockLight() {
        return tracedBlockLight;
    }

    public static void setTracedBlockLight(int value) {
        tracedBlockLight = value < 0 ? 0 : (value > 100 ? 100 : value);
        store(CATEGORY_GENERAL, "tracedBlockLight", tracedBlockLight);
        applySystemProperties();
    }

    public static int getTemporalAccumulation() {
        return temporalAccumulation;
    }

    public static void setTemporalAccumulation(int value) {
        temporalAccumulation = value < 0 ? 0 : (value > 100 ? 100 : value);
        store(CATEGORY_GENERAL, "temporalAccumulation", temporalAccumulation);
        applySystemProperties();
    }

    public static String getSkinPack() {
        return skinPack;
    }

    public static void setSkinPack(String value) {
        skinPack = value == null ? "" : value;
        if (config != null) {
            config.get(CATEGORY_GENERAL, "skinPack", DEF_SKIN_PACK).set(skinPack);
            save();
        }
    }

    public static int getWaterRefraction() {
        return waterRefraction;
    }

    public static void setWaterRefraction(int value) {
        waterRefraction = value < 0 ? 0 : (value > 100 ? 100 : value);
        store(CATEGORY_GENERAL, "waterRefraction", waterRefraction);
        applySystemProperties();
    }

    public static int getCelestialGlint() {
        return celestialGlint;
    }

    public static void setCelestialGlint(int value) {
        celestialGlint = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "celestialGlint", celestialGlint);
        applySystemProperties();
    }

    public static int getIceShine() {
        return iceShine;
    }

    public static void setIceShine(int value) {
        iceShine = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "iceShine", iceShine);
        applySystemProperties();
    }

    public static int getWaterCaustics() {
        return waterCaustics;
    }

    public static void setWaterCaustics(int value) {
        waterCaustics = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "waterCaustics", waterCaustics);
        applySystemProperties();
    }

    public static int getWetSurfaces() {
        return wetSurfaces;
    }

    public static void setWetSurfaces(int value) {
        wetSurfaces = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "wetSurfaces", wetSurfaces);
        applySystemProperties();
    }

    public static int getSunHaze() {
        return sunHaze;
    }

    public static void setSunHaze(int value) {
        sunHaze = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "sunHaze", sunHaze);
        applySystemProperties();
    }

    public static boolean isHdrFrame() {
        return hdrFrame;
    }

    public static void setHdrFrame(boolean value) {
        hdrFrame = value;
        store(CATEGORY_GENERAL, "hdrFrame", value);
        applySystemProperties();
    }

    public static int getExposure() {
        return exposure;
    }

    public static void setExposure(int value) {
        exposure = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "exposure", exposure);
        applySystemProperties();
    }

    public static int getGodRays() {
        return godRays;
    }

    public static void setGodRays(int value) {
        godRays = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "godRays", godRays);
        applySystemProperties();
    }

    public static int getCloudShadows() {
        return cloudShadows;
    }

    public static void setCloudShadows(int value) {
        cloudShadows = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "cloudShadows", cloudShadows);
        applySystemProperties();
    }

    public static int getContactShadows() {
        return contactShadows;
    }

    public static void setContactShadows(int value) {
        contactShadows = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "contactShadows", contactShadows);
        applySystemProperties();
    }

    public static int getCreatureLight() {
        return creatureLight;
    }

    public static void setCreatureLight(int value) {
        creatureLight = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "creatureLight", creatureLight);
        applySystemProperties();
    }

    public static boolean isShowCreatureLight() {
        return showCreatureLight;
    }

    public static void setShowCreatureLight(boolean value) {
        showCreatureLight = value;
        store(CATEGORY_ADVANCED, "showCreatureLight", value);
        applySystemProperties();
    }

    public static boolean isUpdateCheck() {
        return updateCheck;
    }

    public static void setUpdateCheck(boolean value) {
        updateCheck = value;
        store(CATEGORY_GENERAL, "updateCheck", value);
    }

    public static int getLeafShadows() {
        return leafShadows;
    }

    public static void setLeafShadows(int value) {
        leafShadows = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "leafShadows", leafShadows);
        applySystemProperties();
    }

    public static boolean isSceneOcclusion() {
        return sceneOcclusion;
    }

    public static void setSceneOcclusion(boolean value) {
        sceneOcclusion = value;
        store(CATEGORY_GENERAL, "sceneOcclusion", value);
        applySystemProperties();
    }

    public static int getSkyGradient() {
        return skyGradient;
    }

    public static void setSkyGradient(int value) {
        skyGradient = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "skyGradient", skyGradient);
        applySystemProperties();
    }

    public static int getCloudTint() {
        return cloudTint;
    }

    public static void setCloudTint(int value) {
        cloudTint = clamp(value, 0, 100);
        store(CATEGORY_GENERAL, "cloudTint", cloudTint);
        applySystemProperties();
    }

    public static int getPreloadQueue() {
        return preloadQueue;
    }

    public static void setPreloadQueue(int value) {
        preloadQueue = clamp(value, 4, 128);
        store(CATEGORY_GENERAL, "preloadQueue", preloadQueue);
        applySystemProperties();
    }

    public static int getPreloadScan() {
        return preloadScan;
    }

    public static void setPreloadScan(int value) {
        preloadScan = clamp(value, 512, 32768);
        store(CATEGORY_GENERAL, "preloadScan", preloadScan);
        applySystemProperties();
    }

    /**
     * The bounds check every setter above was writing out by hand.
     *
     * Two of them once disagreed with the range the settings screen offered,
     * which is a setting that snaps back to a value the slider cannot show.
     */
    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }

    public static int getMoonSize() {
        return moonSize;
    }

    public static void setMoonSize(int value) {
        moonSize = value < 0 ? 0 : (value > 100 ? 100 : value);
        store(CATEGORY_GENERAL, "moonSize", moonSize);
    }

    public static boolean isRoundMoon() {
        return roundMoon;
    }

    public static void setRoundMoon(boolean value) {
        roundMoon = value;
        store(CATEGORY_GENERAL, "roundMoon", value);
    }

    public static boolean isRoundSun() {
        return roundSun;
    }

    public static void setRoundSun(boolean value) {
        roundSun = value;
        store(CATEGORY_GENERAL, "roundSun", value);
    }

    public static int getSceneTone() {
        return sceneTone;
    }

    public static void setSceneTone(int value) {
        sceneTone = value < 0 ? 0 : (value > 100 ? 100 : value);
        store(CATEGORY_GENERAL, "sceneTone", sceneTone);
        applySystemProperties();
    }

    public static int getSceneWarmth() {
        return sceneWarmth;
    }

    public static void setSceneWarmth(int value) {
        sceneWarmth = value < 0 ? 0 : (value > 100 ? 100 : value);
        store(CATEGORY_GENERAL, "sceneWarmth", sceneWarmth);
        applySystemProperties();
    }



    public static int getFrameGraphCorner() {
        return frameGraphCorner;
    }

    public static void setFrameGraphCorner(int value) {
        frameGraphCorner = value < 0 ? 0 : (value > 3 ? 3 : value);
        store(CATEGORY_ADVANCED, "frameGraphCorner", frameGraphCorner);
        applySystemProperties();
    }

    public static boolean isCacheBlockEntityModels() {
        return cacheBlockEntityModels;
    }

    public static void setCacheBlockEntityModels(boolean value) {
        cacheBlockEntityModels = value;
        store(CATEGORY_OPTIMIZATION, "cacheBlockEntityModels", value);
        applySystemProperties();
    }

    public static int getExplosionParticles() {
        return explosionParticles;
    }

    public static void setExplosionParticles(int value) {
        explosionParticles = value < 0 ? 0 : (value > 20000 ? 20000 : value);
        store(CATEGORY_OPTIMIZATION, "explosionParticles", explosionParticles);
        applySystemProperties();
    }

    public static int getTimeControl() {
        return timeControl;
    }

    public static void setTimeControl(int value) {
        timeControl = value < 0 ? 0 : (value > 2 ? 2 : value);
        store(CATEGORY_GENERAL, "timeControl", timeControl);
        applySystemProperties();
    }

    public static int getTimeOfDay() {
        return timeOfDay;
    }

    public static void setTimeOfDay(int value) {
        timeOfDay = value < 0 ? 0 : (value > 23 ? 23 : value);
        store(CATEGORY_GENERAL, "timeOfDay", timeOfDay);
        applySystemProperties();
    }

    public static int getWeatherControl() {
        return weatherControl;
    }

    public static void setWeatherControl(int value) {
        weatherControl = value < 0 ? 0 : (value > 3 ? 3 : value);
        store(CATEGORY_GENERAL, "weatherControl", weatherControl);
        applySystemProperties();
    }

    public static int getSunSize() {
        return sunSize;
    }

    public static void setSunSize(int value) {
        sunSize = value < 0 ? 0 : (value > 100 ? 100 : value);
        store(CATEGORY_GENERAL, "sunSize", sunSize);
    }

    public static int getSunWarmth() {
        return sunWarmth;
    }

    public static void setSunWarmth(int value) {
        sunWarmth = value < 0 ? 0 : (value > 100 ? 100 : value);
        store(CATEGORY_GENERAL, "sunWarmth", sunWarmth);
    }

    public static boolean isVulkanEntities() {
        return vulkanEntities;
    }

    public static void setVulkanEntities(boolean value) {
        vulkanEntities = value;
        store(CATEGORY_ADVANCED, "vulkanEntities", value);
        applySystemProperties();
    }

    public static boolean isShowAccumulation() {
        return showAccumulation;
    }

    public static void setShowAccumulation(boolean value) {
        showAccumulation = value;
        store(CATEGORY_ADVANCED, "showAccumulation", value);
        applySystemProperties();
    }

    public static int getBlockLightRadius() {
        return blockLightRadius;
    }

    public static void setBlockLightRadius(int value) {
        blockLightRadius = value < 4 ? 4 : (value > 50 ? 50 : value);
        store(CATEGORY_ADVANCED, "blockLightRadius", blockLightRadius);
    }

    public static int getLightSoftness() {
        return lightSoftness;
    }

    public static void setLightSoftness(int value) {
        lightSoftness = value < 0 ? 0 : (value > 100 ? 100 : value);
        store(CATEGORY_GENERAL, "lightSoftness", lightSoftness);
        applySystemProperties();
    }

    public static int getTracedLights() {
        return tracedLights;
    }

    public static void setTracedLights(int value) {
        tracedLights = value < 0 ? 0 : (value > 8 ? 8 : value);
        store(CATEGORY_GENERAL, "tracedLights", tracedLights);
        applySystemProperties();
    }

    public static int getRayTracingRadius() {
        return rayTracingRadius;
    }

    public static void setRayTracingRadius(int value) {
        rayTracingRadius = value < 32 ? 32 : (value > 256 ? 256 : value);
        store(CATEGORY_ADVANCED, "rayTracingRadius", rayTracingRadius);
        applySystemProperties();
    }

    public static int getSunShadows() {
        return sunShadows;
    }

    public static void setSunShadows(int value) {
        sunShadows = value < 0 ? 0 : (value > 100 ? 100 : value);
        store(CATEGORY_GENERAL, "sunShadows", sunShadows);
        applySystemProperties();
    }

    public static boolean isEntityCapture() {
        return entityCapture;
    }

    public static void setEntityCapture(boolean value) {
        entityCapture = value;
        store(CATEGORY_ADVANCED, "entityCapture", value);
    }

    public static boolean isRayTracing() {
        return rayTracing;
    }

    public static void setRayTracing(boolean value) {
        rayTracing = value;
        store(CATEGORY_ADVANCED, "rayTracing", value);
        applySystemProperties();
    }

    public static int getVulkanDevice() {
        return vulkanDevice;
    }

    public static void setVulkanDevice(int value) {
        vulkanDevice = value < -1 ? -1 : (value > 7 ? 7 : value);
        store(CATEGORY_ADVANCED, "vulkanDevice", vulkanDevice);
        applySystemProperties();
    }

    public static boolean isVulkanWeather() {
        return vulkanWeather;
    }

    public static void setVulkanWeather(boolean value) {
        vulkanWeather = value;
        store(CATEGORY_OPTIMIZATION, "vulkanWeather", value);
    }

    public static boolean isFrameGraph() {
        return frameGraph;
    }

    public static void setFrameGraph(boolean value) {
        frameGraph = value;
        store(CATEGORY_GENERAL, "frameGraph", value);
    }

    /** Milliseconds between refreshes of the frame graph's numbers. */
    public static int getFrameGraphInterval() {
        return frameGraphInterval;
    }

    public static void setFrameGraphInterval(int value) {
        frameGraphInterval = value;
        store(CATEGORY_GENERAL, "frameGraphIntervalMs", value);
    }

    public static boolean isExtremeRenderDistance() {
        return extremeRenderDistance;
    }

    public static void setExtremeRenderDistance(boolean value) {
        extremeRenderDistance = value;
        store(CATEGORY_GENERAL, "extremeRenderDistance", value);
    }

    public static int getDirectionalLight() {
        return directionalLight;
    }

    public static void setDirectionalLight(int value) {
        directionalLight = value;
        store(CATEGORY_GENERAL, "directionalLightStrength", value);
        applySystemProperties();
    }

    public static int getHeightFog() {
        return heightFog;
    }

    public static void setHeightFog(int value) {
        heightFog = value;
        store(CATEGORY_GENERAL, "heightFog", value);
        applySystemProperties();
    }

    public static int getHeightFogDepth() {
        return heightFogDepth;
    }

    public static int getFogDistance() {
        return fogDistance;
    }

    public static void setFogDistance(int value) {
        fogDistance = value;
        store(CATEGORY_GENERAL, "fogDistance", value);
        applySystemProperties();
    }

    public static void setHeightFogDepth(int value) {
        heightFogDepth = value;
        store(CATEGORY_GENERAL, "heightFogDepth", value);
        applySystemProperties();
    }

    public static int getWaterReflection() {
        return waterReflection;
    }

    public static void setWaterReflection(int value) {
        waterReflection = value;
        store(CATEGORY_GENERAL, "waterReflection", value);
        applySystemProperties();
    }

    public static int getScreenReflections() {
        return screenReflections;
    }

    public static void setScreenReflections(int value) {
        screenReflections = value;
        store(CATEGORY_GENERAL, "screenReflections", value);
        applySystemProperties();
    }

    public static int getAmbientOcclusion() {
        return ambientOcclusion;
    }

    public static void setAmbientOcclusion(int value) {
        ambientOcclusion = value;
        store(CATEGORY_GENERAL, "ambientOcclusion", value);
        applySystemProperties();
    }

    public static int getAoRadius() {
        return aoRadius;
    }

    public static void setAoRadius(int value) {
        aoRadius = value;
        store(CATEGORY_GENERAL, "aoRadius", value);
        applySystemProperties();
    }

    public static int getBloom() {
        return bloom;
    }

    public static void setBloom(int value) {
        bloom = value;
        store(CATEGORY_GENERAL, "bloom", value);
        applySystemProperties();
    }

    public static int getFoliageSway() {
        return foliageSway;
    }

    public static void setFoliageSway(int value) {
        foliageSway = value;
        store(CATEGORY_GENERAL, "foliageSway", value);
        applySystemProperties();
    }

    public static int getWaterWaves() {
        return waterWaves;
    }

    public static void setWaterWaves(int value) {
        waterWaves = value;
        store(CATEGORY_GENERAL, "waterWaves", value);
        applySystemProperties();
    }

    public static boolean isDynamicLights() {
        return dynamicLights;
    }

    public static void setDynamicLights(boolean value) {
        dynamicLights = value;
        store(CATEGORY_GENERAL, "dynamicLights", value);
        applySystemProperties();
    }

    /** How far a light source may be and still be drawn, in blocks. */
    public static int getDynamicLightDistance() {
        return dynamicLightDistance;
    }

    public static void setDynamicLightDistance(int value) {
        dynamicLightDistance = value;
        store(CATEGORY_GENERAL, "dynamicLightDistance", value);
        applySystemProperties();
    }

    public static boolean isFastFrustumTest() {
        return fastFrustumTest;
    }

    public static void setFastFrustumTest(boolean value) {
        fastFrustumTest = value;
        store(CATEGORY_OPTIMIZATION, "fastFrustumTest", value);
    }

    /** Configured thread count, or 0 to leave vanilla's own choice alone. */
    public static int getChunkBuildThreads() {
        return chunkBuildThreads;
    }

    public static void setChunkBuildThreads(int value) {
        chunkBuildThreads = value;
        store(CATEGORY_OPTIMIZATION, "chunkBuildThreads", value);
    }

    /**
     * What the presets store instead of an "auto" sentinel: one thread per
     * core. Vanilla already treats the core count as its own upper bound and
     * only ever lands below it, so this can never be a downgrade — and a
     * concrete number is what the slider then shows.
     */
    public static int coresForChunkBuilding() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    public static boolean isFogEnabled() {
        return fogEnabled;
    }

    public static void setFogEnabled(boolean value) {
        fogEnabled = value;
        store(CATEGORY_GENERAL, "fog", value);
    }

    public static boolean isZoomEnabled() {
        return zoomEnabled;
    }

    public static void setZoomEnabled(boolean value) {
        zoomEnabled = value;
        store(CATEGORY_GENERAL, "zoom", value);
    }

    /** Divisor applied to the field of view while the zoom key is held. */
    public static float getZoomFactor() {
        return zoomFactor;
    }

    public static void setZoomFactor(int value) {
        zoomFactor = value;
        store(CATEGORY_GENERAL, "zoomFactor", value);
    }

    public static boolean isTerrainEnabled() {
        return terrainEnabled;
    }

    public static void setTerrainEnabled(boolean value) {
        terrainEnabled = value;
        store(CATEGORY_GENERAL, "terrainEnabled", value);
    }

    public static boolean isOverlayEnabled() {
        return overlayEnabled;
    }

    public static void setOverlayEnabled(boolean value) {
        overlayEnabled = value;
        store(CATEGORY_GENERAL, "overlayEnabled", value);
    }

    public static int getEntityDistance() {
        return entityDistance;
    }

    public static void setEntityDistance(int value) {
        entityDistance = value;
        store(CATEGORY_OPTIMIZATION, "entityDistance", value);
    }

    public static int getTileEntityDistance() {
        return tileEntityDistance;
    }

    public static void setTileEntityDistance(int value) {
        tileEntityDistance = value;
        store(CATEGORY_OPTIMIZATION, "tileEntityDistance", value);
    }

    public static boolean areAnimationsEnabled() {
        return animationsEnabled;
    }

    public static void setAnimationsEnabled(boolean value) {
        animationsEnabled = value;
        store(CATEGORY_OPTIMIZATION, "animatedTextures", value);
    }

    public static int getBackgroundFpsLimit() {
        return backgroundFpsLimit;
    }

    public static void setBackgroundFpsLimit(int value) {
        backgroundFpsLimit = value;
        store(CATEGORY_OPTIMIZATION, "backgroundFpsLimit", value);
    }

    public static boolean isUltraLogEnabled() {
        return ultraLogEnabled;
    }

    public static void setUltraLogEnabled(boolean value) {
        ultraLogEnabled = value;
        store(CATEGORY_ADVANCED, "ultraLog", value);
    }

    public static int getUltraLogSeconds() {
        return ultraLogSeconds;
    }

    public static void setUltraLogSeconds(int value) {
        ultraLogSeconds = value;
        store(CATEGORY_ADVANCED, "ultraLogSeconds", value);
    }

    public static boolean isDepthBlitEnabled() {
        return depthBlitEnabled;
    }

    public static void setDepthBlitEnabled(boolean value) {
        depthBlitEnabled = value;
        store(CATEGORY_ADVANCED, "depthBlitEnabled", value);
        applySystemProperties();
    }

    public static boolean isCullingEnabled() {
        return cullingEnabled;
    }

    public static boolean isFlatBlockColours() {
        return flatBlockColours;
    }

    public static void setFlatBlockColours(boolean value) {
        flatBlockColours = value;
        store(CATEGORY_ADVANCED, "flatBlockColours", value);
        applySystemProperties();
    }

    public static void setCullingEnabled(boolean value) {
        cullingEnabled = value;
        store(CATEGORY_ADVANCED, "cullingEnabled", value);
        applySystemProperties();
    }

    public static int getGeometryBudgetMiB() {
        return geometryBudgetMiB;
    }

    public static void setGeometryBudgetMiB(int value) {
        geometryBudgetMiB = value;
        store(CATEGORY_ADVANCED, "geometryBudgetMiB", value);
        applySystemProperties();
    }

    public static int getFramesInFlight() {
        return framesInFlight;
    }

    public static void setFramesInFlight(int value) {
        framesInFlight = value;
        store(CATEGORY_ADVANCED, "framesInFlight", value);
        applySystemProperties();
    }

    /**
     * Properties the JVM was started with, captured before this class writes
     * any of its own. A value given on the command line has to survive the
     * settings file, or a switch meant to reproduce another machine reports
     * success and changes nothing — which is how the LWJGL stack size cost two
     * builds.
     */
    private static final java.util.Set<String> PINNED = new java.util.HashSet<String>();

    /**
     * Writes one property unless the command line already claimed it. The first
     * call per key decides: after that the key is either ours to write every
     * time, or never.
     */
    private static void publish(String key, String value) {
        if (PINNED.contains(key)) {
            return;
        }
        if (!published.contains(key)) {
            published.add(key);
            if (System.getProperty(key) != null) {
                PINNED.add(key);
                net.vulkanmod112.VulkanMod112.LOGGER.info(
                        "{} pinned to {} by the command line — the settings screen cannot move it",
                        key, System.getProperty(key));
                return;
            }
        }
        System.setProperty(key, value);
    }

    /** Keys {@link #publish} has already decided about. */
    private static final java.util.Set<String> published = new java.util.HashSet<String>();

    /**
     * A number that moves whenever any of the published settings might have.
     *
     * The renderer is in another classloader and reads every setting as a
     * system property. Reading forty-five of them once a frame put the lock on
     * the global property table on the render thread forty-five times for an
     * answer that changes when somebody moves a slider. It now reads this one
     * and stops when the number has not moved.
     *
     * Anything that writes one of those properties without going through
     * {@link #applySystemProperties()} has to call {@link #settingsMoved()} —
     * there are two, the background throttle and the high dynamic range frame.
     */
    private static final java.util.concurrent.atomic.AtomicLong SETTINGS_VERSION =
            new java.util.concurrent.atomic.AtomicLong();

    /** Say that a published setting has changed, so the renderer reads them again. */
    static void settingsMoved() {
        System.setProperty("vulkanmod112.settingsVersion",
                Long.toString(SETTINGS_VERSION.incrementAndGet()));
    }

    /**
     * The renderer lives behind the bridge in its own classloader and reads
     * these as system properties, which both sides share.
     */
    private static void applySystemProperties() {
        publish("vulkanmod112.depthBlit", Boolean.toString(depthBlitEnabled));
        publish("vulkanmod112.cull", Boolean.toString(cullingEnabled));
        publish("vulkanmod112.flatBlockColours", Boolean.toString(flatBlockColours));
        publish("vulkanmod112.geometryBudget", Integer.toString(geometryBudgetMiB));
        publish("vulkanmod112.framesInFlight", Integer.toString(framesInFlight));
        publish("vulkanmod112.vulkanDevice", Integer.toString(vulkanDevice));
        publish("vulkanmod112.rayTracing", Boolean.toString(rayTracing));
        // Read once, when the renderer's own classes load, and never again —
        // see DEF_COMPACT_VERTICES for why it cannot be moved after that.
        publish("vulkanmod112.compactVertices", Boolean.toString(compactVertices));
        publish("vulkanmod112.sunShadows", Integer.toString(sunShadows));
        publish("vulkanmod112.shadowSoftness", Integer.toString(shadowSoftness));
        publish("vulkanmod112.rayTracingRadius", Integer.toString(rayTracingRadius));
        publish("vulkanmod112.tracedLights", Integer.toString(tracedLights));
        publish("vulkanmod112.tracedBlockLight", Integer.toString(tracedBlockLight));
        publish("vulkanmod112.lightSoftness", Integer.toString(lightSoftness));
        publish("vulkanmod112.directionalLight", Integer.toString(directionalLight));
        publish("vulkanmod112.heightFog", Integer.toString(heightFog));
        publish("vulkanmod112.heightFogDepth", Integer.toString(heightFogDepth));
        publish("vulkanmod112.waterReflection", Integer.toString(waterReflection));
        publish("vulkanmod112.waterWaves", Integer.toString(waterWaves));
        publish("vulkanmod112.foliageSway", Integer.toString(foliageSway));
        publish("vulkanmod112.bloom", Integer.toString(bloom));
        publish("vulkanmod112.ambientOcclusion", Integer.toString(ambientOcclusion));
        publish("vulkanmod112.screenReflections", Integer.toString(screenReflections));
        publish("vulkanmod112.waterRefraction", Integer.toString(waterRefraction));
        publish("vulkanmod112.celestialGlint", Integer.toString(celestialGlint));
        publish("vulkanmod112.iceShine", Integer.toString(iceShine));
        publish("vulkanmod112.waterCaustics", Integer.toString(waterCaustics));
        publish("vulkanmod112.wetSurfaces", Integer.toString(wetSurfaces));
        publish("vulkanmod112.sunHaze", Integer.toString(sunHaze));
        publish("vulkanmod112.skyGradient", Integer.toString(skyGradient));
        publish("vulkanmod112.sceneOcclusion", Boolean.toString(sceneOcclusion));
        publish("vulkanmod112.leafShadows", Integer.toString(leafShadows));
        publish("vulkanmod112.contactShadows", Integer.toString(contactShadows));
        publish("vulkanmod112.creatureLight", Integer.toString(creatureLight));
        publish("vulkanmod112.showCreatureLight", Boolean.toString(showCreatureLight));
        publish("vulkanmod112.cloudShadows", Integer.toString(cloudShadows));
        publish("vulkanmod112.godRays", Integer.toString(godRays));
        publish("vulkanmod112.hdrFrame", Boolean.toString(hdrFrame));
        publish("vulkanmod112.exposure", Integer.toString(exposure));
        publish("vulkanmod112.cloudTint", Integer.toString(cloudTint));
        publish("vulkanmod112.preloadQueue", Integer.toString(preloadQueue));
        publish("vulkanmod112.preloadScan", Integer.toString(preloadScan));
        publish("vulkanmod112.aoRadius", Integer.toString(aoRadius));
        publish("vulkanmod112.smartAnimations", Boolean.toString(smartAnimations));
        publish("vulkanmod112.showMaterials", Boolean.toString(showMaterials));
        publish("vulkanmod112.showOcclusion", Boolean.toString(showOcclusion));
        publish("vulkanmod112.showMotion", Boolean.toString(showMotion));
        publish("vulkanmod112.motionOverWorld", Boolean.toString(motionOverWorld));
        publish("vulkanmod112.showReflections", Boolean.toString(showReflections));
        publish("vulkanmod112.temporalAccumulation", Integer.toString(temporalAccumulation));
        publish("vulkanmod112.showAccumulation", Boolean.toString(showAccumulation));
        // Nothing behind the bridge reads these two — they are published so
        // that they appear in the one list that says what a session was
        // configured with, and so that the command line can pin them like any
        // other setting.
        publish("vulkanmod112.sceneTone", Integer.toString(sceneTone));
        publish("vulkanmod112.sceneWarmth", Integer.toString(sceneWarmth));
        publish("vulkanmod112.frameGraphCorner", Integer.toString(frameGraphCorner));
        publish("vulkanmod112.cacheBlockEntityModels", Boolean.toString(cacheBlockEntityModels));
        publish("vulkanmod112.explosionParticles", Integer.toString(explosionParticles));
        publish("vulkanmod112.timeControl", Integer.toString(timeControl));
        publish("vulkanmod112.timeOfDay", Integer.toString(timeOfDay));
        publish("vulkanmod112.weatherControl", Integer.toString(weatherControl));
        publish("vulkanmod112.dynamicLights", Boolean.toString(dynamicLights));
        publish("vulkanmod112.dynamicLightDistance", Integer.toString(dynamicLightDistance));
        // The sun and moon are drawn on the game's side and nothing behind the
        // bridge reads these five. They are published because the block above
        // them calls itself "all of them", and a report about a sun that is
        // still square could not be told from a report about a switch that was
        // never on — which is the entire question that report asks.
        publish("vulkanmod112.roundSun", Boolean.toString(roundSun));
        publish("vulkanmod112.roundMoon", Boolean.toString(roundMoon));
        publish("vulkanmod112.sunSize", Integer.toString(sunSize));
        publish("vulkanmod112.sunWarmth", Integer.toString(sunWarmth));
        publish("vulkanmod112.skinPack", skinPack.isEmpty() ? "none" : skinPack);
        // Last, so the stamp never says "settled" over a half-written set.
        settingsMoved();
    }

    /**
     * Every setting the renderer reads, read back from where it reads it.
     *
     * The diagnostics file recorded five settings and not one effect, so a
     * report reading "the grass does not sway" could not be told apart from a
     * report reading "I never switched it on" — and which of the two it is
     * happens to be the entire answer. A whole tester session was spent
     * finding that out by inference.
     *
     * Taken from the system properties rather than from the fields on purpose.
     * The properties are what the renderer actually sees, and the two differ
     * exactly when the command line has pinned one — which is the case where
     * reading the field would print a number that nothing in the frame is
     * using.
     *
     * Built from {@link #published}, which {@link #publish} fills, so a setting
     * added later appears here without anyone remembering to add it.
     */
    public static java.util.List<String> describePublished() {
        java.util.List<String> keys = new java.util.ArrayList<String>(published);
        java.util.Collections.sort(keys);
        java.util.List<String> out = new java.util.ArrayList<String>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String value = System.getProperty(key);
            out.add(key.substring(key.lastIndexOf('.') + 1)
                    + "=" + (value == null ? "?" : value)
                    + (PINNED.contains(key) ? " (pinned)" : ""));
        }
        return out;
    }

    private static void store(String category, String key, int value) {
        if (config != null) {
            net.minecraftforge.common.config.Property property = config.get(category, key, value);
            int was = property.getInt();
            if (was != value) {
                noteChange(key, Integer.toString(was), Integer.toString(value));
            }
            property.set(value);
            save();
        }
    }

    private static void store(String category, String key, boolean value) {
        if (config != null) {
            net.minecraftforge.common.config.Property property = config.get(category, key, value);
            boolean was = property.getBoolean();
            if (was != value) {
                noteChange(key, Boolean.toString(was), Boolean.toString(value));
            }
            property.set(value);
            save();
        }
    }

    /**
     * Which settings have moved since the last line was written, and where from.
     *
     * <h2>Why this exists</h2>
     *
     * A tester's log is a list of ten-second snapshots and nothing between
     * them, so working out what they did means diffing forty of them by eye and
     * hoping the moment fell inside one. The session that prompted this had the
     * renderer switched on somewhere in a ten-second gap, and which side of that
     * gap a complaint came from was the whole answer.
     *
     * <h2>Why it is not written where it happens</h2>
     *
     * A slider is dragged, not clicked. Writing a line per changed value would
     * put three hundred of them in the log for one movement of the mouse — the
     * same reason the file itself is not saved there. So the first value a
     * setting left is kept, the last one it arrived at is read when the line is
     * written, and a drag becomes one move rather than three hundred.
     */
    private static final java.util.LinkedHashMap<String, String[]> movedFrom =
            new java.util.LinkedHashMap<String, String[]>();

    private static void noteChange(String key, String was, String now) {
        String[] move = movedFrom.get(key);
        if (move == null) {
            movedFrom.put(key, new String[]{was, now});
        } else {
            // The first value it left and the last it arrived at. A slider
            // dragged from 0 to 60 reads as one move and not as sixty.
            move[1] = now;
        }
    }

    /** Writes the one line, if anything moved. Cheap when nothing did. */
    private static void logChanges() {
        if (movedFrom.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String[]> entry : movedFrom.entrySet()) {
            String was = entry.getValue()[0];
            String now = entry.getValue()[1];
            // A slider dragged out and back again ends where it started. There
            // is nothing to report and "40 -> 40" reads as a bug in this line.
            if (was.equals(now)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append(' ').append(was).append(" -> ").append(now);
        }
        movedFrom.clear();
        if (sb.length() == 0) {
            return;
        }
        net.vulkanmod112.VulkanMod112.LOGGER.info("Settings changed: {}", sb);
        // A setting that just moved is exactly when it is worth saying whether
        // it can do anything, and this is the moment the new value is known.
        SettingsHealth.check();
    }

    /**
     * Marks the file as needing a write, without writing it.
     *
     * A slider is dragged, not clicked: every pixel of travel is a value
     * change, and each one used to write the whole configuration file to disk
     * synchronously. Dozens of writes a second while the mouse moves, on the
     * thread drawing the screen.
     *
     * The write is coalesced instead, and forced where it matters: leaving the
     * settings screen and closing the game both flush. Nothing can be lost that
     * way — the only window is a crash during a drag, and a slider position is
     * not what anyone would mourn.
     */
    private static void save() {
        saveWanted = true;
    }

    private static boolean saveWanted;

    /** Writes the file if anything has changed. Cheap when nothing has. */
    public static void flush() {
        if (!movedFrom.isEmpty()) {
            // Every setter is supposed to do this itself, and two of them did
            // not — so the report printed dynamicLights=false for a session
            // where the light was demonstrably working. A published value that
            // disagrees with the field is worse than no value at all: it is a
            // number in a log that reads as a measurement.
            //
            // Cheap, because it only runs on the tick something moved. This is
            // a safety net and not the mechanism: a setting the renderer reads
            // every frame still needs its setter to publish immediately, and a
            // tick of lag would be visible.
            applySystemProperties();
        }
        // Before the early return: this is called every client tick, and what
        // moved should be written down whether or not the file needs saving.
        logChanges();
        if (!saveWanted) {
            return;
        }
        saveWanted = false;
        if (config != null && config.hasChanged()) {
            config.save();
        }
    }
}
