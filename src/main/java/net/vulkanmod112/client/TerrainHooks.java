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
    }

    public static String stats() {
        if (!terrainEnabled()) {
            return "terrain: off";
        }
        if (broken) {
            return "terrain: FAILED, vanilla fallback";
        }
        if (incompatibleRenderer) {
            return "terrain: disabled for incompatible renderer";
        }
        return "terrain: Vulkan, " + lastChunksDrawn + " chunks, frame " + framesDrawn;
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
            VulkanBridge bridge = VulkanLoader.bridgeIfReady();
            want = bridge != null && bridge.isInitialized();
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
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
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
                if (lightmapColors != null) {
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
        multiply(PROJ, MV, MVP);
        // z' = 0.5*z + 0.5*w, applied to the combined matrix rows (column-major)
        for (int col = 0; col < 4; col++) {
            float z = MVP[col * 4 + 2];
            float w = MVP[col * 4 + 3];
            MVP[col * 4 + 2] = 0.5f * z + 0.5f * w;
        }
    }

    /** Column-major 4x4 multiply: out = a * b. */
    private static void multiply(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                out[col * 4 + row] = sum;
            }
        }
    }

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
        bridge.setLightmap(lightmap.getGlTextureId());
        lightmapColors = lightmap.getTextureData(); // backing array of the 16x16 lightmap
        atlasUploaded = true;
        LOGGER.info("Block atlas (GL {}) and lightmap (GL {}) handed to Vulkan", atlasId, lightmap.getGlTextureId());
        return true;
    }

}
