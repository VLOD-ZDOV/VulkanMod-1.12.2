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
import net.vulkanmod112.mixin.VertexBufferAccessor;
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

    /** Packed per chunk: glBufferId, blockX, blockY, blockZ. */
    private static int[] chunkData = new int[1024];

    private static long framesDrawn;
    private static int lastChunksDrawn;

    private TerrainHooks() {
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

    /** Returns true when the Vulkan side took the layer and GL must skip it. */
    public static boolean renderChunkLayer(BlockRenderLayer layer, List<RenderChunk> chunks) {
        // TRANSLUCENT stays on the vanilla path, and the Vulkan side rejects it
        // outright. Leaving before packChunks matters: water and glass make a
        // long chunk list at high render distances, and every frame it was
        // walked, packed and thrown away. It also kept the drawn-chunk counter
        // reporting chunks nothing ever drew.
        if (layer == BlockRenderLayer.TRANSLUCENT) {
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
                if (lightmapColors != null) {
                    bridge.updateLightmapData(lightmapColors);
                }
            }
            boolean taken = bridge.renderTerrainLayer(layer.ordinal(), chunkData, count, MVP,
                    viewX, viewY, viewZ, mc.displayWidth, mc.displayHeight);
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
            chunkData[i++] = ((VertexBufferAccessor) (Object) vb).vulkanmod112$getGlBufferId();
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
