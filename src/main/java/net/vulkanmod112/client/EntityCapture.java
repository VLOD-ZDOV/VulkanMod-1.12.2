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
    /**
     * How many checks are worth doing before the answer is in.
     *
     * The check reads the driver's matrix, and reading the driver is a sync
     * point: the processor waits for the graphics queue to reach it. Early in a
     * session that costs two or three microseconds. In a loaded scene it was
     * measured at around fifty — and at ten checks a frame that is half a
     * millisecond, which is more than the whole vanilla entity pass costs and
     * far more than the thing being verified.
     *
     * The measurement said so plainly and it took a session's log to see it:
     * the reported cost of the capture rose steadily — 0.28, 0.35, 0.43, 0.55
     * milliseconds — while the number of parts per frame *fell*. Work that grows
     * as the scene grows heavier, without the work itself growing, is a wait,
     * not a cost.
     *
     * So the check stops once it has proved the point. Two hundred and eighty
     * thousand comparisons with not one disagreement is not a hypothesis any
     * more. -Dvulkanmod112.checkEntityPoseForever=true brings it back for when
     * the composition is changed again, which is the only time it is in doubt.
     */
    private static final int CHECK_BUDGET =
            Integer.getInteger("vulkanmod112.entityPoseChecks", 20_000);
    private static final boolean CHECK_FOREVER =
            "true".equals(System.getProperty("vulkanmod112.checkEntityPoseForever"));
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

    /**
     * Which skins the scene needs, and what copying them all would cost.
     *
     * This is the number the whole entity plan turns on. Terrain has one atlas;
     * creatures have a picture each, plus one per armour piece and one per held
     * item, and they are the game's own textures living in OpenGL. Every one
     * has to be copied into Vulkan before anything can be drawn with it, and
     * "how many, and how big" decides whether that is a cache with eviction or
     * simply a list.
     *
     * Guessing was possible and useless: a lone player in a field and a mob
     * farm are different questions, and only a real world answers either.
     *
     * Looked up once per creature rather than once per bone — the skin does not
     * change between the parts of one model, so comparing against the last one
     * seen skips the map for all but the first bone of each.
     */
    private static final java.util.HashMap<Integer, int[]> TEXTURE_SIZES =
            new java.util.HashMap<Integer, int[]>();
    private static int lastTextureSeen = -1;
    private static long texturePixels;
    private static int frameTextures;
    private static int lastFrameTextures;
    private static final java.util.HashSet<Integer> FRAME_TEXTURES =
            new java.util.HashSet<Integer>();

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
    private static boolean checking = true;
    private static long disagreements;
    private static float worstError;

    private EntityCapture() {
    }

    /** Only during the game's own entity pass; a model shown in a GUI is not one. */
    /**
     * Armed on every other frame, so that the cost is a difference and not a
     * guess.
     *
     * Timing the pass with a clock at each end measures the game's own entity
     * rendering, of which this is a small part — the first version reported
     * that number as though it were the overhead, which made a hook doing
     * almost nothing look like it cost a millisecond. Timing each part instead
     * would have the clock cost a share of the answer large enough to change
     * it. So the whole pass is timed both ways, alternately, and what is
     * reported is what one costs over the other.
     */
    private static boolean everyOther;

    public static void arm() {
        everyOther = !everyOther;
        armed = VulkanConfig.isEntityCapture() && everyOther;
        measuring = VulkanConfig.isEntityCapture();
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
        if (!measuring) {
            return;
        }
        measuring = false;
        long elapsed = System.nanoTime() - passStartedNanos;
        if (armed) {
            armedNanos += elapsed;
            armedFrames++;
            if (frameParts > 0) {
                lastParts = frameParts;
                lastQuads = frameQuads;
                lastFrameTextures = frameTextures;
            }
        } else {
            bareNanos += elapsed;
            bareFrames++;
        }
        armed = false;
        depth = 0;
        frameParts = 0;
        frameQuads = 0;
        frameTextures = 0;
        lastTextureSeen = -1;
        FRAME_TEXTURES.clear();
    }

    private static boolean measuring;
    private static long armedNanos;
    private static long armedFrames;
    private static long bareNanos;
    private static long bareFrames;

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
                // The creature's own frame, taken from the mirror rather than
                // from the driver.
                //
                // Two anchors were tried before this one. The model's own
                // render method is overridden by every model without calling
                // the base, so a hook there fired for almost nothing and every
                // bone was composed against a frame left over from another
                // creature. Reading per root bone is correct and saves nothing:
                // a creature's bones are almost all roots, so once per root is
                // once per bone, and the cost came back exactly where it
                // started. The mirror removes the driver from the question.
                System.arraycopy(GlMatrixMirror.current(), 0, STACK[0], 0, 16);
            }
            float[] parent = STACK[depth];
            // Checked rarely, because the check is the very thing this is
            // trying to avoid doing often.
            // Counted before the check and not inside it: the two used to be
            // one expression, and the short circuit meant the count only ever
            // saw the bones that were not roots — which is one in a hundred.
            partsSeen++;
            if (checking && partsSeen % CHECK_EVERY == 0) {
                verify(parent);
                if (!CHECK_FOREVER && checks >= CHECK_BUDGET) {
                    checking = false;
                }
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
                noteTexture();
            }
        } catch (Throwable ignored) {
            // As above.
        }
    }

    /**
     * Records which skin this part is about to be drawn with.
     *
     * Its size is asked of the driver exactly once per texture per session —
     * a couple of hundred calls in a long session against a couple of hundred
     * thousand parts, so the cost is nowhere near the hot path.
     */
    private static void noteTexture() {
        int texture = GlTextureMirror.boundOnDefaultUnit();
        if (texture <= 0 || texture == lastTextureSeen) {
            return;
        }
        lastTextureSeen = texture;
        if (FRAME_TEXTURES.add(texture)) {
            frameTextures++;
        }
        if (TEXTURE_SIZES.containsKey(texture)) {
            return;
        }
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        if (w <= 0 || h <= 0) {
            // Not a two-dimensional texture we can copy; recorded as known so
            // it is not asked about again.
            TEXTURE_SIZES.put(texture, new int[]{0, 0});
            return;
        }
        TEXTURE_SIZES.put(texture, new int[]{w, h});
        texturePixels += (long) w * h;
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
    /**
     * Shared with the drawing path, which composes the same poses for real.
     *
     * One copy of this arithmetic, not two: it was verified against the driver
     * two hundred and eighty thousand times, and a second copy would be a
     * second thing to verify.
     */
    static void localTransform(ModelRenderer part, float scale, float[] out) {
        // The game's own sine table rather than the library's. It is a lookup
        // into sixty-five thousand precomputed values, which is what vanilla
        // uses everywhere it animates anything — and twelve calls to the real
        // thing per bone was most of what this method cost.
        float sinX = net.minecraft.util.math.MathHelper.sin(part.rotateAngleX);
        float cosX = net.minecraft.util.math.MathHelper.cos(part.rotateAngleX);
        float sinY = net.minecraft.util.math.MathHelper.sin(part.rotateAngleY);
        float cosY = net.minecraft.util.math.MathHelper.cos(part.rotateAngleY);
        float sinZ = net.minecraft.util.math.MathHelper.sin(part.rotateAngleZ);
        float cosZ = net.minecraft.util.math.MathHelper.cos(part.rotateAngleZ);

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

    /**
     * out = a * b, for matrices whose last row is 0 0 0 1.
     *
     * Every matrix in this chain is a rotation and a shift, so the fourth row
     * is known and the fourth column of the product is the only one that needs
     * the shift added. Twenty-seven multiplications instead of sixty-four, on
     * the one piece of arithmetic that runs once per bone of every creature on
     * screen.
     */
    private static void multiply(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 3; col++) {
            int c = col * 4;
            float b0 = b[c];
            float b1 = b[c + 1];
            float b2 = b[c + 2];
            out[c] = a[0] * b0 + a[4] * b1 + a[8] * b2;
            out[c + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2;
            out[c + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2;
            out[c + 3] = 0.0f;
        }
        float b0 = b[12];
        float b1 = b[13];
        float b2 = b[14];
        out[12] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12];
        out[13] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13];
        out[14] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14];
        out[15] = 1.0f;
    }

    /**
     * Turns a part's boxes into flat vertices, once.
     *
     * The scale is baked in, which matches what the game does: it compiles the
     * part's display list on first use and reuses it at whatever scale came
     * first. A model drawn at two scales is already wrong in vanilla, and
     * copying that is more useful than being right differently.
     */
    /** The part's geometry, baked once and kept; empty when it has none. */
    static float[] shapeOf(ModelRenderer part, float scale) {
        float[] shape = GEOMETRY.get(part);
        if (shape == null) {
            shape = bake(part, scale);
            GEOMETRY.put(part, shape);
            shapesCached++;
        }
        return shape;
    }

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
        double withCapture = armedFrames == 0 ? 0.0 : armedNanos / (double) armedFrames;
        double without = bareFrames == 0 ? 0.0 : bareNanos / (double) bareFrames;
        double overhead = withCapture - without;
        return "entity capture: " + lastParts + " parts, " + lastQuads + " quads a frame; "
                + "entity pass " + String.format("%.3f", withCapture / 1e6) + " ms with, "
                + String.format("%.3f", without / 1e6) + " ms without, so "
                + String.format("%.3f", overhead / 1e6) + " ms"
                + (lastParts == 0 ? "" : String.format(" (%.0f ns a part)", overhead / lastParts))
                + " is ours; " + matrixReads + " driver reads over " + partsSeen + " parts; "
                + "placement checked " + checks + " times, " + disagreements + " disagreed, worst "
                + String.format("%.4f", worstError) + " blocks"
                + (checking ? "" : " (checking stopped; it costs a driver sync)") + "; "
                + shapesCached + " model shapes cached"
                // What drawing these would cost before a single triangle: the
                // skins have to be copied into Vulkan, and this says how many
                // and how much. A frame's worth is what a cache has to hold at
                // once; the session's total is what it has to hold if nothing
                // is ever evicted.
                + String.format("; skins %d distinct (%.1f MiB as RGBA), %d in the busiest frame; "
                        + "geometry %.0f KiB a frame",
                        TEXTURE_SIZES.size(), texturePixels * 4.0 / (1024.0 * 1024.0),
                        lastFrameTextures, lastQuads * 4.0 * 28.0 / 1024.0);
    }
}
