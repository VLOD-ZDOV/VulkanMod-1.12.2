package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;

/**
 * Named starting points for the settings screen.
 *
 * A preset is a one-shot write, not a mode: it sets a group of options and
 * then stops existing, so anything changed afterwards simply stays changed.
 * That is why the rows are buttons rather than a selector — a selector would
 * keep claiming a preset is active after it stopped being true.
 *
 * Every preset writes the whole set of values it owns, never a subset. That
 * sounds like bookkeeping and is the difference between a preset and a trap:
 * one that only sets what it wants to lower cannot undo the one applied before
 * it, so going back up the list left the world looking like the heaviest preset
 * anyone had tried that session, with no row on the screen admitting it. The
 * lists below are therefore deliberately repetitive.
 *
 * Render distance is the one value treated differently: every preset caps it
 * and none of them raises it, because raising a distance somebody chose takes
 * frames away without saying so. Beautiful used to be the exception and set
 * thirty-two outright, on the theory that asking for the best-looking world
 * and being left at eight chunks was the failure in the other direction. It
 * was not: measured, the distance bought that preset nothing — its effects are
 * paid per pixel — while the chunk rebuilding at thirty-two is exactly what
 * turns a four-millisecond frame into a thirty-millisecond one every twentieth
 * frame, which is the stutter the preset was blamed for.
 */
public final class VulkanPresets {

    private VulkanPresets() {
    }

    /**
     * The complete set of what a preset decides. Named fields rather than a row
     * of arguments, because fifteen numbers in a row is how the wrong two end up
     * swapped.
     */
    private static final class Look {
        int entityDistance;
        int tileEntityDistance;
        int backgroundFps;
        boolean animations;
        boolean flatBlockColours;
        int framesInFlight;
        int chunkBuildThreads = -1;   // -1 leaves the setting alone
        /**
         * Video memory the chunk buffer may take before growth turns cautious,
         * in MiB. -1 leaves it on automatic, which is a quarter of what the
         * device reports.
         *
         * Automatic is right wherever the card has memory of its own. On
         * integrated graphics it is not memory of its own — it is the system's,
         * the same pool the game's heap comes out of, and a quarter of it is a
         * quarter of what the machine has to run everything else in.
         */
        int geometryBudgetMiB = -1;

        int particles;
        boolean fancy;
        int ambientOcclusion;
        int clouds;
        boolean entityShadows;
        /**
         * Whether creatures are drawn here rather than by the game.
         *
         * A preset has to have an opinion on this one, and until it did the
         * shipped default reached every preset including the two that exist to
         * give things up. It costs the processor — the pose is worked out there
         * instead of on the card — and returns no frames at all; what it buys
         * is a shadow of the creature's own shape and a creature that exists in
         * this renderer's depth. Both of those are things the fast presets have
         * already switched off, so on them it is cost without purchase.
         */
        boolean vulkanEntities;
        int mipmap;
        /** Never raised above what the player chose. */
        int renderDistanceCap;
        /** Set outright rather than capped; -1 to use the cap instead. */
        int renderDistanceExact = -1;
        int fpsLimit;
        boolean vsync;

        // The effects this mod adds on top of the world. Every preset states
        // all of them for the same reason it states everything else: one that
        // only turns effects on cannot be undone by one that never heard of
        // them, and a bloom left burning after switching to Performance is
        // exactly the complaint this class exists to stop.
        int directionalLight = VulkanConfig.DEF_DIRECTIONAL_LIGHT;
        int heightFog;
        int heightFogDepth = VulkanConfig.DEF_HEIGHT_FOG_DEPTH;
        int waterReflection;
        int waterWaves;
        int foliageSway;
        int bloom;
        int screenReflections;
        int shaderAmbientOcclusion;
        int aoRadius = VulkanConfig.DEF_AO_RADIUS;

        // The rule above was written and then broken: everything added to this
        // mod after it was never filed here, so the preset that means "show me
        // what this can do" went on setting eight effects out of eighteen and
        // left the rest wherever they were — which for a fresh install is off.
        // Reported as "the beautiful preset did nothing, it all looked
        // ordinary", and it was doing exactly what it said, which was not much.
        //
        // Material tags first, because they are not an effect: they are what
        // records which block a vertex came from, and swaying, bloom, occlusion
        // and every water and ice effect are switched on but inert without
        // them. A preset that turns on the effects and not the tags is the one
        // way to get all of the cost and none of the picture.
        boolean materialTags;
        boolean dynamicLights;
        int sceneTone;
        int sceneWarmth = VulkanConfig.DEF_SCENE_WARMTH;
        int celestialGlint;
        int iceShine;
        int waterCaustics;
        int wetSurfaces;
        int sunHaze;
        int cloudTint;
        int skyGradient;
        boolean sceneOcclusion;
        int leafShadows;
        int contactShadows;
        int creatureLight;
        int cloudShadows;
        int godRays;
        boolean hdrFrame;
        int exposure = 50;
        int waterRefraction;
        boolean roundSun;
        boolean roundMoon;
    }

    /**
     * Everything on, on the assumption that the machine can afford it.
     *
     * The mirror image of Potato, and written second on purpose: a preset that
     * only ever gives things up leaves nothing to come back to. This is what
     * "come back" means.
     *
     * Screen reflections are set well below their maximum. They are the newest
     * and least settled of the effects here, and a preset is the wrong place to
     * show somebody an effect at its most demanding and least finished — the
     * slider is still there for anyone who wants to push it.
     */
    public static void beautiful(Minecraft mc) {
        Look look = new Look();
        look.entityDistance = 256;
        look.tileEntityDistance = 128;
        look.backgroundFps = 10;
        look.animations = true;
        look.flatBlockColours = false;
        look.framesInFlight = 3;
        // Set here too, and not only by the presets named for speed. Building
        // chunks costs no pixels, so the size of that pool is not a looks
        // question and never was — and this is the preset that asks for
        // thirty-two chunks of render distance, which is the setting that
        // makes the pool the thing the frame waits for. Left alone it came
        // out of vanilla's arithmetic over the heap: measured in a heavy pack
        // at this distance, half the frames took four milliseconds and one in
        // twenty took thirty, which reads as a stutter rather than as the two
        // hundred frames the average claims.
        look.chunkBuildThreads = VulkanConfig.coresForChunkBuilding();
        look.particles = 0;
        look.fancy = true;
        look.ambientOcclusion = 2;
        look.clouds = 2;
        look.entityShadows = true;
        look.vulkanEntities = true;
        look.mipmap = 4;
        // Twelve, and a cap rather than an exact number.
        //
        // Thirty-two was this preset's own idea of "the machine can afford it",
        // and on a card that can it still stutters: at that distance the frame
        // waits for chunks being rebuilt, not for anything drawn, so half the
        // frames come in at four milliseconds and one in twenty at thirty. The
        // effects here cost per pixel and barely notice the distance, so the
        // distance was buying nothing this preset is for.
        //
        // A cap and not an exact value because the two failures are not
        // symmetric. Coming down from a distance somebody chose costs them
        // sharpness far away and gives back the smoothness they came here for;
        // raising somebody who deliberately sits at eight would spend their
        // frames on chunks they did not ask for and hide the effects behind a
        // stutter.
        look.renderDistanceCap = 12;
        look.fpsLimit = 260;
        look.vsync = false;

        // Without this line every effect below is switched on and does nothing.
        look.materialTags = true;
        look.dynamicLights = true;
        look.directionalLight = 65;
        look.heightFog = 20;
        look.waterReflection = 70;
        look.waterWaves = 50;
        // Quieter than it was: at fifty-five a field reads as wind rather
        // than as grass, and the point of the preset is a world that looks
        // right standing still as well as in motion.
        look.foliageSway = 38;
        look.bloom = 45;
        // Off in the preset named for looks, and this is the honest thing to do.
        //
        // Every description this project ships already says screen reflections
        // are unfinished — nothing off the edge of the frame, nothing behind
        // anything nearer — and then the preset called Beautiful switched them
        // on anyway. So the one effect we know is not ready was the one
        // deciding what the mod looks like on a first run: the white patch that
        // stretches as the camera rises, and the field of dither with a hard
        // straight edge beside it, are both the march and both in every
        // screenshot anyone has taken of this.
        //
        // What replaces it is not nothing. The Fresnel term still turns the
        // surface into a mirror at a grazing angle, and what it now mirrors is
        // the sky along the reflected ray rather than one flat colour. That is
        // a real reflection of a real sky; the march was a real reflection of
        // whatever happened to be on screen, which at a shallow angle is a
        // handful of pixels smeared down the water.
        //
        // The slider is untouched and one row away for anyone who wants it.
        look.screenReflections = 0;
        look.shaderAmbientOcclusion = 60;
        look.sceneTone = 45;
        look.sceneWarmth = 55;
        look.waterRefraction = 45;
        look.celestialGlint = 55;
        look.iceShine = 60;
        look.waterCaustics = 60;
        look.wetSurfaces = 65;
        look.sunHaze = 60;
        look.cloudTint = 70;
        look.skyGradient = 55;
        look.sceneOcclusion = true;
        look.leafShadows = 100;
        look.contactShadows = 60;
        look.creatureLight = 55;
        look.cloudShadows = 45;
        look.godRays = 45;
        look.hdrFrame = true;
        look.roundSun = true;
        look.roundMoon = true;
        apply(mc, look);
    }

    /** Everything this mod owns back to the shipped values; vanilla untouched. */
    public static void stable() {
        VulkanConfig.resetToDefaults();
    }

    /** Caps the draw distances that vanilla leaves far wider than anyone can see. */
    public static void balanced(Minecraft mc) {
        Look look = new Look();
        look.entityDistance = 128;
        look.tileEntityDistance = 64;
        look.backgroundFps = 10;
        look.animations = true;
        look.flatBlockColours = false;
        look.framesInFlight = 2;
        // Same reasoning as in Beautiful: free of pixels, and this preset caps
        // the render distance at the same thirty-two.
        look.chunkBuildThreads = VulkanConfig.coresForChunkBuilding();
        look.particles = 1;
        look.fancy = true;
        look.ambientOcclusion = 2;
        look.clouds = 2;
        look.entityShadows = true;
        look.vulkanEntities = true;
        look.mipmap = 4;
        look.renderDistanceCap = 32;
        look.fpsLimit = 260;
        look.vsync = false;
        apply(mc, look);
    }

    /** Trades looks for frames: shorter distances, no animation, fewer particles. */
    public static void performance(Minecraft mc) {
        Look look = new Look();
        look.entityDistance = 64;
        look.tileEntityDistance = 32;
        look.backgroundFps = 5;
        look.animations = false;
        look.flatBlockColours = false;
        // A third frame in flight gives the processor more room when it is the
        // thing holding the frame up, which is what this preset assumes.
        look.framesInFlight = 3;
        // Chunk building is what the frame waits for at long render distances,
        // and vanilla sizes that thread pool from the heap rather than from the
        // processor. A preset named for performance is the right place to take
        // the core count seriously; the shipped default still leaves it alone.
        look.chunkBuildThreads = VulkanConfig.coresForChunkBuilding();
        look.particles = 2;
        look.fancy = false;
        look.ambientOcclusion = 0;
        look.clouds = 0;
        look.entityShadows = false;
        look.vulkanEntities = false;
        look.mipmap = 4;
        look.renderDistanceCap = 16;
        look.fpsLimit = 260;
        look.vsync = false;
        apply(mc, look);
    }

    /**
     * For a machine that cannot run this game well, on the assumption that
     * sixty frames is the goal and everything else is negotiable.
     *
     * The other presets tune; this one gives things up. It is separate from
     * Performance because the two answer different questions: Performance asks
     * what can be spared to go faster on a capable machine, this one asks what
     * has to go for the game to be playable at all. On a laptop with shared
     * memory and a sixty-hertz screen, frames above sixty are not a gain — they
     * are heat and fan noise for pictures nobody sees, which is why this is the
     * one preset that puts a ceiling on rather than taking one off.
     */
    public static void potato(Minecraft mc) {
        Look look = new Look();
        look.entityDistance = 32;
        look.tileEntityDistance = 16;
        // One frame a second out of focus. The game keeps running; the card
        // stops being asked to draw a menu nobody is looking at.
        look.backgroundFps = 1;
        look.animations = false;
        // Flat colours instead of textures, with the mip chain deliberately
        // left switched on — it is what flat colours are made of. Turning
        // mipmaps off is the obvious-looking way to make textures cheap and it
        // does the reverse: distant chunks then read the full-size atlas at
        // random, which is what a texture cache is worst at. The sampler is
        // pinned near the end of that same chain instead, where a sprite is a
        // handful of texels and the whole atlas fits in cache — one level short
        // of the end, because at the end an ore block averages out into stone.
        look.flatBlockColours = true;
        // Two, not three. Frames in flight buy the processor room when it is
        // the thing holding the frame up — on this class of machine the card
        // is, and a third frame only adds a frame of delay to the controls.
        look.framesInFlight = 2;
        // One fewer than the machine has, and only here.
        //
        // Every other preset takes every core, which is what vanilla does and
        // is right where there are cores to spare: a build thread that is not
        // needed costs nothing. On two cores there are none to spare, and two
        // build threads leave the thread that actually draws the frame with no
        // core of its own — competing with them and with the game's own logic
        // for the same two. The chunk that arrives a moment later is not the
        // thing being noticed on this machine; the frame that did not is.
        look.chunkBuildThreads = Math.max(1, VulkanConfig.coresForChunkBuilding() - 1);
        // A floor rather than a quarter of what the device claims.
        //
        // The device claiming it is integrated: what it reports as its own
        // memory is the system's, and a quarter of that is a quarter of the two
        // gigabytes this game was given in the first place. At eight chunks
        // there is nothing like that much geometry to hold — the whole visible
        // world fits inside the smallest buffer this allocator will make.
        look.geometryBudgetMiB = 256;
        look.particles = 2;
        look.fancy = false;
        look.ambientOcclusion = 0;
        look.clouds = 0;
        look.entityShadows = false;
        look.vulkanEntities = false;
        look.mipmap = 4;
        look.renderDistanceCap = 8;
        look.fpsLimit = 60;
        look.vsync = true;
        apply(mc, look);
    }

    /**
     * Writes one complete look and rebuilds only what has to be rebuilt.
     *
     * Graphics quality, smooth lighting, the render distance and the mipmap
     * level are baked into chunk geometry or into the atlas, so changing them
     * means nothing until those are made again — and making them again is the
     * expensive part, which is why it happens once at the end and only if one
     * of those four actually moved.
     */
    private static void apply(Minecraft mc, Look look) {
        VulkanConfig.setTerrainEnabled(true);
        VulkanConfig.setEntityDistance(look.entityDistance);
        VulkanConfig.setTileEntityDistance(look.tileEntityDistance);
        VulkanConfig.setBackgroundFpsLimit(look.backgroundFps);
        VulkanConfig.setAnimationsEnabled(look.animations);
        VulkanConfig.setFlatBlockColours(look.flatBlockColours);
        VulkanConfig.setDepthBlitEnabled(true);
        VulkanConfig.setCullingEnabled(true);
        VulkanConfig.setFramesInFlight(look.framesInFlight);
        VulkanConfig.setGeometryBudgetMiB(0);
        VulkanConfig.setDirectionalLight(look.directionalLight);
        VulkanConfig.setHeightFog(look.heightFog);
        VulkanConfig.setHeightFogDepth(look.heightFogDepth);
        VulkanConfig.setWaterReflection(look.waterReflection);
        VulkanConfig.setWaterWaves(look.waterWaves);
        VulkanConfig.setFoliageSway(look.foliageSway);
        VulkanConfig.setBloom(look.bloom);
        VulkanConfig.setScreenReflections(look.screenReflections);
        VulkanConfig.setAmbientOcclusion(look.shaderAmbientOcclusion);
        VulkanConfig.setAoRadius(look.aoRadius);
        VulkanConfig.setVulkanEntities(look.vulkanEntities);
        VulkanConfig.setMaterialTags(look.materialTags);
        VulkanConfig.setDynamicLights(look.dynamicLights);
        VulkanConfig.setSceneTone(look.sceneTone);
        VulkanConfig.setSceneWarmth(look.sceneWarmth);
        VulkanConfig.setCelestialGlint(look.celestialGlint);
        VulkanConfig.setIceShine(look.iceShine);
        VulkanConfig.setWaterCaustics(look.waterCaustics);
        VulkanConfig.setWetSurfaces(look.wetSurfaces);
        VulkanConfig.setSunHaze(look.sunHaze);
        VulkanConfig.setCloudTint(look.cloudTint);
        VulkanConfig.setSkyGradient(look.skyGradient);
        VulkanConfig.setSceneOcclusion(look.sceneOcclusion);
        VulkanConfig.setLeafShadows(look.leafShadows);
        VulkanConfig.setContactShadows(look.contactShadows);
        VulkanConfig.setCreatureLight(look.creatureLight);
        VulkanConfig.setCloudShadows(look.cloudShadows);
        VulkanConfig.setGodRays(look.godRays);
        VulkanConfig.setHdrFrame(look.hdrFrame);
        VulkanConfig.setExposure(look.exposure);
        VulkanConfig.setWaterRefraction(look.waterRefraction);
        VulkanConfig.setRoundSun(look.roundSun);
        VulkanConfig.setRoundMoon(look.roundMoon);
        if (look.geometryBudgetMiB >= 0) {
            VulkanConfig.setGeometryBudgetMiB(look.geometryBudgetMiB);
        }
        if (look.chunkBuildThreads > 0) {
            VulkanConfig.setChunkBuildThreads(look.chunkBuildThreads);
        }

        GameSettings settings = mc.gameSettings;
        boolean wasFancy = settings.fancyGraphics;
        int wasAo = settings.ambientOcclusion;
        int wasDistance = settings.renderDistanceChunks;
        int wasMipmap = settings.mipmapLevels;

        settings.particleSetting = look.particles;
        settings.fancyGraphics = look.fancy;
        settings.ambientOcclusion = look.ambientOcclusion;
        settings.clouds = look.clouds;
        settings.entityShadows = look.entityShadows;
        settings.limitFramerate = look.fpsLimit;
        settings.enableVsync = look.vsync;
        if (look.renderDistanceExact > 0) {
            settings.renderDistanceChunks = look.renderDistanceExact;
        } else if (settings.renderDistanceChunks > look.renderDistanceCap) {
            settings.renderDistanceChunks = look.renderDistanceCap;
        }
        if (settings.mipmapLevels != look.mipmap) {
            // Through the game's own setter rather than the field: it rebinds
            // the atlas, sets the filtering and raises the flag Forge added to
            // stop the models being rebuilt once per notch of the slider.
            // Writing the field alone changes the number and nothing else.
            settings.setOptionFloatValue(GameSettings.Options.MIPMAP_LEVELS, look.mipmap);
            // That flag is only acted on when a settings screen closes, and
            // this was applied from a button in the middle of ours.
            settings.onGuiClosed();
        }
        settings.saveOptions();

        if (mc.renderGlobal != null
                && (settings.fancyGraphics != wasFancy
                    || settings.ambientOcclusion != wasAo
                    || settings.renderDistanceChunks != wasDistance
                    || settings.mipmapLevels != wasMipmap)) {
            mc.renderGlobal.loadRenderers();
        }
    }
}
