package net.vulkanmod112;

/**
 * Boundary between the game (LWJGL 2 world) and the Vulkan renderer
 * (LWJGL 3 world, loaded in an isolated classloader). Implementations live in
 * net.vulkanmod112.vkimpl and must only be instantiated via VulkanLoader.
 *
 * No org.lwjgl types may ever appear in these signatures: the two sides see
 * different, incompatible org.lwjgl.* classes.
 */
public interface VulkanBridge {

    /** Initializes the Vulkan instance, device and graphics queue. */
    void init();

    /** Releases all Vulkan resources. Safe to call more than once. */
    void destroy();

    boolean isInitialized();

    /** Human-readable summary of the selected GPU, for logs and the F3 screen. */
    String gpuSummary();

    /**
     * Total device-local memory in MiB, or 0 before the device is selected.
     * Drives the automatic geometry budget: this is a hardware fact the game
     * side has no other way to learn.
     */
    int vramMegabytes();

    /**
     * MiB of chunk geometry mirrored into Vulkan right now, or 0 with no world.
     *
     * Read by the estimate in the settings screen, which is why it is a
     * measurement rather than a calculation. How much a world costs depends on
     * what is in it — a plains biome and a cave system at the same render
     * distance are not close — so a figure derived from the render distance
     * alone would be confidently wrong. Anchored to what this player's world
     * actually uses, the same arithmetic becomes a fair prediction of what
     * changing a setting would do to it.
     */
    int geometryMegabytes();

    /**
     * Renders the demo scene offscreen on the GPU via Vulkan and returns the
     * frame as tightly packed RGBA8 pixels ({@code width * height * 4} bytes,
     * top row first). Direct java.nio buffers are safe to pass across the
     * classloader boundary.
     */
    java.nio.ByteBuffer renderDemo(int width, int height);

    /**
     * Sets up the zero-copy Vulkan→OpenGL image sharing (VRAM image visible
     * to both APIs, GPU-side semaphore sync). Must be called on the client
     * thread with the game's GL context current. Returns false when the
     * drivers lack the required extensions — callers should fall back to
     * {@link #renderDemo}.
     */
    boolean initInterop(int width, int height);

    /** GL texture id of the shared image, or -1 before {@link #initInterop}. */
    int interopTextureId();

    /**
     * Renders the next frame into the shared image on the Vulkan queue and
     * enqueues the GL-side wait; after this call the game may draw the
     * texture. Client thread only.
     */
    void renderInteropFrame(float timeSeconds);

    /** Signals Vulkan that the frame was displayed; call after drawing. */
    void interopFrameDisplayed();

    /**
     * Mirrors a game VBO upload into a Vulkan vertex buffer, keyed by the GL
     * buffer id. Client thread only.
     *
     * {@code data} is the game's own buffer, positioned at the payload, and
     * the game uploads it to OpenGL immediately afterwards. Implementations
     * must treat it as read-only and must not change its position or limit.
     */
    void mirrorChunkBuffer(int slot, java.nio.ByteBuffer data);

    /** Frees the Vulkan mirror of a deleted game VBO. */
    void releaseChunkBuffer(int slot);

    /**
     * Mirrors a chunk from the thread that built it, before the render thread
     * ever sees it. Returns false when that was not possible, in which case the
     * caller must leave the ordinary {@link #mirrorChunkBuffer} path to do the
     * work — a refusal is never an error.
     *
     * Same contract on {@code data} as mirrorChunkBuffer: read only, position
     * and limit untouched.
     */
    boolean stageChunkBuffer(int slot, java.nio.ByteBuffer data);

    /**
     * Says what a chunk layer's vertices are made of, ahead of the geometry.
     *
     * {@code runs} is pairs of ints — one past the last vertex of a stretch,
     * and the material of that stretch — of which {@code runCount} are in use.
     * The array belongs to the caller and is copied here; only primitives cross
     * the bridge, which is what keeps the two class loaders apart.
     *
     * Called before the geometry it describes, from whichever thread built the
     * chunk. Sending nothing for a slot is always allowed and means "plain".
     */
    void stageChunkMaterials(int slot, int[] runs, int runCount);

    /**
     * Where in the block atlas each recognised material's texture sits.
     *
     * {@code rects} is four floats per entry — minU, minV, maxU, maxV — and
     * {@code materials} says what each rectangle stands for. Sent after every
     * atlas upload, because stitching decides afresh where a sprite lands.
     *
     * This is how the translucent layer knows what it is made of: the game
     * reorders that layer's quads whenever the camera moves, so a label
     * attached to a vertex describes the wrong surface afterwards, while the
     * texture coordinates move with the quad they belong to.
     */
    void setMaterialSprites(int[] materials, float[] rects, int count);

    /** One-line mirror statistics for the F3 screen. */
    String chunkMirrorStats();

    /**
     * Multi-line dump of everything the Vulkan side knows about itself: device,
     * chosen formats, active code paths, resource counts and the latest frame
     * timings. Plain text so the bridge stays free of LWJGL types.
     */
    String diagnosticsReport();

    /**
     * Copies the game's block atlas (a GL texture) into a Vulkan image.
     * Call on the client thread after texture stitching / resource reloads.
     */
    void updateAtlas(int atlasGlTextureId);

    /**
     * Replaces rectangles of the block atlas with animation frames the game
     * just produced.
     *
     * Two flat arrays because only primitives and arrays may cross this
     * boundary. {@code header} holds six ints per rectangle — mip level, x, y,
     * width, height, and where its pixels begin in {@code pixels} — and the
     * pixels are the game's own 0xAARRGGBB, converted on the far side.
     */
    void updateAtlasRegions(int[] header, int headerCount, int[] pixels, int pixelCount);

    /**
     * Draws the terrain's glow into the game's frame, once the game has drawn
     * everything else in the world into it.
     *
     * The frame is handed in as an OpenGL texture because the glow is worked
     * out from it: a light with a creature standing in front of it is not
     * visible in the finished picture, and so must not spill. Does nothing when
     * bloom is off or when no glow was prepared this frame.
     */
    void applySceneBloom(int sceneGlTexture);

    /**
     * Darkens the corners of the whole picture, not only of the blocks.
     *
     * The occlusion this renderer already had is computed inside its own pass,
     * from a depth image holding terrain and nothing else — so a chest, a mob
     * or a modded block casts nothing into the floor it stands on, however
     * plainly it is standing there. By this point the game has finished the
     * world, and its depth buffer holds every one of them.
     */
    void applySceneOcclusion(int sceneGlTexture);

    /**
     * Grades the finished frame — terrain, creatures, particles, weather and
     * water together — where all of it exists at once. Does nothing at zero.
     */
    void applySceneTone(int sceneGlTexture);

    /**
     * Whether the tone pass can still resolve a floating frame back into
     * range, or has failed for good this session.
     *
     * True until a driver refuses the blit the tone pass needs, or its
     * targets fail to come up. Whatever decides the game's own frame format
     * has to ask this: a floating frame with a dead tone pass burns every
     * highlight above white instead of drawing them.
     */
    boolean isSceneToneAvailable();

    /**
     * The renderer's depth image as an OpenGL texture, for the game to use as
     * its own depth attachment — or 0 when that is not on offer.
     *
     * The frame used to carry a full screen of depth across twice: out, so the
     * game's creatures would be hidden behind hills, and back, so the water
     * would be hidden behind the creatures. If the game's depth buffer simply
     * <em>is</em> this image, neither copy has anywhere to go.
     *
     * Returns 0 until the renderer's targets exist and match the size asked
     * for, so ask every frame rather than once. The size is checked here rather
     * than by the caller because a window being resized spends a frame or two
     * with the two halves disagreeing, and an attachment of the wrong size is
     * a framebuffer that renders into part of itself.
     */
    int sharedDepthTexture(int width, int height);

    /**
     * Whether OpenGL took the offer above and kept a complete framebuffer.
     *
     * Called from the top of the world pass, where a frame is going to be
     * submitted — which is what lets the "no" case hand the images back one
     * last time for that submit to wait on.
     */
    void depthSharingAccepted(boolean accepted);

    /**
     * The same "no", from a frame this renderer is not going to draw at all.
     *
     * Kept apart from the call above because the difference is the whole of it:
     * with no submit there is nothing waiting for the images, and handing them
     * back anyway leaves a signal that the next frame consumes instead of the
     * one meant for it.
     */
    void depthSharingDropped();

    /**
     * Hands the shared images back to Vulkan, at the top of the world pass.
     *
     * Does nothing unless the depth is shared. When it is, this is the one
     * moment in the loop that is after everything the game drew into the depth
     * last frame and before anything this renderer records into it this frame,
     * which makes it the only correct place to say so.
     */
    void beginFrameDepthHandover();

    /** Tells the Vulkan side which GL texture holds the 16x16 lightmap. */
    void setLightmap(int lightmapGlTextureId);

    /**
     * Hands over the game's CPU-side lightmap colors (256 ARGB ints). Avoids
     * a per-frame glGetTexImage pipeline stall; the array is read once per
     * frame at terrain render time.
     */
    void updateLightmapData(int[] argb);

    /**
     * Hands over the fixed-function fog the game has set up for this frame:
     * {r, g, b, mode, start, end, density}, where mode is 0 for off, 1 linear,
     * 2 exponential and 3 exponential squared.
     *
     * Without this the terrain is the only thing in the scene drawn without
     * fog, which is most visible underwater — entities take the colour of the
     * water while the blocks behind them stay clear.
     */
    void updateFogState(float[] fog);

    /**
     * Light sources near the camera, four floats each: position relative to the
     * camera, then the vanilla light level the source emits.
     *
     * They are added by the terrain shader while it shades, rather than written
     * into the world and rebuilt into chunk geometry the way the game itself
     * would do it. Rebuilding chunks is what the frame is already waiting on
     * when the player moves, so a light that travels with them is the last
     * thing to pay for that way.
     */
    void updateDynamicLights(float[] lights, int count);

    /**
     * Draws one terrain layer with Vulkan. Layer ordinals follow
     * BlockRenderLayer: 0 SOLID, 1 CUTOUT_MIPPED, 2 CUTOUT, 3 TRANSLUCENT.
     * SOLID begins the frame, CUTOUT submits it and composites color+depth
     * into the game's framebuffer. {@code chunks} packs [slot, x, y, z]
     * per chunk. Returns true when Vulkan took the layer (GL must skip it).
     */
    boolean renderTerrainLayer(int layerOrdinal, int[] chunks, int chunkCount, float[] mvp,
                               double viewX, double viewY, double viewZ, int fbWidth, int fbHeight);

    /**
     * Whether the translucent layer can go through Vulkan at all on this
     * machine. False until the render targets exist, and false for good on a
     * driver with no sampleable 24-bit depth — every AMD card — because the
     * pass has no way to borrow the game's depth back without it.
     *
     * <p>Anything that stops the game filling its own chunk buffers has to ask
     * first: water and glass live only there, so dropping them while this
     * returns false leaves nobody drawing them at all.
     */
    boolean drawsTranslucent();

    /**
     * Which way the sun is, in the same camera-relative axes the terrain is
     * drawn in, plus how strongly its shadow should be believed.
     *
     * Four floats: direction x, y, z and strength. Sent every frame because the
     * sun moves; costs nothing, and the alternative is the renderer keeping its
     * own clock and drifting from the sky the game draws.
     */
    void updateSun(float[] sun);

    /**
     * How hard it is raining where the camera is, from 0 to 1.
     *
     * Kept apart from the fog state even though both arrive per frame and both
     * describe the weather: fog is read out of OpenGL, where the game has
     * already put it, and this is not in OpenGL at all. It is here because the
     * shader cannot ask the world anything.
     */
    void updateWeather(float rainStrength, int seaLevel);

    /**
     * Where the game's own clouds are, so that they can throw a shadow.
     *
     * Every number here is the game's rather than this mod's, and that is the
     * whole point of the effect: the shadow is cast by sampling the very
     * texture the clouds are drawn from, at the place the game's own
     * arithmetic puts it. A pattern of our own would drift away from what is
     * overhead, and a shadow that does not match its cloud reads as a fault
     * rather than as weather.
     *
     * @param glTexture   the cloud sheet, as OpenGL knows it, or 0 for none
     * @param height      the height the clouds hang at, in world blocks
     * @param driftBlocks how far they have drifted along x since the world began
     */
    void updateClouds(int glTexture, float height, float driftBlocks);

    /**
     * Whether particles and weather can go through Vulkan on this machine.
     *
     * Always false where {@link #drawsTranslucent} is false, and for the same
     * reason it is one question rather than two: sprites are drawn inside the
     * translucent pass, and there is no second place to put them that would not
     * cost another round trip of the game's depth.
     */
    boolean drawsSprites();

    /**
     * Why rays are or are not being traced this session, in one sentence.
     *
     * Needed on this side of the bridge and not only in the report, because
     * the answer is not the setting. Acceleration structures have to be asked
     * for when the Vulkan device is created, so the switch takes effect at the
     * next start and everything that depends on it — sun shadows, traced
     * light, traced block light — quietly does nothing until then. A tester
     * turned all four on and reported that torches cast no shadow; they were
     * describing this exactly.
     */
    String rayTracingStatus();

    /** Whether rays can actually be traced right now, whatever the setting says. */
    boolean isRayTracingActive();

    /**
     * Whether this frame's creatures really are in the acceleration structure.
     *
     * Tracing being on says a ray can be fired; it does not say there is
     * anything for the ray to hit. The creatures go in as one structure over
     * one run of vertices, and that run can be missing for reasons that have
     * nothing to do with the settings: none drawn by this renderer yet, a run
     * broken up by particles, or a structure budget with no room left in it.
     * Whatever takes vanilla's round shadow away has to ask this as well, or a
     * mob stands on nothing at all — which is worse than the circle.
     */
    boolean creaturesInStructure();

    /**
     * Copies one of the game's sprite sheets into Vulkan, by slot.
     *
     * Slot 0 is the block atlas and is never sent here — it is already in
     * Vulkan for the terrain and is shared rather than copied. Slots 1 upwards
     * are the particle sheet, rain and snow. Call after a resource reload: the
     * game hands out fresh GL names then, and the old copies are last pack's.
     */
    void updateSpriteTexture(int slot, int glTextureId);

    /**
     * The sprite slot holding an OpenGL texture, copying it in on first sight.
     *
     * Zero means there is no room, and the caller is expected to leave that
     * creature to the game rather than to lose it.
     */
    int spriteSlotForTexture(int glTextureId);

    /**
     * Hands over one batch of camera-facing quads for this frame.
     *
     * {@code vertices} is the game's own buffer in its
     * PARTICLE_POSITION_TEX_COLOR_LMAP layout — position, texture, colour,
     * light map, 28 bytes a vertex — positioned at the payload, and it is read
     * without its position or limit being touched, like every other buffer that
     * crosses this boundary.
     *
     * Batches are drawn in the order they arrive, which is the whole of what
     * decides what ends up over what: the pass they are drawn in borrows the
     * game's depth read-only, so nothing in it occludes anything else in it.
     *
     * {@code alphaCutoff} is the game's own alpha test for this batch, and the
     * two values differ: particles cut at one 255th, weather at a tenth.
     */
    /**
     * @return false when the renderer could not take the batch, so that the
     *         caller draws it the way the game would have. It used to return
     *         nothing, drop what would not fit and leave the caller believing
     *         it had been drawn — which is how a whole rain field could vanish.
     */
    boolean submitSprites(java.nio.ByteBuffer vertices, int vertexCount, int spriteSlot,
                          float alphaCutoff);

    /**
     * The same, with a colour laid over the skin.
     *
     * @param overlay packed ARGB, where alpha is how much of the colour to use
     *                and zero means none. This is how a creature turns red when
     *                it is hurt: the game does it by rewriting what a texture
     *                unit computes, which is a thing that exists in the fixed
     *                pipeline it draws with and not in the one here.
     * @param glint   true for the shimmer of enchanted armour, which is added
     *                to what is already there rather than covering it, is not
     *                touched by the light map, and must not write depth
     */
    boolean submitSprites(java.nio.ByteBuffer vertices, int vertexCount, int spriteSlot,
                          float alphaCutoff, int overlay, boolean glint);

}
