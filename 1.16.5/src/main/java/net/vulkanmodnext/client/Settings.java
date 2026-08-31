package net.vulkanmodnext.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every setting the mod has, declared once.
 *
 * <h2>Generated, and why</h2>
 *
 * This file is written by {@code tools/lift-settings.py} out of the 1.12.2
 * mod's own declarations — the keys, the categories, the defaults, the ranges
 * and the sentences describing them. Retyping a hundred and seven settings
 * would be a hundred and seven chances to get a default wrong, and a wrong
 * default is the invisible kind of wrong: the setting works, and behaves
 * differently from the one the player already knows.
 *
 * <h2>Declared is not the same as working</h2>
 *
 * Most of these do nothing here yet, because the part of the mod they steer has
 * not been ported. They are listed anyway, and each one says which it is. That
 * is the whole point of showing them: a menu that hides what is missing tells a
 * player the port is further along than it is, and a menu whose switches
 * silently do nothing is worse than one that has none.
 */
public final class Settings {

    public enum Category { GENERAL, QUALITY, OPTIMIZATION, ADVANCED }

    /** One setting: what it is called, what it may hold, and whether it bites. */
    public static final class Setting {

        public final String key;
        public final Category category;
        public final boolean bool;
        public final int min;
        public final int max;
        public final int fallback;
        public final String description;
        /** False when this is declared and remembered but steers nothing yet. */
        public final boolean live;

        Setting(String key, Category category, boolean bool, int min, int max, int fallback,
                boolean live, String description) {
            this.key = key;
            this.category = category;
            this.bool = bool;
            this.min = min;
            this.max = max;
            this.fallback = fallback;
            this.live = live;
            this.description = description;
        }

        /** A readable name, made from the key rather than kept beside it. */
        public String title() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                if (i == 0) {
                    out.append(Character.toUpperCase(c));
                } else if (Character.isUpperCase(c)) {
                    out.append(' ').append(c);
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }

    private static final List<Setting> ALL = new ArrayList<>();

    private static void add(String key, Category category, boolean bool, int min, int max,
                            int fallback, boolean live, String description) {
        ALL.add(new Setting(key, category, bool, min, max, fallback, live, description));
    }

    public static List<Setting> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static List<Setting> of(Category category) {
        List<Setting> out = new ArrayList<>();
        for (Setting setting : ALL) {
            if (setting.category == category) {
                out.add(setting);
            }
        }
        return out;
    }

    /** How many of them actually steer something in this port. */
    public static int liveCount() {
        int n = 0;
        for (Setting setting : ALL) {
            if (setting.live) {
                n++;
            }
        }
        return n;
    }

    private Settings() {
    }

    static {
        add("ambientOcclusion", Category.GENERAL, false, 0, 100, 0, false,
                "How much a point is darkened by how little of its surroundings it can see, in ");
        add("aoRadius", Category.GENERAL, false, 1, 6, 2, false,
                "How far a corner's shadow reaches, in blocks. Larger is softer and spreads ");
        add("bloom", Category.GENERAL, false, 0, 100, 0, false,
                "How much light spills off a glowing surface into what is around it, in percent. ");
        add("celestialGlint", Category.GENERAL, false, 0, 100, 0, false,
                "The sun itself sliding along the ripples, and the moon doing the same at ");
        add("cloudShadows", Category.GENERAL, false, 0, 100, 0, false,
                "How dark a shadow the clouds overhead cast on the world. Read from the very sheet the game draws its clouds from, at the height the world reports and with the drift the game itself counts - so the dark patch lands under the cloud that cast it rather than beside it. Needs the clouds turned on and the sun above the horizon; fades out near the horizon, where the journey up to the cloud layer is long enough that the shadow lands nowhere near what is overhead.");
        add("cloudTint", Category.GENERAL, false, 0, 100, 0, false,
                "How much of the sky's own colour the clouds take. Vanilla clouds are white at ");
        add("colourVision", Category.GENERAL, false, 0, 3, 0, false,
                "Move the colours one kind of eye cannot separate into the channels it still can. ");
        add("contactShadows", Category.GENERAL, false, 0, 100, 0, false,
                "How dark a short shadow cast along the ground towards the sun may go. It is worked out from the depth of the picture rather than from geometry, so whatever drew into that depth casts one - a chest, a creature, another mod's machine - and nothing is taken away from any mod to get it. It can only find something that is itself on the screen and within about a block of the surface, which is why it is a contact shadow and not a shadow: it fills the gap where a thing meets the floor, and the sun's own long shadows are the traced ones. Shares the ambient occlusion pass, so it costs a loop rather than a pass, and turn on Occlusion Over Everything for it to see anything but blocks.");
        add("creatureLight", Category.GENERAL, false, 0, 100, 0, false,
                "How much a creature shades its own faces against the sun, so that a cow in a lit world is lit like the world instead of flat against it. The face is taken from the geometry being drawn rather than from the depth of the picture, so it is exact and has no outline around it. Only the sky half of the game's own lighting is moved, never the block half - a creature in a cave beside a torch is left exactly as the game drew it, whatever this is set to, and that is by construction rather than by tuning. Needs Draw Creatures in Vulkan.");
        add("directionalLightStrength", Category.GENERAL, false, 0, 100, 50, false,
                "How far dynamic light goes towards caring which way a surface is turned, in ");
        add("dynamicLightDistance", Category.GENERAL, false, 1, 200, 160, false,
                "How far away a light source may be and still be drawn, in blocks. This is not how ");
        add("dynamicLights", Category.GENERAL, true, 0, 1, 0, false,
                "Let a carried torch, a dropped glowing block or a burning creature light the ");
        add("exposure", Category.GENERAL, false, 0, 100, 50, false,
                "How much light is let in before the film curve closes the range back down. The middle is no change. Only means anything with the frame above turned on.");
        add("extremeRenderDistance", Category.GENERAL, true, 0, 1, 0, false,
                "Let the render-distance slider go past 64, up to 128. The game builds a render ");
        add("fog", Category.GENERAL, true, 0, 1, 1, false,
                "Fade Vulkan terrain into the distance the way the rest of the scene already does. ");
        add("fogDistance", Category.GENERAL, false, 1, 400, 100, false,
                "How far the game's own distance fog reaches, as a percentage of what the game ");
        add("foliageSway", Category.GENERAL, false, 0, 100, 0, false,
                "How far the top of a plant leans in the wind, in percent. 0 is off. Grass, ");
        add("frameGraph", Category.GENERAL, true, 0, 1, 0, false,
                "Show a frame-time graph in the bottom-left corner, with the worst and best frame ");
        add("frameGraphIntervalMs", Category.GENERAL, false, 100, 5000, 1000, false,
                "How often the frame graph recomputes the numbers above it, in milliseconds. The ");
        add("godRays", Category.GENERAL, false, 0, 100, 0, false,
                "How bright the shafts of light from the sun may be. Gathered from the finished picture: the walk from a pixel towards the sun adds up what the sky shows through, so anything standing in the way leaves a dark lane and a gap in a canopy leaves a bright one. No geometry and no rays are involved, so it cannot break another mod - and whatever a mod drew is in the picture and casts its own shafts for free. Needs the sun above the horizon and roughly in front of you; fades out rather than switching off as it leaves the screen.");
        add("hdrFrame", Category.GENERAL, true, 0, 1, 0, true,
                "Ask the game for a frame with room above white in it. Minecraft draws the world into eight bits a channel, so anything brighter than white is cut off before any effect here ever sees it - which is why the glow has no light to add, a highlight on water arrives already flattened into a white patch, and the tone curve can only tilt colours ");
        add("heightFog", Category.GENERAL, false, 0, 100, 0, false,
                "How much colour the ground below you gives up to fog, in percent. 0 is off. It ");
        add("heightFogDepth", Category.GENERAL, false, 4, 96, 24, false,
                "The drop below the camera, in blocks, over which height fog reaches nearly all ");
        add("iceShine", Category.GENERAL, false, 0, 100, 0, false,
                "How much of the sky ice gathers on its surface. The game draws ice as a flat ");
        add("leafGlow", Category.GENERAL, false, 0, 100, 0, false,
                "How brightly a leaf lets the sun through from behind it. The game shades a leaf ");
        add("leafShadows", Category.GENERAL, false, 0, 100, 0, false,
                "How much light gets through leaves and plants in a traced shadow. A ray cannot ");
        add("lightSoftness", Category.GENERAL, false, 0, 100, 30, false,
                "How soft the edge of a shadow cast by a torch or a fire is. Separate from the ");
        add("moonSize", Category.GENERAL, false, 0, 100, 40, false,
                "How large the moon is drawn. Same trick as the sun: the quad the game gives it ");
        add("overlayEnabled", Category.GENERAL, true, 0, 1, 0, false,
                "Show the legacy Vulkan diagnostic overlay.");
        add("preloadQueue", Category.GENERAL, false, 4, 128, 16, false,
                "How many chunks Offscreen Chunk Preload keeps queued for building at once. ");
        add("preloadScan", Category.GENERAL, false, 512, 32768, 4096, false,
                "How much of the chunk grid Offscreen Chunk Preload looks through each frame ");
        add("roundMoon", Category.GENERAL, true, 0, 1, 0, false,
                "Draw the moon as a round disc with a soft glow. The game does not draw a moon so ");
        add("roundSun", Category.GENERAL, true, 0, 1, 0, false,
                "Draw the sun as a round, warm disc instead of vanilla's square. The picture is ");
        add("sceneGamma", Category.GENERAL, false, 0, 100, 50, false,
                "How the finished frame is bent before it reaches the screen. Fifty is the frame ");
        add("sceneOcclusion", Category.GENERAL, true, 0, 1, 0, true,
                "Darken the corners of the whole picture rather than of the blocks alone. The ");
        add("sceneTone", Category.GENERAL, false, 0, 100, 0, false,
                "How strongly the finished picture is graded — contrast in the middle, warmth in ");
        add("sceneWarmth", Category.GENERAL, false, 0, 100, 50, false,
                "Which way the grading leans. The middle is neutral, above it warm, below it ");
        add("screenReflections", Category.GENERAL, false, 0, 100, 0, false,
                "How much of a water reflection is the world actually standing there rather than ");
        add("shadowSoftness", Category.GENERAL, false, 0, 100, 35, false,
                "How soft the edge of a traced shadow is. One ray gives one answer per pixel, so ");
        add("skyGradient", Category.GENERAL, false, 0, 100, 0, false,
                "How much deeper the sky gets away from the horizon. Vanilla's sky is one colour ");
        add("sunHaze", Category.GENERAL, false, 0, 100, 0, false,
                "How much the fog warms towards the sun and cools away from it. The game fogs ");
        add("sunShadows", Category.GENERAL, false, 0, 100, 0, false,
                "How dark the sun's shadow is, traced against the terrain. Needs Terrain ");
        add("sunSize", Category.GENERAL, false, 0, 100, 50, false,
                "How large the disc is drawn. The quad the game gives the sun cannot be resized ");
        add("sunWarmth", Category.GENERAL, false, 0, 100, 60, false,
                "How far towards orange the rim of the sun goes. 0 leaves it white. The centre ");
        add("temporalAccumulation", Category.GENERAL, false, 0, 100, 60, false,
                "How much of what a pixel looked like last frame it keeps. A traced shadow is ");
        add("terrainEnabled", Category.GENERAL, true, 0, 1, 0, true,
                "Render supported terrain layers through Vulkan. Disabling immediately returns terrain to vanilla OpenGL.");
        add("timeControl", Category.GENERAL, false, 0, 2, 0, false,
                "Whether the time of day you see is the world's own, held where it was, or set ");
        add("timeOfDay", Category.GENERAL, false, 0, 23, 12, false,
                "Which hour to show when the control above is set to Fixed. The day is kept, so ");
        add("tracedBlockLight", Category.GENERAL, false, 0, 100, 0, false,
                "How much of the game's own block light to replace with light traced from the ");
        add("tracedLights", Category.GENERAL, false, 0, 8, 2, false,
                "How many moving lights a surface may ask whether something is in the way. A ");
        add("updateCheck", Category.GENERAL, true, 0, 1, 1, false,
                "Ask once when the game starts whether a newer build of this mod exists, and say so at the top of this screen. Both the page it is published on and the repository it is built from are asked, because a build can be on one and not yet on the other, and the button then leads to whichever of them actually has it. Two GET requests with no query, no body and no identifier: the only thing said about you is a user agent naming this mod and its version, which one of the two services refuses a request without. Nothing about the machine, the player, the world or the other mods is collected or sent. Off means the requests are never made.");
        add("waterCaustics", Category.GENERAL, false, 0, 100, 0, false,
                "How much light gathers into moving bands on the bed of shallow water. Real ");
        add("waterReflection", Category.GENERAL, false, 0, 100, 0, false,
                "How much of a water surface turns into a reflection of the sky as you look ");
        add("waterRefraction", Category.GENERAL, false, 0, 100, 0, false,
                "How much the surface of water bends what is seen through it. Reflection and ");
        add("waterWaves", Category.GENERAL, false, 0, 100, 0, false,
                "How much a moving wave pattern tilts the water surface, in percent. 0 is off. ");
        add("weatherControl", Category.GENERAL, false, 0, 3, 0, false,
                "Whether the weather you see is the world's own or one you pick. Local to this ");
        add("wetSurfaces", Category.GENERAL, false, 0, 100, 0, false,
                "How much rain makes upward-facing surfaces gather the sky. Only faces pointing ");
        add("zoom", Category.GENERAL, true, 0, 1, 1, false,
                "Hold-to-zoom on the key bound in Controls.");
        add("zoomFactor", Category.GENERAL, false, 2, 10, 4, false,
                "How far the zoom key narrows the field of view. 4 means a quarter of it.");
        add("animatedTextures", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Update animated block textures. Off skips the per-tick frame uploads for every animated sprite.");
        add("backgroundFpsLimit", Category.OPTIMIZATION, false, 0, 60, 10, false,
                "Framerate cap while the game window is not active. 0 disables the cap.");
        add("buildNearOffThread", Category.OPTIMIZATION, true, 0, 1, 0, false,
                "Queue a chunk that changed close to you for a builder thread instead of ");
        add("cacheBlockEntityModels", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Record the primed TNT cube once and replay it, instead of looking the model up ");
        add("chunkBuildThreads", Category.OPTIMIZATION, false, 0, 64, 0, false,
                "How many threads build chunk geometry. 0 keeps vanilla's count, which it derives from ");
        add("chunkPreload", Category.OPTIMIZATION, true, 0, 1, 0, false,
                "Let chunks outside the view be rebuilt. Vanilla only ever schedules chunks that are ");
        add("dropVanillaBuffers", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Stop filling the game's own chunk buffers once Vulkan has the geometry. The world ");
        add("entityDistance", Category.OPTIMIZATION, false, 0, 256, 0, false,
                "Stop drawing entities past this many blocks. 0 keeps vanilla's per-entity limit.");
        add("explosionParticles", Category.OPTIMIZATION, false, 0, 20000, 0, false,
                "How many particles one tick's explosions may spawn before they are thinned. ");
        add("fastFrustumTest", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Decide whether a box is off screen from its far corner rather than from all ");
        add("fastRebuildNear", Category.OPTIMIZATION, true, 0, 1, 0, false,
                "Hand the last loop of the terrain setup only the chunks it can act on. That loop ");
        add("groupQuadFacings", Category.OPTIMIZATION, true, 0, 1, 1, true,
                "Sort each chunk's faces by which way they point, so the ones a camera cannot ");
        add("materialTags", Category.OPTIMIZATION, true, 0, 1, 0, false,
                "Record what each stretch of a chunk's geometry is made of while the chunk is ");
        add("nearPlaneHundredths", Category.OPTIMIZATION, false, 0, 50, 10, false,
                "Near clipping plane in hundredths of a block. 0 keeps vanilla's 0.05, which at long ");
        add("ownVisibilityWalk", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Run this mod's own chunk visibility search instead of the game's. Same answer, ");
        add("shortEntitySections", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Hand the entity and block-entity passes only the sections that can hold ");
        add("shortLayerSections", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Give the game's own layer filter the sections that hold blocks instead of every ");
        add("smartAnimations", Category.OPTIMIZATION, true, 0, 1, 0, false,
                "Update only the animated block textures that are actually on screen. Vanilla ");
        add("tileEntityDistance", Category.OPTIMIZATION, false, 0, 128, 0, false,
                "Stop drawing chests, signs and other block entities past this many blocks. 0 keeps vanilla's.");
        add("visibilitySeedCache", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Reuse the seed of the chunk visibility search while the camera stays in the same ");
        add("vulkanParticles", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Draw particles with Vulkan. The game still decides where every particle is and ");
        add("vulkanTranslucent", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Draw water and glass in Vulkan rather than leaving them on the OpenGL path. Not ");
        add("vulkanWeather", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "The same for rain and snow. A separate switch from the one above so that either ");
        add("atlasPixelsSeen", Category.ADVANCED, false, 0, 65536, 0, true,
                "Remembered, not set: how many pixels across the block atlas was last time. ");
        add("blockLightRadius", Category.ADVANCED, false, 4, 50, 24, false,
                "How far around you light-emitting blocks are looked for, in blocks. The search ");
        add("compactVertices", Category.ADVANCED, true, 0, 1, 1, true,
                "Pack each chunk vertex into 16 bytes instead of the 28 the game uses. The ");
        add("cullingEnabled", Category.ADVANCED, true, 0, 1, 1, true,
                "Skip triangles facing away from the camera. Off is for diagnosing geometry only.");
        add("depthBlitEnabled", Category.ADVANCED, true, 0, 1, 1, true,
                "Copy Vulkan depth into the game's depth buffer with glBlitFramebuffer instead of a shader.");
        add("entityCapture", Category.ADVANCED, true, 0, 1, 0, false,
                "Read what the game draws for every creature, and draw none of it. The first step ");
        add("flatBlockColours", Category.ADVANCED, true, 0, 1, 0, true,
                "Draw every block face in one flat colour by reading the smallest level of the ");
        add("frameGraphCorner", Category.ADVANCED, false, 0, 3, 0, false,
                "Which corner the frame time graph sits in. The default is the bottom left, ");
        add("framesInFlight", Category.ADVANCED, false, 1, 3, 2, true,
                "How many terrain frames the CPU may run ahead of the GPU. Higher smooths out stalls ");
        add("geometryBudgetMiB", Category.ADVANCED, false, 0, 8192, 0, true,
                "VRAM in MiB the chunk geometry buffer may take before growth becomes cautious. ");
        add("motionOverWorld", Category.ADVANCED, true, 0, 1, 0, true,
                "Show the motion over a dim ghost of the world instead of over black. Black ");
        add("rayTracing", Category.ADVANCED, true, 0, 1, 0, false,
                "Build acceleration structures over the terrain, which is what a ray needs ");
        add("rayTracingRadius", Category.ADVANCED, false, 32, 256, 96, false,
                "How far from you the terrain carries the structures a ray can hit, in blocks. ");
        add("settingsRevision", Category.ADVANCED, false, 0, 1000, 0, false,
                "Which changed defaults have already been applied to this file. Not a setting.");
        add("showAccumulation", Category.ADVANCED, true, 0, 1, 0, true,
                "Diagnostic: paint each pixel by how much of its history it kept instead of by ");
        add("showCreatureLight", Category.ADVANCED, true, 0, 1, 0, true,
                "Paint creatures with the shading term on its own, flat grey, and nothing else. ");
        add("showMaterials", Category.ADVANCED, true, 0, 1, 0, true,
                "Paint the terrain by what it is made of instead of by its texture: water blue, ");
        add("showMotion", Category.ADVANCED, true, 0, 1, 0, true,
                "Paint the world with how each pixel moved since the last frame instead of with ");
        add("showOcclusion", Category.ADVANCED, true, 0, 1, 0, true,
                "Draw the ambient occlusion on its own, as flat grey, instead of applying it to ");
        add("showReflections", Category.ADVANCED, true, 0, 1, 0, true,
                "Paint the water with what the reflected ray found and nothing else: no fresnel ");
        add("ultraLog", Category.ADVANCED, true, 0, 1, 0, false,
                "Write a detailed diagnostics report to logs/vulkanmodnext-diagnostics.log.");
        add("ultraLogSeconds", Category.ADVANCED, false, 1, 120, 10, false,
                "Seconds between diagnostics snapshots.");
        add("vulkanDevice", Category.ADVANCED, false, -1, 7, -1, true,
                "Which GPU Vulkan renders on, by the number the log gives it. -1 chooses ");
        add("vulkanEntities", Category.ADVANCED, true, 0, 1, 1, false,
                "Draw creatures through Vulkan instead of letting the game draw them. ");
    }
}
