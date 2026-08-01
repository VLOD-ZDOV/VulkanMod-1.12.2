package net.vulkanmod112.client;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.vulkanmod112.mixin.ModelBoxAccessor;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.IdentityHashMap;

/**
 * Reads the geometry and placement of every entity part the game draws, and
 * draws nothing.
 *
 * <h2>Why this exists before anything visible</h2>
 *
 * Entities are the largest remaining piece of the world this renderer does not
 * own, and they are also the part of the game other mods hook hardest — so the
 * way taking them over goes wrong is not a wrong pixel but somebody else's mod
 * no longer drawing. Nothing here is taken over. What it produces is the two
 * numbers that decide whether taking it over is possible at all.
 *
 * <h2>Where the placement comes from, and why that is the whole problem</h2>
 *
 * A part's shape is fixed and is read once. Where the part <i>is</i> lives
 * entirely in OpenGL's matrix stack, built up by the game as it walks the
 * skeleton. The obvious way to get it is to ask the driver at the moment the
 * game is about to draw — and that was measured at two to three microseconds a
 * part, for half a millisecond to a full millisecond of frame at a few hundred
 * parts. The whole Vulkan terrain pass is a third of a millisecond, so that
 * approach was over before it began.
 *
 * The transform a part applies is not a mystery, though. It is
 * {@code translate(offset) · translate(rotationPoint · scale) · Rz · Ry · Rx},
 * every term of it a field the part already holds, and children are drawn
 * inside their parent's frame. Composing that is arithmetic. The driver is then
 * needed once for the whole creature, not once for each of its bones.
 *
 * <h2>The check that makes this an experiment rather than a hope</h2>
 *
 * Composed transforms are compared against the driver's own on a sample of
 * parts. Getting the order of three rotations wrong, or a scale applied to the
 * wrong term, produces a creature that is subtly inside out — and inside out in
 * a way that is obvious in a screenshot and invisible in a number, which is the
 * wrong way round for something not being drawn yet. The disagreement count and
 * the largest error are reported beside the cost.
 */
public final class EntityCapture {

    /** Position, texture, normal — eight floats a vertex, four vertices a quad. */
    private static final int FLOATS_PER_VERTEX = 8;
    /** How deep a model's skeleton is allowed to be before this gives up on it. */
    private static final int MAX_DEPTH = 16;
    /** One part in this many is checked against the driver. */
    private static final int CHECK_EVERY = 64;
    /** Beyond this the two disagree about where the part is, in blocks. */
    private static final float AGREEMENT = 0.002f;

    private static boolean armed;
    private static final IdentityHashMap<Object, float[]> GEOMETRY =
            new IdentityHashMap<Object, float[]>();
    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    private static final float[] READ = new float[16];

    /** The frame of the creature being drawn, and one frame per skeleton level. */
    private static final float[][] STACK = new float[MAX_DEPTH][16];
    private static int depth;
    private static final float[] LOCAL = new float[16];
    private static final float[] COMPOSED = new float[16];

    private static int frameParts;
    private static int frameQuads;
    private static long frameNanos;
    private static int lastParts;
    private static int lastQuads;
    private static long lastNanos;
    private static long shapesCached;
    private static long partsSeen;
    private static long matrixReads;
    private static long checks;
    private static long disagreements;
    private static float worstError;

    private EntityCapture() {
    }

    /** Only during the game's own entity pass; a model shown in a GUI is not one. */
    public static void arm() {
        armed = VulkanConfig.isEntityCapture();
        // Timed around the whole pass rather than around each part. Two clock
        // readings per part is tens of nanoseconds against a few hundred being
        // measured, which is a share of the answer large enough to change it.
        passStartedNanos = System.nanoTime();
    }

    private static long passStartedNanos;

    /**
     * Keeps the last pass that drew anything, rather than the last pass.
     *
     * The game runs this pass twice when it is going to draw entities again
     * after the water, and the second run is empty unless a shader mod asked
     * for it. Reporting whichever ran last therefore reported zero on every
     * frame — a counter that said the hook was dead while two million parts a
     * session were going through it.
     */
    public static void disarm() {
        if (!armed) {
            return;
        }
        armed = false;
        depth = 0;
        frameNanos = System.nanoTime() - passStartedNanos;
        if (frameParts > 0) {
            lastParts = frameParts;
            lastQuads = frameQuads;
            lastNanos = frameNanos;
        }
        frameParts = 0;
        frameQuads = 0;
        frameNanos = 0;
    }

    /**
     * The creature's own frame, read from the driver once for the whole model.
     *
     * This is the one place the matrix stack still has to be asked, and it is
     * asked once per creature per layer rather than once per bone — which is
     * the difference between five hundred driver calls a frame and twenty.
     */
    public static void beginModel() {
        if (!armed) {
            return;
        }
        long start = System.nanoTime();
        try {
            depth = 0;
            readMatrix(STACK[0]);
            matrixReads++;
        } catch (Throwable ignored) {
            // A model this does not understand is a model the game still draws.
        } finally {
            frameNanos += System.nanoTime() - start;
        }
    }

    /**
     * One part of one creature, at the moment the game is about to draw it.
     *
     * Called before vanilla's own draw and never instead of it. Anything that
     * throws here would take down the entity pass of a game that is rendering
     * perfectly well without us, so nothing is allowed out.
     */
    public static void beginPart(ModelRenderer part, float scale) {
        if (!armed || part == null) {
            return;
        }
        try {
            if (depth >= MAX_DEPTH - 1) {
                return;
            }
            if (depth == 0) {
                // The creature's own frame, and it has to be read here rather
                // than once per model.
                //
                // The obvious anchor is ModelBase.render, which every model
                // has — and which every model also overrides without calling,
                // so a hook on it fires for almost nothing. The first version
                // did that and composed every bone against a frame left over
                // from some other creature: ninety-eight parts in a hundred
                // landed somewhere else, one of them a hundred and seventy
                // blocks away. Reading per root bone is correct, and what it
                // costs is the measurement below.
                readMatrix(STACK[0]);
                matrixReads++;
            }
            float[] parent = STACK[depth];
            // Checked rarely, because the check is the very thing this is
            // trying to avoid doing often.
            if (depth > 0 && ++partsSeen % CHECK_EVERY == 0) {
                verify(parent);
            }
            localTransform(part, scale, LOCAL);
            multiply(parent, LOCAL, COMPOSED);
            depth++;
            System.arraycopy(COMPOSED, 0, STACK[depth], 0, 16);

            float[] shape = GEOMETRY.get(part);
            if (shape == null) {
                shape = bake(part, scale);
                GEOMETRY.put(part, shape);
                shapesCached++;
            }
            if (shape.length != 0) {
                frameParts++;
                frameQuads += shape.length / (FLOATS_PER_VERTEX * 4);
            }
        } catch (Throwable ignored) {
            // As above.
        }
    }

    /** Leaves the part's frame, so its siblings are placed beside it and not inside it. */
    public static void endPart() {
        if (armed && depth > 0) {
            depth--;
        }
    }

    /**
     * Asks the driver where it thinks we are, and compares.
     *
     * The two are built from the same fields by the same rules, so they should
     * agree to the last bit that floating point allows; the tolerance is for
     * the order the multiplications happen in and nothing else. A disagreement
     * means the composition is wrong somewhere, and this is the only way that
     * shows up while nothing is being drawn.
     */
    private static void verify(float[] expected) {
        readMatrix(READ);
        matrixReads++;
        checks++;
        float worst = 0.0f;
        for (int i = 0; i < 16; i++) {
            worst = Math.max(worst, Math.abs(READ[i] - expected[i]));
        }
        if (worst > AGREEMENT) {
            disagreements++;
        }
        worstError = Math.max(worstError, worst);
    }

    private static void readMatrix(float[] out) {
        MATRIX.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX);
        MATRIX.get(out).clear();
    }

    /**
     * What one part does to the frame it is drawn in.
     *
     * Straight out of the game's own routine: it translates by the offset,
     * translates by the rotation point scaled, and turns about Z, then Y, then
     * X. The order matters and is not the order the fields are declared in.
     * Children are drawn without any of it being undone first, which is what
     * makes the frames nest.
     */
    private static void localTransform(ModelRenderer part, float scale, float[] out) {
        float sinX = (float) Math.sin(part.rotateAngleX);
        float cosX = (float) Math.cos(part.rotateAngleX);
        float sinY = (float) Math.sin(part.rotateAngleY);
        float cosY = (float) Math.cos(part.rotateAngleY);
        float sinZ = (float) Math.sin(part.rotateAngleZ);
        float cosZ = (float) Math.cos(part.rotateAngleZ);

        // Rz * Ry * Rx, written out rather than multiplied three times: this is
        // the innermost thing in the whole capture and it runs once per bone
        // per creature per frame.
        float m00 = cosZ * cosY;
        float m01 = sinZ * cosY;
        float m02 = -sinY;

        float m10 = cosZ * sinY * sinX - sinZ * cosX;
        float m11 = sinZ * sinY * sinX + cosZ * cosX;
        float m12 = cosY * sinX;

        float m20 = cosZ * sinY * cosX + sinZ * sinX;
        float m21 = sinZ * sinY * cosX - cosZ * sinX;
        float m22 = cosY * cosX;

        // Column-major, the way OpenGL and every matrix in this mod stores one.
        out[0] = m00;  out[1] = m01;  out[2] = m02;  out[3] = 0.0f;
        out[4] = m10;  out[5] = m11;  out[6] = m12;  out[7] = 0.0f;
        out[8] = m20;  out[9] = m21;  out[10] = m22; out[11] = 0.0f;
        // The offset is not scaled and the rotation point is; that is the
        // game's own asymmetry, not a slip here.
        out[12] = part.offsetX + part.rotationPointX * scale;
        out[13] = part.offsetY + part.rotationPointY * scale;
        out[14] = part.offsetZ + part.rotationPointZ * scale;
        out[15] = 1.0f;
    }

    /** Column-major 4x4 multiply: out = a * b. Never aliases its output. */
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

    /**
     * Turns a part's boxes into flat vertices, once.
     *
     * The scale is baked in, which matches what the game does: it compiles the
     * part's display list on first use and reuses it at whatever scale came
     * first. A model drawn at two scales is already wrong in vanilla, and
     * copying that is more useful than being right differently.
     */
    private static float[] bake(ModelRenderer part, float scale) {
        java.util.List<ModelBox> boxes = part.cubeList;
        if (boxes == null || boxes.isEmpty()) {
            return new float[0];
        }
        int quads = 0;
        for (ModelBox box : boxes) {
            TexturedQuad[] list = ((ModelBoxAccessor) box).vulkanmod112$quads();
            if (list != null) {
                quads += list.length;
            }
        }
        float[] out = new float[quads * 4 * FLOATS_PER_VERTEX];
        int at = 0;
        for (ModelBox box : boxes) {
            TexturedQuad[] list = ((ModelBoxAccessor) box).vulkanmod112$quads();
            if (list == null) {
                continue;
            }
            for (TexturedQuad quad : list) {
                // The same normal the game works out at bake time, from two
                // edges of the quad. Kept because a lit surface needs it and
                // because it is free here.
                net.minecraft.util.math.Vec3d edge1 =
                        quad.vertexPositions[1].vector3D.subtractReverse(quad.vertexPositions[0].vector3D);
                net.minecraft.util.math.Vec3d edge2 =
                        quad.vertexPositions[1].vector3D.subtractReverse(quad.vertexPositions[2].vector3D);
                net.minecraft.util.math.Vec3d normal = edge2.crossProduct(edge1).normalize();
                for (int i = 0; i < 4; i++) {
                    PositionTextureVertex vertex = quad.vertexPositions[i];
                    out[at++] = (float) (vertex.vector3D.x * scale);
                    out[at++] = (float) (vertex.vector3D.y * scale);
                    out[at++] = (float) (vertex.vector3D.z * scale);
                    out[at++] = vertex.texturePositionX;
                    out[at++] = vertex.texturePositionY;
                    out[at++] = (float) normal.x;
                    out[at++] = (float) normal.y;
                    out[at++] = (float) normal.z;
                }
            }
        }
        return out;
    }

    /** One line for the diagnostics report; this is the whole product so far. */
    public static String stats() {
        if (!VulkanConfig.isEntityCapture()) {
            return "entity capture: off";
        }
        return "entity capture: " + lastParts + " parts, " + lastQuads + " quads last frame, "
                + String.format("%.3f", lastNanos / 1e6) + " ms ("
                + (lastParts == 0 ? "n/a" : String.format("%.0f ns a part", lastNanos / (double) lastParts))
                + "); " + matrixReads + " driver matrix reads over " + partsSeen + " parts; "
                + "placement checked " + checks + " times, " + disagreements + " disagreed, worst "
                + String.format("%.4f", worstError) + " blocks; "
                + shapesCached + " model shapes cached";
    }
}
