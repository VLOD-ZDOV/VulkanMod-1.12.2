package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.vulkanmod112.VulkanBridge;
import net.vulkanmod112.VulkanLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * Game-side driver of the Vulkan terrain renderer (stage 3.3).
 *
 * Vanilla draws world geometry in layer order SOLID → CUTOUT_MIPPED → CUTOUT
 * (then entities, then TRANSLUCENT). The Vulkan path accumulates the three
 * opaque-ish layers into one shared VRAM frame and composites it (color +
 * depth) into the game's framebuffer at the CUTOUT call — before entities, so
 * they occlude correctly. TRANSLUCENT stays on the vanilla GL path for now.
 *
 * Any failure permanently falls back to vanilla rendering: losing the world's
 * visuals is never acceptable.
 */
public final class TerrainHooks {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Terrain");

    /** Renderer replacements own the same 1.12 classes as these mixins. */
    private static final String[] INCOMPATIBLE_RENDERER_CLASSES = {
            "optifine.OptiFineForgeTweaker",
            "shadersmod.client.Shaders",
            "shadersmodcore.transform.SMCClassTransformer"
    };
    private static boolean broken;

    /**
     * The bridge, or nothing at all once the device has been lost.
     *
     * There is a difference between "the terrain has stopped drawing" and "the
     * device is gone", and this is what had them confused. The fallback was
     * doing its job — terrain went back to OpenGL and the world stayed on
     * screen — while the animation upload went on handing frames to a device
     * that no longer existed, and the next submission it made turned a failure
     * the game had survived into a crash report. A lost device is lost for
     * everything, so everything asks through here.
     */
    static VulkanBridge liveBridge() {
        if (broken) {
            return null;
        }
        return VulkanLoader.bridgeIfReady();
    }
    private static boolean compatibilityChecked;
    private static boolean incompatibleRenderer;
    private static boolean atlasUploaded;
    /** Live reference to EntityRenderer.lightmapColors (game updates it in place). */
    private static int[] lightmapColors;

    private static double viewX;
    private static double viewY;
    private static double viewZ;

    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final float[] MV = new float[16];
    private static final float[] PROJ = new float[16];
    private static final float[] MVP = new float[16];
    /** r, g, b, mode, start, end, density — handed to the renderer each frame. */
    private static final float[] FOG = new float[7];
    private static final FloatBuffer FOG_COLOR = BufferUtils.createFloatBuffer(16);

    /** Packed per chunk: mirror slot, blockX, blockY, blockZ. */
    private static int[] chunkData = new int[1024];

    private static long framesDrawn;
    private static int lastChunksDrawn;
    /** Size of the list vanilla handed us for the opaque layer, before we touched it. */
    private static int lastVanillaChunks;

    /**
     * How long vanilla spends drawing the layers we did not take, and how many
     * chunks it drew there. TRANSLUCENT is the whole of it today.
     *
     * This exists to price D2 before building it. Over an ocean at render
     * distance 64 the frame collapses to 71 fps while this renderer draws 554
     * chunks for 0.33 ms of it — everything visible is water, which stays on
     * vanilla GL. Moving that layer into Vulkan removes vanilla's per-chunk
     * draw calls, but it does *not* remove the re-sorting vanilla does when the
     * player moves, because the geometry still has to be built. Which of the
     * two dominates decides whether D2 is worth its complexity, and guessing
     * at it is exactly how the last two days went wrong.
     */
    private static long vanillaLayerStart;
    private static long vanillaLayerNanos;
    private static long vanillaLayerFrames;
    private static int vanillaLayerChunks;

    private TerrainHooks() {
    }

    /** Called by the mixin when vanilla, not Vulkan, is about to draw a layer. */
    public static void beginVanillaLayer(BlockRenderLayer layer, int chunkCount) {
        if (layer != BlockRenderLayer.TRANSLUCENT) {
            vanillaLayerStart = 0L;
            return;
        }
        vanillaLayerChunks = chunkCount;
        vanillaLayerStart = System.nanoTime();
    }

    /** Paired with the above; a cancelled layer never reaches it. */
    public static void endVanillaLayer() {
        if (vanillaLayerStart == 0L) {
            return;
        }
        vanillaLayerNanos += System.nanoTime() - vanillaLayerStart;
        vanillaLayerFrames++;
        vanillaLayerStart = 0L;
    }

    /**
     * Whether the translucent layer reached the Vulkan path, counted.
     *
     * The first attempt at moving it over changed nothing at all, because a
     * leftover guard rejected layer 3 before the new branch could see it, and
     * the only sign was that vanilla went on drawing water exactly as before.
     * That is the failure mode worth a counter: not wrong output, no output.
     */
    private static long translucentTaken;
    private static long translucentRefused;

    /** Milliseconds per frame vanilla spent on the translucent layer, and its chunk count. */
    public static String vanillaLayerStats() {
        String taken = String.format("; translucent to Vulkan %d, refused %d",
                translucentTaken, translucentRefused);
        translucentTaken = 0L;
        translucentRefused = 0L;
        if (vanillaLayerFrames == 0) {
            return "vanilla translucent: not drawn" + taken;
        }
        double perFrame = vanillaLayerNanos / 1_000_000.0 / vanillaLayerFrames;
        String line = String.format("vanilla translucent: %.2f ms per frame over %d frames, %d chunks last frame",
                perFrame, vanillaLayerFrames, vanillaLayerChunks);
        vanillaLayerNanos = 0L;
        vanillaLayerFrames = 0L;
        return line + taken;
    }

    public static void setViewPosition(double x, double y, double z) {
        viewX = x;
        viewY = y;
        viewZ = z;
    }

    /** Marks the block atlas for re-upload (initial stitch and resource reloads). */
    public static void invalidateAtlas() {
        atlasUploaded = false;
        // The particle, rain and snow sheets are reloaded on the same event and
        // handed fresh GL names; copies made from the old ones are last pack's.
        SpriteHooks.forgetSheets();
        // Recorded lists hold texture coordinates, and stitching decides
        // those afresh; keeping them would draw last pack's pixels.
        TntModelCache.forget();
    }

    /**
     * Why the world is not being drawn by Vulkan, or null when it is.
     *
     * Every effect in this mod is code inside this renderer, so this one
     * question decides whether any of them can do anything at all — and it was
     * being answered in three places that could disagree, none of which a
     * player ever sees. A tester watched a switched-on setting do nothing for
     * ninety seconds and reported the setting as broken; the setting was fine
     * and this was the answer nobody had asked for.
     *
     * A sentence rather than a flag, because the four reasons need four
     * different things done about them and "false" says none of that.
     */
    public static String whyNotDrawing() {
        if (!TERRAIN_ALLOWED_BY_PROPERTY) {
            return "the command line switched the Vulkan renderer off for this session";
        }
        if (!VulkanConfig.isTerrainEnabled()) {
            return "Vulkan terrain rendering is off in the settings";
        }
        if (broken) {
            return "the Vulkan renderer failed and the game fell back to OpenGL";
        }
        if (incompatibleRenderer) {
            return "another mod is drawing the world, so the Vulkan renderer stood aside";
        }
        // Said "Vulkan" on a machine where Vulkan never started, two lines above
        // the same report saying it was not initialized. The settings allow the
        // terrain path and nothing has failed since — because nothing has run.
        VulkanBridge bridge = liveBridge();
        if (bridge == null || !bridge.isInitialized()) {
            return "Vulkan never started on this machine";
        }
        return null;
    }

    public static String stats() {
        String why = whyNotDrawing();
        if (why != null) {
            return "terrain: not drawn — " + why;
        }
        // Both numbers, because one of them alone has now cost two rounds of
        // asking a tester for a screenshot. The list is vanilla's: whatever it
        // hands us for the opaque layer is everything we could possibly draw.
        // A world that is missing with the two far apart is ours to fix; with
        // the two equal and both small, vanilla decided that before we saw it,
        // and the search to look at is the visibility walk.
        return "terrain: Vulkan, " + lastChunksDrawn + " of " + lastVanillaChunks
                + " chunks vanilla listed, frame " + framesDrawn;
    }

    /**
     * Whether the game's own chunk upload should be skipped right now.
     *
     * Read by the upload hook on every chunk, so it is a field rather than a
     * chain of checks: it is settled once a frame by
     * {@link #updateVanillaBufferDrop()}.
     */
    private static boolean droppingVanillaBuffers;

    public static boolean dropVanillaBuffers() {
        return droppingVanillaBuffers;
    }

    /**
     * Turns the drop on and off, and rebuilds the world whenever it changes.
     *
     * This is the whole safety of the feature. Every failure path in this mod
     * ends in falling back to vanilla rendering, which works only because the
     * vanilla buffers hold the world; with them empty it would mean an
     * invisible one. So the moment anything makes the Vulkan path unavailable —
     * a failure, the setting, the terrain switch — the buffers have to be
     * filled again, and the only way to do that is to rebuild every chunk.
     *
     * It runs before the guards in {@link #renderChunkLayer} on purpose:
     * {@code broken} makes that method return early, and this is exactly the
     * case that must not be missed.
     */
    private static void updateVanillaBufferDrop() {
        boolean want = VulkanConfig.isDropVanillaBuffers()
                && !broken
                && terrainEnabled()
                && !incompatibleRenderer;
        if (want) {
            VulkanBridge bridge = liveBridge();
            // Water and glass live in the vanilla buffers and nowhere else
            // until the translucent layer goes through Vulkan. Dropping them
            // before that leaves the layer to a renderer that declines it and
            // to buffers that are empty — so nobody draws it, and an ocean
            // turns into a hole in the world with nothing in any log to say so.
            want = bridge != null && bridge.isInitialized()
                    && VulkanConfig.isVulkanTranslucent()
                    && bridge.drawsTranslucent();
        }
        if (want == droppingVanillaBuffers) {
            return;
        }
        droppingVanillaBuffers = want;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.renderGlobal != null) {
            LOGGER.info("Vanilla chunk buffers {} — rebuilding every chunk so the world stays drawn",
                    want ? "no longer filled" : "filled again");
            mc.renderGlobal.loadRenderers();
        }
    }

    /** Returns true when the Vulkan side took the layer and GL must skip it. */
    public static boolean renderChunkLayer(BlockRenderLayer layer, List<RenderChunk> chunks) {
        if (layer == BlockRenderLayer.SOLID) {
            // Recorded before every guard below, because the case worth
            // diagnosing is the one where we hand the layer straight back and
            // draw nothing: what vanilla offered still has to be visible then.
            lastVanillaChunks = chunks == null ? 0 : chunks.size();
            updateVanillaBufferDrop();
        }
        // Leaving before packChunks matters when the layer is not taken: water
        // and glass make a long chunk list at high render distances, and every
        // frame of it was walked, packed and thrown away. It also kept the
        // drawn-chunk counter reporting chunks nothing ever drew.
        if (layer == BlockRenderLayer.TRANSLUCENT && !VulkanConfig.isVulkanTranslucent()) {
            return false;
        }
        if (!terrainEnabled() || broken) {
            return false;
        }
        if (!checkRendererCompatibility()) {
            return false;
        }
        VulkanBridge bridge = liveBridge();
        if (bridge == null || !bridge.isInitialized()) {
            return false;
        }
        try {
            if (!ensureTextures(bridge)) {
                return false;
            }
            Minecraft mc = Minecraft.getMinecraft();
            int count = packChunks(layer, chunks);
            if (layer == BlockRenderLayer.SOLID) {
                captureMatrices();
                captureFog();
                bridge.updateFogState(FOG);
                DynamicLights.gather(viewX, viewY, viewZ);
                bridge.updateDynamicLights(DynamicLights.lights(), DynamicLights.count());
                captureSun(mc);
                bridge.updateSun(SUN);
                if (lightmapColors != null) {
                    checkLightmapStillOurs();
                    bridge.updateLightmapData(lightmapColors);
                }
            }
            boolean taken = bridge.renderTerrainLayer(layer.ordinal(), chunkData, count, MVP,
                    viewX, viewY, viewZ, mc.displayWidth, mc.displayHeight);
            if (layer == BlockRenderLayer.TRANSLUCENT) {
                if (taken) {
                    translucentTaken++;
                } else {
                    translucentRefused++;
                }
            }
            if (taken && layer == BlockRenderLayer.CUTOUT) {
                framesDrawn++;
            }
            return taken;
        } catch (Throwable t) {
            broken = true;
            LOGGER.error("Vulkan terrain rendering failed — falling back to vanilla GL permanently", t);
            Diagnostics.flushNow("terrain failed permanently: " + t);
            RenderNotice.fellBackToOpenGL(t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            return false;
        }
    }

    /**
     * Launch flag, read once. System.getProperty locks the global Properties
     * table, and this sits on the per-layer path — the flag cannot change
     * while the game runs, so there is nothing to re-read.
     */
    private static final boolean TERRAIN_ALLOWED_BY_PROPERTY =
            !"false".equals(System.getProperty("vulkanmod112.terrain"));

    private static boolean terrainEnabled() {
        return TERRAIN_ALLOWED_BY_PROPERTY && VulkanConfig.isTerrainEnabled();
    }

    /**
     * Whether this frame's translucent layer is going through Vulkan.
     *
     * Sprites ride in that pass, and they are handed over a third of the way
     * through the frame — long before the layer itself is asked for. So the
     * question has to be answerable early, from state rather than from what has
     * happened this frame, and every term below is state.
     */
    static boolean vulkanOwnsTranslucent() {
        return !broken && terrainEnabled() && !incompatibleRenderer
                && VulkanConfig.isVulkanTranslucent();
    }

    private static boolean checkRendererCompatibility() {
        if (compatibilityChecked) {
            return !incompatibleRenderer;
        }
        compatibilityChecked = true;
        if (Boolean.getBoolean("vulkanmod112.allowIncompatibleRenderer")) {
            return true;
        }
        ClassLoader loader = TerrainHooks.class.getClassLoader();
        for (String className : INCOMPATIBLE_RENDERER_CLASSES) {
            try {
                Class.forName(className, false, loader);
                incompatibleRenderer = true;
                LOGGER.warn("Detected {}. Vulkan terrain is disabled to keep vanilla rendering safe; "
                        + "use -Dvulkanmod112.allowIncompatibleRenderer=true only for testing.", className);
                return false;
            } catch (ClassNotFoundException ignored) {
                // Not installed.
            } catch (LinkageError ignored) {
                // A partially loaded renderer is just as unsafe to interpose on.
                incompatibleRenderer = true;
                return false;
            }
        }
        return true;
    }

    private static int packChunks(BlockRenderLayer layer, List<RenderChunk> chunks) {
        int count = chunks.size();
        if (chunkData.length < count * 4) {
            chunkData = new int[Integer.highestOneBit(count * 4) * 2];
        }
        int i = 0;
        for (RenderChunk chunk : chunks) {
            VertexBuffer vb = chunk.getVertexBufferByLayer(layer.ordinal());
            if (vb == null) {
                continue;
            }
            BlockPos pos = chunk.getPosition();
            int slot = ((VertexBufferSlot) (Object) vb).vulkanmod112$slot();
            if (slot == ChunkSlots.UNASSIGNED) {
                // Built but never uploaded yet: nothing to draw from the
                // mirror this frame, and the chunk comes back next frame.
                continue;
            }
            chunkData[i++] = slot;
            chunkData[i++] = pos.getX();
            chunkData[i++] = pos.getY();
            chunkData[i++] = pos.getZ();
        }
        if (layer == BlockRenderLayer.SOLID) {
            lastChunksDrawn = i / 4;
        } else {
            lastChunksDrawn += i / 4;
        }
        return i / 4;
    }

    /** Direction to the sun, in world axes; see captureSun. */
    private static final float[] SUN = {0.0f, 1.0f, 0.0f};

    /**
     * Where the sun is, worked out the way the game itself places it.
     *
     * Vanilla draws the sun by turning the sky ninety degrees about Y and then
     * by the day's angle about X, and hanging the sun overhead in that frame.
     * Undoing those two rotations on "straight up" leaves this, and taking it
     * from the same number the sky is drawn from is what keeps a shadow
     * pointing where the light in the picture comes from — a renderer with its
     * own clock would drift from the sky above it within a day.
     *
     * Below the horizon there is no sun to cast anything, and the caller reads
     * the y component for exactly that.
     */
    private static void captureSun(Minecraft mc) {
        if (mc.world == null) {
            SUN[0] = 0.0f;
            SUN[1] = -1.0f;
            SUN[2] = 0.0f;
            return;
        }
        float angle = mc.world.getCelestialAngleRadians(mc.getRenderPartialTicks());
        SUN[0] = -(float) Math.sin(angle);
        SUN[1] = (float) Math.cos(angle);
        SUN[2] = 0.0f;
    }

    /**
     * Copies the fixed-function fog the game has already configured for this
     * frame, so the Vulkan terrain fades exactly like everything OpenGL still
     * draws. Underwater this is the difference between entities turning the
     * colour of the water and the blocks behind them staying perfectly clear.
     *
     * Read from GL rather than recomputed, because the game changes fog for
     * water, lava, blindness, the void and render distance, and mods add more.
     */
    private static void captureFog() {
        if (!VulkanConfig.isFogEnabled() || !GL11.glIsEnabled(GL11.GL_FOG)) {
            FOG[3] = 0.0f; // mode 0: the shader skips the blend
            return;
        }
        FOG_COLOR.clear();
        GL11.glGetFloat(GL11.GL_FOG_COLOR, FOG_COLOR);
        FOG[0] = FOG_COLOR.get(0);
        FOG[1] = FOG_COLOR.get(1);
        FOG[2] = FOG_COLOR.get(2);
        int mode = GL11.glGetInteger(GL11.GL_FOG_MODE);
        if (mode == GL11.GL_LINEAR) {
            FOG[3] = 1.0f;
        } else if (mode == GL11.GL_EXP) {
            FOG[3] = 2.0f;
        } else if (mode == GL11.GL_EXP2) {
            FOG[3] = 3.0f;
        } else {
            FOG[3] = 0.0f;
            return;
        }
        FOG[4] = GL11.glGetFloat(GL11.GL_FOG_START);
        FOG[5] = GL11.glGetFloat(GL11.GL_FOG_END);
        FOG[6] = GL11.glGetFloat(GL11.GL_FOG_DENSITY);
    }

    /** MVP = depth-range fix (GL [-1,1] → VK [0,1]) * projection * modelview. */
    private static void captureMatrices() {
        MODELVIEW.clear();
        PROJECTION.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        MODELVIEW.get(MV).clear();
        PROJECTION.get(PROJ).clear();
        Matrices.multiply(PROJ, MV, MVP);
        Matrices.toVulkanDepth(MVP);
    }

    /**
     * Hands this tick's animation frames to the Vulkan copy of the atlas.
     *
     * Called when the game has finished stepping its own animations. With no
     * renderer to send them to they are dropped rather than kept: a session
     * that never brings Vulkan up would otherwise grow this buffer forever, and
     * frames that arrive late are of no use to anyone.
     */
    /**
     * Adds the terrain's glow once the game has drawn the rest of the world.
     *
     * Late on purpose: at this point the frame holds entities, particles,
     * weather and water as well as terrain, so a mob in front of a lava lake
     * is inside the glow rather than pasted over it, and a torch throws light
     * onto the sky, which is drawn long after this mod's own frame is finished.
     */
    public static void applySceneBloom() {
        VulkanBridge bridge = liveBridge();
        if (bridge == null || !VulkanConfig.isTerrainEnabled()) {
            return;
        }
        net.minecraft.client.shader.Framebuffer frame = Minecraft.getMinecraft().getFramebuffer();
        if (frame == null || frame.framebufferTexture == 0) {
            return;
        }
        bridge.applySceneBloom(frame.framebufferTexture);
        // After the glow and not before it: the tone is of the finished
        // picture, and by this point the glow is part of the picture.
        bridge.applySceneTone(frame.framebufferTexture);
    }

    /**
     * Closes the Vulkan side while the window it shares memory with still
     * exists.
     *
     * Everything here is guarded, and that is the whole design of it. This runs
     * on the way out, where there is nothing left to save and nothing left to
     * fix: an exception thrown from here would replace a clean exit with a
     * crash report about a renderer that had already finished its work, and a
     * wait that never returns would leave the game on screen forever. So a
     * failure is written down and the game goes on closing.
     */
    public static void shutdown() {
        // The last chance the settings file has to receive anything still
        // waiting to be written.
        VulkanConfig.flush();
        VulkanBridge bridge = liveBridge();
        if (bridge == null || !bridge.isInitialized()) {
            return;
        }
        LOGGER.info("Closing the Vulkan side before the window goes");
        try {
            bridge.destroy();
        } catch (Throwable t) {
            LOGGER.error("Vulkan did not close cleanly; the game is exiting anyway", t);
        }
    }

    /**
     * Off with -Dvulkanmod112.noAtlasAnimations=true.
     *
     * A way to take the atlas upload out of the frame without taking the
     * renderer out with it: animations freeze, everything else carries on. It
     * exists because this path was rewritten to live across a frame instead of
     * finishing inside a wait, and a driver fault is a poor place to start
     * guessing which change caused it.
     */
    private static final boolean ATLAS_ANIMATIONS_OFF =
            "true".equals(System.getProperty("vulkanmod112.noAtlasAnimations"));

    public static void flushAtlasAnimations() {
        VulkanBridge bridge = liveBridge();
        if (bridge == null || ATLAS_ANIMATIONS_OFF || !VulkanConfig.isTerrainEnabled()) {
            AtlasAnimations.discard();
            return;
        }
        AtlasAnimations.flush(bridge);
    }

    /**
     * Whether the light map this renderer copies is still the one the game uses.
     *
     * <h2>The thing being watched for</h2>
     *
     * Terrain lighting here is the game's own 16x16 light map, read once as a
     * live array and copied to the GPU whenever it changes. That works with a
     * lighting mod for the same reason it works with the seasons: whatever
     * writes into that array, this renderer follows.
     *
     * What it does not follow is a mod that replaces the texture rather than
     * the array — uploading its own colours straight to GL, or swapping in a
     * different {@code DynamicTexture} altogether. Then vanilla's array stays
     * as it was, the terrain is lit by it, and everything the game draws is lit
     * by the mod. The world would be lit two different ways in one frame, with
     * nothing in any log to say so.
     *
     * <h2>Why a check and not a fix</h2>
     *
     * There is no fix from here: if the colours never pass through an array we
     * can see, the only way to follow them is to read the texture back off the
     * GPU every time it changes, which is a stall per tick for a case that may
     * not exist. What can be done is to notice, name the number, and say it
     * once — so a report of "the ground is lit wrong with mod X" is one line in
     * a log rather than a week.
     *
     * <p>Cheap: two identity comparisons on the frame that draws SOLID.
     */
    private static void checkLightmapStillOurs() {
        if (lightmapWarned) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        try {
            DynamicTexture live = ReflectionHelper.getPrivateValue(
                    net.minecraft.client.renderer.EntityRenderer.class, mc.entityRenderer,
                    "lightmapTexture", "field_78513_d");
            if (live == null) {
                return;
            }
            // Identity, not contents: the array is meant to change every tick,
            // and what matters is whether it is still the same array.
            if (live.getTextureData() == lightmapColors) {
                return;
            }
            lightmapWarned = true;
            LOGGER.warn("The light map was replaced by something else — terrain lighting is "
                    + "copied from the array this mod took at startup, and that is no longer "
                    + "the one the game is drawing from. Blocks and creatures may be lit "
                    + "differently. Naming the other lighting mod in a report is enough to "
                    + "act on this.");
        } catch (Throwable t) {
            // A loader where the field is not where it was. Not worth a second
            // failure on top of whatever is already wrong.
            lightmapWarned = true;
        }
    }

    /** Said once; a lighting mod does not become less installed over time. */
    private static boolean lightmapWarned;

    private static boolean ensureTextures(VulkanBridge bridge) {
        if (atlasUploaded) {
            return true;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int atlasId = mc.getTextureMapBlocks().getGlTextureId();
        DynamicTexture lightmap = ReflectionHelper.getPrivateValue(
                net.minecraft.client.renderer.EntityRenderer.class, mc.entityRenderer,
                "lightmapTexture", "field_78513_d");
        bridge.updateAtlas(atlasId);
        MaterialSprites.handOver(bridge, mc.getTextureMapBlocks());
        // From here on the copy is worth keeping up to date.
        AtlasAnimations.arm();
        bridge.setLightmap(lightmap.getGlTextureId());
        lightmapColors = lightmap.getTextureData(); // backing array of the 16x16 lightmap
        // Same moment, same reason: the sheets particles and weather are drawn
        // from are the game's textures, and this is the one point in the frame
        // where copying one costs nothing that is already in flight.
        SpriteHooks.sendSheets(bridge);
        atlasUploaded = true;
        LOGGER.info("Block atlas (GL {}) and lightmap (GL {}) handed to Vulkan", atlasId, lightmap.getGlTextureId());
        return true;
    }

}
