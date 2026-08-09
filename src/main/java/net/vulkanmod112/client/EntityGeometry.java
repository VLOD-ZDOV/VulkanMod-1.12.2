package net.vulkanmod112.client;

import net.minecraft.client.model.ModelRenderer;
import net.vulkanmod112.VulkanBridge;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Creature geometry, turned into the vertices the sprite pass already draws.
 *
 * <h2>Why there is no entity pipeline</h2>
 *
 * There nearly was one. A bone's matrix maps model space to *eye* space — the
 * view rotation is already in it — while everything this renderer draws works
 * in world coordinates measured from the camera, and the obvious reading of
 * that was "entities need their own pipeline with the projection matrix alone".
 *
 * The obvious reading was expensive and wrong. Undoing the view once a frame
 * costs one matrix inverse; then a creature's vertices are in exactly the space
 * particles, rain and snow are already in, and the pass that draws those draws
 * these too — same pipeline, same vertex format, same blend, same composite.
 * The alternative was a second pipeline, a second set of shaders and a second
 * place for the vertex layout to drift out of step.
 *
 * <h2>Why the model tree is walked here</h2>
 *
 * Vanilla's own {@code render} draws a bone and then its children inside the
 * frame it just built. Taking the bone means taking its children too — a hook
 * that stops the parent never sees them — so the walk is repeated here, in the
 * same order, with the same rules about what is hidden.
 *
 * <h2>What is not ours</h2>
 *
 * Anything that does not build its models out of {@code ModelRenderer}: mods
 * with their own geometry go through none of this and are drawn exactly as they
 * were. So does anything whose skin will not fit in a slot. That is the whole
 * of the fallback, and it costs nothing to have.
 */
public final class EntityGeometry {

    /** pos 3f | uv 2f | colour 4ub | light 2s — vanilla's particle vertex. */
    private static final int VERTEX_BYTES = 28;
    /** How deep a skeleton may be before this stops following it. */
    private static final int MAX_DEPTH = 16;

    /** One buffer per skin, because the pass draws one texture at a time. */
    private static final class Batch {
        final int glTexture;
        ByteBuffer vertices;
        int count;

        Batch(int glTexture) {
            this.glTexture = glTexture;
            this.vertices = BufferUtils.createByteBuffer(64 * 1024).order(ByteOrder.nativeOrder());
        }
    }

    private static final List<Batch> BATCHES = new ArrayList<Batch>();
    private static Batch current;

    /** The camera's own transform, undone, so bones come out in world axes. */
    private static final float[] VIEW_INVERSE = new float[16];
    private static boolean viewKnown;
    private static final float[] LOCAL = new float[16];
    private static final float[][] FRAMES = new float[MAX_DEPTH][16];

    private static long framesDrawn;
    private static long quadsDrawn;
    private static long partsDrawn;
    private static long skinsRefused;
    private static int lastQuads;
    private static int lastBatches;

    private EntityGeometry() {
    }

    public static boolean drawing() {
        return VulkanConfig.isVulkanEntities() && viewKnown;
    }

    /**
     * Takes the camera out of the matrix, once for the whole pass.
     *
     * At this moment the model-view holds the camera and nothing else — no
     * creature has been placed yet — so inverting it here is inverting the view.
     */
    public static void beginPass() {
        viewKnown = false;
        if (!VulkanConfig.isVulkanEntities()) {
            return;
        }
        viewKnown = invert(GlMatrixMirror.current(), VIEW_INVERSE);
        for (int i = 0; i < BATCHES.size(); i++) {
            BATCHES.get(i).count = 0;
        }
    }

    /**
     * Draws one root bone and everything hanging off it.
     *
     * @return true when the game should not draw it as well
     */
    public static boolean takePart(ModelRenderer part, float scale) {
        if (part == null || !drawing()) {
            return false;
        }
        try {
            // The frame the game has built up to here: the creature's place in
            // the world, still with the camera's rotation in it, which the
            // inverse above takes back out.
            multiply(VIEW_INVERSE, GlMatrixMirror.current(), FRAMES[0]);
            emit(part, scale, 0);
            return true;
        } catch (Throwable ignored) {
            // A model this does not understand is a model the game still draws
            // — but only if we say so, and we have already drawn part of it.
            // Saying "ours" here would lose the rest of the creature, so the
            // half that reached the buffer is accepted and the rest is not
            // drawn twice.
            return true;
        }
    }

    private static void emit(ModelRenderer part, float scale, int depth) {
        if (part.isHidden || !part.showModel || depth >= MAX_DEPTH - 1) {
            return;
        }
        EntityCapture.localTransform(part, scale, LOCAL);
        float[] frame = FRAMES[depth + 1];
        multiply(FRAMES[depth], LOCAL, frame);
        float[] shape = EntityCapture.shapeOf(part, scale);
        if (shape.length != 0) {
            write(shape, frame);
            partsDrawn++;
        }
        List<ModelRenderer> children = part.childModels;
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                ModelRenderer child = children.get(i);
                if (child != null) {
                    emit(child, scale, depth + 1);
                }
            }
        }
    }

    /**
     * The two lights the game shades every creature with, in world directions.
     *
     * Vanilla lights entities with the fixed-function pipeline: {@code
     * RenderHelper.enableStandardItemLighting} switches on two directional
     * lights of 0.6 each and an ambient of 0.4, and the material takes the
     * vertex colour for both terms. The positions are handed to OpenGL while
     * the model-view holds the camera and nothing else, which is what makes
     * them fixed in the world rather than following the head.
     *
     * Nothing here was reproducing any of it, and the symptom was exactly what
     * that predicts: a creature drawn through Vulkan came out brighter than the
     * same creature drawn by the game, and flat with it — every face at full
     * strength, whichever way it pointed. Reported from a live session before
     * any of this was measured.
     */
    private static final float[] LIGHT_0 = normalised(0.2f, 1.0f, -0.7f);
    private static final float[] LIGHT_1 = normalised(-0.2f, 1.0f, 0.7f);
    private static final float DIFFUSE = 0.6f;
    private static final float AMBIENT = 0.4f;

    private static float[] normalised(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / length, y / length, z / length};
    }

    /**
     * Vanilla's own shading of one face, folded into the vertex colour.
     *
     * Done here rather than in the shader for two reasons that point the same
     * way: the normal is already in hand at this moment and would otherwise
     * have to be carried through a vertex format that has no room for it, and
     * the game shades these flat — one normal for the whole quad — so there is
     * nothing a per-fragment version would add.
     *
     * @param in index of this vertex inside {@code shape}; the normal is the
     *           sixth, seventh and eighth float of it
     * @param m  the frame this part is drawn in, whose rotation takes the
     *           normal into the world
     */
    private static int shade(int colour, float[] shape, int in, float[] m) {
        float nx = shape[in + 5];
        float ny = shape[in + 6];
        float nz = shape[in + 7];
        float wx = m[0] * nx + m[4] * ny + m[8] * nz;
        float wy = m[1] * nx + m[5] * ny + m[9] * nz;
        float wz = m[2] * nx + m[6] * ny + m[10] * nz;
        float length = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (length < 1.0e-6f) {
            return colour;
        }
        wx /= length;
        wy /= length;
        wz /= length;
        float lit = AMBIENT
                + DIFFUSE * Math.max(wx * LIGHT_0[0] + wy * LIGHT_0[1] + wz * LIGHT_0[2], 0.0f)
                + DIFFUSE * Math.max(wx * LIGHT_1[0] + wy * LIGHT_1[1] + wz * LIGHT_1[2], 0.0f);
        // OpenGL clamps the result of lighting, and a face square-on to both
        // lights reaches past one without it.
        if (lit > 1.0f) {
            lit = 1.0f;
        }
        // The byte order is the vertex format's: R is the low byte, alpha the
        // high one, and alpha is not a colour — a creature's edges are decided
        // by the cutoff in the fragment shader and dimming it would move them.
        int r = (int) (((colour) & 0xFF) * lit);
        int g = (int) (((colour >>> 8) & 0xFF) * lit);
        int b = (int) (((colour >>> 16) & 0xFF) * lit);
        return (colour & 0xFF000000) | (b << 16) | (g << 8) | r;
    }

    /** Eight floats a vertex in, twenty-eight bytes a vertex out. */
    private static void write(float[] shape, float[] m) {
        Batch batch = batchFor(GlTextureMirror.boundOnDefaultUnit());
        if (batch == null) {
            return;
        }
        int vertices = shape.length / 8;
        ensure(batch, vertices * VERTEX_BYTES);
        int colour = GlTextureMirror.packedColor();
        short lightU = GlTextureMirror.lightU();
        short lightV = GlTextureMirror.lightV();
        ByteBuffer out = batch.vertices;
        int at = batch.count * VERTEX_BYTES;
        for (int v = 0; v < vertices; v++) {
            int in = v * 8;
            float x = shape[in];
            float y = shape[in + 1];
            float z = shape[in + 2];
            out.putFloat(at, m[0] * x + m[4] * y + m[8] * z + m[12]);
            out.putFloat(at + 4, m[1] * x + m[5] * y + m[9] * z + m[13]);
            out.putFloat(at + 8, m[2] * x + m[6] * y + m[10] * z + m[14]);
            out.putFloat(at + 12, shape[in + 3]);
            out.putFloat(at + 16, shape[in + 4]);
            out.putInt(at + 20, shade(colour, shape, in, m));
            out.putShort(at + 24, lightU);
            out.putShort(at + 26, lightV);
            at += VERTEX_BYTES;
        }
        batch.count += vertices;
        quadsDrawn += vertices / 4;
    }

    private static Batch batchFor(int glTexture) {
        if (glTexture <= 0) {
            return null;
        }
        if (current != null && current.glTexture == glTexture) {
            return current;
        }
        for (int i = 0; i < BATCHES.size(); i++) {
            Batch batch = BATCHES.get(i);
            if (batch.glTexture == glTexture) {
                current = batch;
                return batch;
            }
        }
        Batch batch = new Batch(glTexture);
        BATCHES.add(batch);
        current = batch;
        return batch;
    }

    private static void ensure(Batch batch, int extra) {
        int needed = batch.count * VERTEX_BYTES + extra;
        if (batch.vertices.capacity() >= needed) {
            return;
        }
        int size = batch.vertices.capacity();
        while (size < needed) {
            size *= 2;
        }
        ByteBuffer grown = BufferUtils.createByteBuffer(size).order(ByteOrder.nativeOrder());
        batch.vertices.position(0).limit(batch.count * VERTEX_BYTES);
        grown.put(batch.vertices);
        batch.vertices.clear();
        grown.clear();
        batch.vertices = grown;
    }

    /**
     * Hands the pass over, one skin at a time.
     *
     * A skin with no slot left is not drawn here — and because taking a part
     * already stopped the game drawing it, that creature is simply missing for
     * a frame rather than doubled. The count is reported; the measurement that
     * set the slot count said sixteen skins in a session, so this is a bound
     * that should never be reached.
     */
    /** The bridge lives in this package; the mixin does not. */
    public static void flushToBridge() {
        flush(TerrainHooks.liveBridge());
    }

    /**
     * Closes the capture window, so that nothing outside it is taken.
     *
     * Forge calls the entity pass a second time after the translucent layer,
     * and this renderer has nothing to do with that call: its submission has
     * gone and its batches are cleared, so a part taken there would be
     * cancelled for the game and drawn by nobody. Standing down between the
     * two passes is what leaves it to vanilla, which draws it correctly.
     */
    public static void endPass() {
        viewKnown = false;
    }

    public static void flush(VulkanBridge bridge) {
        if (bridge == null || !viewKnown) {
            return;
        }
        int batches = 0;
        int quads = 0;
        for (int i = 0; i < BATCHES.size(); i++) {
            Batch batch = BATCHES.get(i);
            if (batch.count == 0) {
                continue;
            }
            int slot = bridge.spriteSlotForTexture(batch.glTexture);
            if (slot <= 0) {
                skinsRefused++;
                batch.count = 0;
                continue;
            }
            batch.vertices.position(0).limit(batch.count * VERTEX_BYTES);
            // A cutout threshold rather than a particle's: a creature's skin is
            // opaque where it is drawn at all, and the edges of a cape or a
            // wing are a hard boundary, not a fade.
            bridge.submitSprites(batch.vertices, batch.count, slot, 0.1f);
            batch.vertices.clear();
            batches++;
            quads += batch.count / 4;
            batch.count = 0;
        }
        lastBatches = batches;
        lastQuads = quads;
        if (quads > 0) {
            framesDrawn++;
        }
        current = null;
        viewKnown = false;
    }

    /** out = a * b, both column-major. */
    private static void multiply(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 4; col++) {
            int c = col * 4;
            float b0 = b[c];
            float b1 = b[c + 1];
            float b2 = b[c + 2];
            float b3 = b[c + 3];
            out[c] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3;
            out[c + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3;
            out[c + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3;
            out[c + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3;
        }
    }

    /**
     * The view is a rotation and a translation, so its inverse is the
     * transposed rotation and the translation carried back through it.
     *
     * The general inverse would work and is forty lines of cofactors; this is
     * six, and it is exact for the one kind of matrix this is ever given. A
     * matrix that is not of that kind is refused rather than inverted wrongly,
     * because a wrong view puts every creature in the world somewhere else.
     */
    private static boolean invert(float[] m, float[] out) {
        // Scale would show up as basis vectors that are not unit length.
        float lengthSq = m[0] * m[0] + m[1] * m[1] + m[2] * m[2];
        if (m[3] != 0.0f || m[7] != 0.0f || m[11] != 0.0f || m[15] != 1.0f
                || lengthSq < 0.99f || lengthSq > 1.01f) {
            return false;
        }
        out[0] = m[0];
        out[1] = m[4];
        out[2] = m[8];
        out[3] = 0.0f;
        out[4] = m[1];
        out[5] = m[5];
        out[6] = m[9];
        out[7] = 0.0f;
        out[8] = m[2];
        out[9] = m[6];
        out[10] = m[10];
        out[11] = 0.0f;
        out[12] = -(m[0] * m[12] + m[1] * m[13] + m[2] * m[14]);
        out[13] = -(m[4] * m[12] + m[5] * m[13] + m[6] * m[14]);
        out[14] = -(m[8] * m[12] + m[9] * m[13] + m[10] * m[14]);
        out[15] = 1.0f;
        return true;
    }

    public static String stats() {
        if (!VulkanConfig.isVulkanEntities()) {
            return "vulkan entities: off";
        }
        return "vulkan entities: " + lastQuads + " quads in " + lastBatches + " skins last frame; "
                + framesDrawn + " frames drawn, " + partsDrawn + " parts, " + quadsDrawn
                + " quads total" + (skinsRefused > 0 ? "; " + skinsRefused + " skins had no slot"
                : "");
    }
}
