package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.inventory.container.PlayerContainer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import net.vulkanmodnext.mixin.LightTextureAccess;
import net.vulkanmodnext.vkimpl.VkContext;
import net.vulkanmodnext.VulkanModNext;
import net.vulkanmodnext.mixin.ChunkRenderAccess;
import net.vulkanmodnext.mixin.WorldRendererAccess;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.util.math.vector.Matrix4f;

import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Gathers everything the Vulkan terrain renderer needs to draw one layer.
 *
 * <h2>What it does not do yet</h2>
 *
 * It does not draw. Vanilla still renders the frame, and this only collects and
 * checks the inputs. That order is deliberate: handing the renderer a wrong
 * chunk list or a transposed matrix produces a black screen, and a black screen
 * says nothing about which of the two was wrong. Everything here is checkable
 * while the game still looks right.
 *
 * <h2>The five things a layer needs</h2>
 *
 * <ol>
 * <li><b>The visible chunks</b>, as mirror slots, in the game's own order. Taken
 *     from the game rather than worked out: the first thing to prove is that we
 *     can draw what vanilla draws.</li>
 * <li><b>The camera</b>, which the game hands to this method.</li>
 * <li><b>The matrix</b>. On this version the game still drives the fixed
 *     function matrix stack, so the product is read back from OpenGL — the same
 *     route the 1.12.2 mod uses, and it has the advantage of being whatever the
 *     game actually set rather than whatever we think it set.</li>
 * <li><b>The block atlas</b>, by its OpenGL name.</li>
 * <li><b>The framebuffer size.</b></li>
 * </ol>
 */
public final class TerrainFrame {

    private static final int MAX_CHUNKS = 1 << 16;

    /** Four ints a chunk: slot, then the world position of its corner. */
    private static int[] slots = new int[4096 * 4];
    private static final float[] mvp = new float[16];
    private static Matrix4f frameProjection;
    private static final float[] sun = new float[3];
    private static final float[] fog = new float[7];
    private static final float[] cameraOffset = new float[3];
    private static final int[] taken = new int[4];
    private static final int[] refused = new int[4];
    private static int sentAtlas = -1;
    private static int sentLightmap = -1;
    private static int frames;
    /** Late enough that startup is over: a first frame is not a frame. */
    private static final int ANNOUNCE_ON_FRAME = 60;
    private static FloatBuffer scratchBuffer;
    private static boolean announced;

    private TerrainFrame() {
    }

    /**
     * @return true when Vulkan drew this layer and the game should not
     */
    public static boolean layer(WorldRenderer renderer, RenderType layer, MatrixStack matrices,
                                double viewX, double viewY, double viewZ) {
        VkContext context = VulkanStartup.context();
        if (context == null || frameProjection == null) {
            return false;
        }
        try {
            return gather(context, renderer, layer, matrices, viewX, viewY, viewZ);
        } catch (Throwable failed) {
            if (!announced) {
                announced = true;
                VulkanModNext.LOGGER.warn("Could not draw a terrain layer through Vulkan; the "
                        + "game keeps drawing it", failed);
            }
            // Never the mod's fault that a frame is missing: whatever went
            // wrong here, vanilla still draws the world.
            return false;
        }
    }

    private static boolean gather(VkContext context, WorldRenderer renderer, RenderType layer,
                                  MatrixStack matrices,
                                  double viewX, double viewY, double viewZ) {
        java.util.List<?> visible = ((WorldRendererAccess) renderer).vulkanmodnext$visibleChunks();
        int count = 0;
        int at = 0;
        int empty = 0;
        int unmirrored = 0;
        for (int i = 0; i < visible.size() && count < MAX_CHUNKS; i++) {
            Object entry = visible.get(i);
            net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.ChunkRender chunk =
                    ((ChunkRenderAccess) entry).vulkanmodnext$chunk();
            if (chunk.getCompiledChunk().isEmpty(layer)) {
                empty++;
                continue;
            }
            VertexBuffer buffer = chunk.getBuffer(layer);
            if (buffer == null) {
                continue;
            }
            int slot = ((VertexBufferSlot) (Object) buffer).vulkanmodnext$slot();
            if (slot == ChunkSlots.UNASSIGNED) {
                // Built before we were watching, or never built at all. Counted
                // rather than skipped quietly: a list that is quietly short is
                // a world with holes in it, and this is the number that says so.
                unmirrored++;
                continue;
            }
            if (at + 4 > slots.length) {
                slots = java.util.Arrays.copyOf(slots, slots.length * 2);
            }
            // Four numbers a chunk, not one: the slot says where its geometry
            // is, and the three after it say where in the world to put it. A
            // chunk's vertices are stored relative to its own corner, so
            // without these every one of them is drawn at the origin — which
            // is exactly what happened, and the renderer reported eight hundred
            // chunks and four hundred thousand vertices while doing it.
            net.minecraft.util.math.BlockPos origin = chunk.getOrigin();
            slots[at++] = slot;
            slots[at++] = origin.getX();
            slots[at++] = origin.getY();
            slots[at++] = origin.getZ();
            count++;
        }

        modelViewProjection(matrices);
        announceOnce(layer, count, empty, unmirrored, viewX, viewY, viewZ);

        int ordinal = ordinalOf(layer);
        if (!VulkanConfig.isTerrainEnabled() || count == 0 || ordinal < 0) {
            return false;
        }
        if (!handOverTextures(context)) {
            return false;
        }

        handOverFrameState(context, matrices);
        boolean drawn = context.renderTerrainLayer(ordinal, slots, count, mvp,
                viewX, viewY, viewZ,
                Minecraft.getInstance().getWindow().getWidth(),
                Minecraft.getInstance().getWindow().getHeight());
        taken[ordinal] += drawn ? 1 : 0;
        refused[ordinal] += drawn ? 0 : 1;
        return drawn;
    }

    /**
     * The per-frame numbers the terrain shader needs beyond the geometry.
     *
     * <h2>None of these were being sent, and it showed</h2>
     *
     * With the geometry right and every chunk drawn, the picture still had a
     * fifth of vanilla's colours. The shader was being handed a world with no
     * sun in it and no fog: terms that default to zero, and a frame that goes
     * flat rather than wrong.
     *
     * <p>The sun is taken from the same angle the game draws its own sky from.
     * A renderer with a clock of its own drifts from the sky above it within a
     * day, and then a shadow points somewhere the light in the picture does
     * not come from.
     *
     * <p>The fog is read from OpenGL, and here that is right where it was wrong
     * for the matrices: this version still drives the fixed-function fog state,
     * and by the time a layer draws, the game has set it. Reading it is also
     * what keeps our fog and the game's from disagreeing at the seam where our
     * terrain meets vanilla's entities.
     */
    private static void handOverFrameState(VkContext context, MatrixStack matrices) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            float angle = mc.level.getTimeOfDay(mc.getFrameTime()) * ((float) Math.PI * 2.0f);
            sun[0] = -(float) Math.sin(angle);
            sun[1] = (float) Math.cos(angle);
            sun[2] = 0.0f;
            context.setSunDirection(sun);
            context.setRainStrength(mc.level.getRainLevel(mc.getFrameTime()));
        }

        FloatBuffer colour = scratch();
        GL11C.glGetFloatv(GL11.GL_FOG_COLOR, colour);
        fog[0] = colour.get(0);
        fog[1] = colour.get(1);
        fog[2] = colour.get(2);
        int mode = GL11C.glGetInteger(GL11.GL_FOG_MODE);
        fog[3] = mode == GL11.GL_LINEAR ? 1.0f : 0.0f;
        fog[4] = GL11C.glGetFloat(GL11.GL_FOG_START);
        fog[5] = GL11C.glGetFloat(GL11.GL_FOG_END);
        fog[6] = GL11C.glGetFloat(GL11.GL_FOG_DENSITY);
        context.setFogState(fog);

        // Where the camera is, in the space the matrix maps to the origin. On
        // this version the model-view carries the rotation only — the camera's
        // position is applied per chunk — so this is the rotation undone on the
        // translation, which for a rotation-only matrix is zero and is still
        // worth sending rather than assumed.
        Matrix4f pose = matrices.last().pose();
        FloatBuffer into = scratch();
        pose.store(into);
        cameraOffset[0] = -(into.get(0) * into.get(12) + into.get(1) * into.get(13)
                + into.get(2) * into.get(14));
        cameraOffset[1] = -(into.get(4) * into.get(12) + into.get(5) * into.get(13)
                + into.get(6) * into.get(14));
        cameraOffset[2] = -(into.get(8) * into.get(12) + into.get(9) * into.get(13)
                + into.get(10) * into.get(14));
        context.updateCameraOffset(cameraOffset);
    }

    /**
     * Which layers Vulkan is actually taking.
     *
     * Four numbers rather than one, because "the terrain is drawn" is four
     * separate claims and they fail separately: a frame with the solid layer
     * taken and the cutout refused is a world with no grass, leaves or glass in
     * it, and that is exactly the shape of a picture with too few colours in
     * it.
     */
    public static String layerReport() {
        StringBuilder sb = new StringBuilder("layers taken by Vulkan:");
        String[] names = {"solid", "cutout mipped", "cutout", "translucent"};
        for (int i = 0; i < 4; i++) {
            sb.append(' ').append(names[i]).append(' ').append(taken[i])
                    .append('/').append(taken[i] + refused[i]);
        }
        return sb.toString();
    }

    /**
     * Gives the renderer the atlas and the lightmap, once each.
     *
     * <h2>Once, and this is not an optimisation</h2>
     *
     * {@code updateAtlas} does not compare and skip: it destroys the copy it
     * has, forgets every sprite sheet built from it and uploads the whole chain
     * again — five mip levels of it. Calling that per layer is not four times
     * the work, it is a stall: the upload reuses the renderer's one-shot fence,
     * and the second call in a frame waits on a fence the frame it is inside
     * has already taken. The first attempt at this port did exactly that and
     * the report was {@code vkWaitForFences(atlas) failed with VK_TIMEOUT} —
     * which reads like a driver problem and is not one.
     *
     * <p>So it is sent on change only. The names do change: a resource reload
     * builds a new atlas and the game hands out fresh OpenGL names, and copies
     * made from the old ones would draw the previous pack's textures.
     *
     * @return false when the lightmap does not exist yet, which happens for a
     *         few frames after a world opens
     */
    private static boolean handOverTextures(VkContext context) {
        int atlas = atlasTexture();
        int lightmap = lightmapTexture();
        if (lightmap <= 0 || atlas <= 0) {
            return false;
        }
        if (atlas != sentAtlas) {
            context.updateAtlas(atlas);
            sentAtlas = atlas;
        }
        if (lightmap != sentLightmap) {
            context.setLightmap(lightmap);
            sentLightmap = lightmap;
        }
        return true;
    }

    /**
     * Which layer this is, in the order the renderer was written against, or
     * -1 for one it must not be given.
     *
     * <h2>The fifth layer</h2>
     *
     * On 1.12.2 there are four layers, they are an enum, and the ordinal is the
     * answer. Here there are five: cutout is split by mipmapping, and tripwire
     * is its own — drawn <em>after</em> translucent, which is the whole
     * problem. The renderer opens its frame on the first layer and closes it on
     * the last, so handing tripwire over as "translucent again" opens a second
     * frame on top of one already finished.
     *
     * <p>It stays with vanilla. Tripwire is a few strings and hooks; the cost
     * of letting the game draw them is nothing, and the cost of getting this
     * wrong is a frame the renderer cannot close.
     */
    private static int ordinalOf(RenderType layer) {
        if (layer == RenderType.solid()) {
            return 0;
        }
        if (layer == RenderType.cutoutMipped()) {
            return 1;
        }
        if (layer == RenderType.cutout()) {
            return 2;
        }
        if (layer == RenderType.translucent()) {
            return 3;
        }
        return -1;
    }

    /** The lightmap, by its OpenGL name, or 0 before the game has built one. */
    private static int lightmapTexture() {
        try {
            LightTextureAccess lightmap = (LightTextureAccess) Minecraft.getInstance()
                    .gameRenderer.lightTexture();
            return lightmap.vulkanmodnext$texture().getId();
        } catch (Throwable notYet) {
            return 0;
        }
    }

    /** Kept from the top of the frame; every layer of it uses the same one. */
    public static void beginFrame(Matrix4f projection) {
        frameProjection = projection;
        frames++;
    }

    /**
     * Projection times model-view, both taken from the game rather than from
     * OpenGL.
     *
     * The first attempt read them back with {@code glGetFloatv}, which is what
     * the 1.12.2 mod does and is wrong on this version: the projection came
     * back NaN and the model-view came back as the identity, with no OpenGL
     * error. Neither reading was a bug in the reading — on 1.16.5 the camera
     * lives in the game's own matrix stack and only reaches OpenGL when a
     * buffer is actually drawn, so at the top of a layer there is nothing there
     * yet.
     *
     * <p>Both are parameters of the methods this mod is already inside. Asking
     * the game is shorter, exact, and does not depend on legacy state that a
     * later version will not have.
     */
    private static void modelViewProjection(MatrixStack matrices) {
        Matrix4f product = frameProjection.copy();
        product.multiply(matrices.last().pose());
        FloatBuffer into = scratch();
        product.store(into);
        into.get(mvp);
        into.clear();
        // OpenGL's clip space runs from -1 to 1 in depth and Vulkan's from 0 to
        // 1. Without this correction every vertex is placed correctly in x and
        // y and lands outside the depth range, so the world is drawn and none
        // of it survives — which is exactly what it looked like: eight hundred
        // chunks, six hundred thousand vertices, and a screen full of sky.
        Matrices.toVulkanDepth(mvp);
    }

    private static FloatBuffer scratch() {
        if (scratchBuffer == null) {
            scratchBuffer = ByteBuffer.allocateDirect(16 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        scratchBuffer.clear();
        return scratchBuffer;
    }

    /**
     * One line, once, saying whether the inputs make sense.
     *
     * The numbers to look at are the chunk count against what the game drew,
     * and the last row of the matrix: a perspective projection puts −1 in the
     * third column of it, and a matrix that arrives transposed or empty says so
     * there before it says it as a black screen.
     */
    private static void announceOnce(RenderType layer, int count, int empty, int unmirrored,
                                     double viewX, double viewY, double viewZ) {
        if (announced || count == 0 || frames < ANNOUNCE_ON_FRAME) {
            return;
        }
        announced = true;
        VulkanModNext.LOGGER.info(
                "Terrain layer {}: {} chunks to draw, {} empty, {} not mirrored yet",
                layer, count, empty, unmirrored);
        VulkanModNext.LOGGER.info("  camera ({}, {}, {}), atlas texture {}, framebuffer {}x{}",
                String.format("%.1f", viewX), String.format("%.1f", viewY),
                String.format("%.1f", viewZ), atlasTexture(),
                Minecraft.getInstance().getWindow().getWidth(),
                Minecraft.getInstance().getWindow().getHeight());
        VulkanModNext.LOGGER.info("  the projection the game handed us: {}", frameProjection);
        VulkanModNext.LOGGER.info("  matrix last row {} {} {} {} (a perspective one has -1 third)",
                String.format("%.3f", mvp[3]), String.format("%.3f", mvp[7]),
                String.format("%.3f", mvp[11]), String.format("%.3f", mvp[15]));

    }

    /** The block atlas, by its OpenGL name. */
    public static int atlasTexture() {
        return Minecraft.getInstance().getTextureManager()
                .getTexture(PlayerContainer.BLOCK_ATLAS).getId();
    }
}
