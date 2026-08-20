package net.vulkanmod112.client;

import java.nio.FloatBuffer;

/**
 * A copy of OpenGL's model-view matrix, and of one of its texture matrices,
 * kept by watching what is done to them.
 *
 * <h2>Why this is here at all</h2>
 *
 * Where an entity's bone is lives in the driver's matrix stack, and asking the
 * driver where it is costs about two and a half microseconds. Composing a
 * bone's own transform from its fields was measured to be exact — two thousand
 * checks against the driver, not one disagreement, not a thousandth of a block
 * of error — but it needs a frame to start from, and the frame it starts from
 * is the creature's, which the game builds with matrix calls. The obvious
 * anchor, the model's own render method, is overridden by every model without
 * calling the base one. The next anchor, the root bone, works and saves
 * nothing: a creature's bones are almost all roots, so reading once per root
 * is reading once per bone, and the measurement came back exactly where it
 * started — a millisecond a frame.
 *
 * So the driver is taken out of the question entirely. Every call that moves
 * the matrix goes through {@code GlStateManager}, and every one of them is a
 * few multiplications. Doing them here as well costs a fraction of what asking
 * the answer back costs.
 *
 * <h2>What it cannot see, and why that is survivable</h2>
 *
 * Anything that changes the matrix without going through {@code GlStateManager}
 * — a mod calling {@code GL11.glTranslatef} directly. That is why the mirror is
 * checked against the driver on a sample of bones rather than trusted: a mod
 * doing that shows up as a disagreement count, in the diagnostics, rather than
 * as a creature drawn in the wrong place.
 */
public final class GlMatrixMirror {

    /** GL_MODELVIEW. Modes other than these two are tracked only enough to be ignored. */
    private static final int MODELVIEW = 5888;
    /**
     * GL_TEXTURE, and the reason it is here at all.
     *
     * Exactly one thing in the game moves this matrix on a creature: the glint
     * of enchanted armour, which draws the model twice more with the texture
     * coordinates scaled, turned and slid along, and the sliding is what makes
     * it shimmer. Nothing about that reaches the vertices — the model, its
     * bones and its texture coordinates are identical in all three passes —
     * so a renderer that captures geometry and ignores this captures three
     * copies of the same thing and can only draw one of them.
     *
     * <b>Only the unit a skin is sampled on is followed.</b> OpenGL keeps one
     * of these per texture unit, and the game leans on that: {@code
     * EntityRenderer.enableLightmap} puts a scale of a two hundred and
     * fifty-sixth and an offset of eight on the light map's unit, once, and
     * never takes them off again. Followed without asking which unit it
     * belonged to, that offset was read as the skin's — so from the first
     * frame of every session every creature looked like a glint, and was drawn
     * as one: added rather than laid down, no depth of its own, and its
     * texture read from a single texel. On screen that is a creature you can
     * see through.
     */
    private static final int TEXTURE = 5890;
    private static final int DEPTH = 48;
    /** Two would do for the glint; eight is room for a mod with an opinion. */
    private static final int TEXTURE_DEPTH = 8;

    private static final float[][] STACK = new float[DEPTH][16];
    private static final float[][] TEXTURE_STACK = new float[TEXTURE_DEPTH][16];
    private static int top;
    private static int textureTop;
    /**
     * Whether anything has touched the texture matrix at each level of its
     * stack, since that level was last reset.
     *
     * Cheaper than comparing sixteen floats on every part, and wrong only in
     * the harmless direction: a transform that happens to be the identity is
     * reported as present and applied, which changes nothing.
     *
     * One per level rather than one flag, because the two things in the game
     * that move this matrix reset it in different ways. The glint of enchanted
     * armour loads the identity back when it is done; the glint of an
     * enchanted item in a slot pushes, moves, and pops — and a single flag,
     * having no way to know what a pop undid, stayed set from the first
     * enchanted thing on the hotbar until the game was closed.
     */
    private static final boolean[] TEXTURE_MOVED = new boolean[TEXTURE_DEPTH];
    /** Pushes past the top of the texture stack, so pops can be paired with them. */
    private static int textureOverflow;
    private static int mode = MODELVIEW;
    private static final float[] SCRATCH = new float[16];
    private static final float[] PRODUCT = new float[16];

    static {
        identity(STACK[0]);
        for (int i = 0; i < TEXTURE_DEPTH; i++) {
            identity(TEXTURE_STACK[i]);
        }
    }

    private GlMatrixMirror() {
    }

    /** The model-view matrix as this mirror believes it to be, column-major. */
    public static float[] current() {
        return STACK[top];
    }

    public static void setMode(int newMode) {
        mode = newMode;
    }

    /** The texture matrix as this mirror believes it to be, column-major. */
    public static float[] currentTexture() {
        return TEXTURE_STACK[textureTop];
    }

    /** False when texture coordinates may be used exactly as they arrived. */
    public static boolean textureMoved() {
        return TEXTURE_MOVED[textureTop];
    }

    /**
     * Which matrix the calls below are talking about, or null for a mode this
     * mirror does not follow.
     */
    private static float[] target() {
        if (mode == MODELVIEW) {
            return STACK[top];
        }
        if (mode == TEXTURE && GlTextureMirror.onDefaultUnit()) {
            return TEXTURE_STACK[textureTop];
        }
        return null;
    }

    /** True while the calls coming in are about the texture matrix we follow. */
    private static boolean onTexture() {
        return mode == TEXTURE && GlTextureMirror.onDefaultUnit();
    }

    private static void markTextureMoved() {
        if (onTexture()) {
            TEXTURE_MOVED[textureTop] = true;
        }
    }

    public static void push() {
        if (mode == TEXTURE) {
            if (!GlTextureMirror.onDefaultUnit()) {
                return;
            }
            if (textureTop + 1 < TEXTURE_DEPTH) {
                System.arraycopy(TEXTURE_STACK[textureTop], 0,
                        TEXTURE_STACK[textureTop + 1], 0, 16);
                TEXTURE_MOVED[textureTop + 1] = TEXTURE_MOVED[textureTop];
                textureTop++;
            } else {
                // Counted rather than dropped: an unpaired pop would hand back
                // a level that was never left, and the transform of whatever
                // pushed would stay applied to everything after it.
                textureOverflow++;
            }
            return;
        }
        if (mode != MODELVIEW) {
            return;
        }
        if (top + 1 < DEPTH) {
            System.arraycopy(STACK[top], 0, STACK[top + 1], 0, 16);
            top++;
        } else {
            // Deeper than the driver's own guaranteed depth. Nothing useful is
            // left to mirror, and the check against the driver will say so.
            overflowed = true;
        }
    }

    public static void pop() {
        if (mode == TEXTURE) {
            if (!GlTextureMirror.onDefaultUnit()) {
                return;
            }
            if (textureOverflow > 0) {
                textureOverflow--;
            } else if (textureTop > 0) {
                textureTop--;
            }
            return;
        }
        if (mode != MODELVIEW) {
            return;
        }
        if (top > 0) {
            top--;
        }
    }

    public static void loadIdentity() {
        float[] m = target();
        if (m == null) {
            return;
        }
        identity(m);
        if (mode == TEXTURE) {
            // The glint resets this before each of its two passes and again
            // when it is done, so this is also how the ordinary case gets its
            // "nothing to apply" back.
            TEXTURE_MOVED[textureTop] = false;
        }
    }

    public static void translate(double x, double y, double z) {
        float[] m = target();
        if (m == null) {
            return;
        }
        markTextureMoved();
        // The last column moved by the matrix's own basis: the same thing
        // multiplying by a translation would do, without the other twelve
        // products that are all zero or one.
        float fx = (float) x;
        float fy = (float) y;
        float fz = (float) z;
        m[12] += m[0] * fx + m[4] * fy + m[8] * fz;
        m[13] += m[1] * fx + m[5] * fy + m[9] * fz;
        m[14] += m[2] * fx + m[6] * fy + m[10] * fz;
        m[15] += m[3] * fx + m[7] * fy + m[11] * fz;
    }

    public static void scale(double x, double y, double z) {
        float[] m = target();
        if (m == null) {
            return;
        }
        markTextureMoved();
        float fx = (float) x;
        float fy = (float) y;
        float fz = (float) z;
        for (int row = 0; row < 4; row++) {
            m[row] *= fx;
            m[4 + row] *= fy;
            m[8 + row] *= fz;
        }
    }

    /**
     * Angle in degrees about an arbitrary axis, exactly as OpenGL defines it.
     *
     * The general form rather than three special cases, because the game turns
     * models about single axes and mods do not have to.
     */
    public static void rotate(float angleDegrees, float x, float y, float z) {
        if (target() == null) {
            return;
        }
        markTextureMoved();
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length == 0.0f) {
            return;
        }
        float ax = x / length;
        float ay = y / length;
        float az = z / length;
        double radians = Math.toRadians(angleDegrees);
        float c = (float) Math.cos(radians);
        float s = (float) Math.sin(radians);
        float ic = 1.0f - c;

        SCRATCH[0] = ax * ax * ic + c;
        SCRATCH[1] = ay * ax * ic + az * s;
        SCRATCH[2] = az * ax * ic - ay * s;
        SCRATCH[3] = 0.0f;
        SCRATCH[4] = ax * ay * ic - az * s;
        SCRATCH[5] = ay * ay * ic + c;
        SCRATCH[6] = az * ay * ic + ax * s;
        SCRATCH[7] = 0.0f;
        SCRATCH[8] = ax * az * ic + ay * s;
        SCRATCH[9] = ay * az * ic - ax * s;
        SCRATCH[10] = az * az * ic + c;
        SCRATCH[11] = 0.0f;
        SCRATCH[12] = 0.0f;
        SCRATCH[13] = 0.0f;
        SCRATCH[14] = 0.0f;
        SCRATCH[15] = 1.0f;
        multiplyInto(target(), SCRATCH);
    }

    /** The buffer is the caller's and its position is not disturbed. */
    public static void multiply(FloatBuffer matrix) {
        float[] m = target();
        if (m == null || matrix == null || matrix.remaining() < 16) {
            return;
        }
        markTextureMoved();
        int at = matrix.position();
        for (int i = 0; i < 16; i++) {
            SCRATCH[i] = matrix.get(at + i);
        }
        multiplyInto(m, SCRATCH);
    }

    private static void multiplyInto(float[] target, float[] right) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += target[k * 4 + row] * right[col * 4 + k];
                }
                PRODUCT[col * 4 + row] = sum;
            }
        }
        System.arraycopy(PRODUCT, 0, target, 0, 16);
    }

    private static void identity(float[] m) {
        java.util.Arrays.fill(m, 0.0f);
        m[0] = 1.0f;
        m[5] = 1.0f;
        m[10] = 1.0f;
        m[15] = 1.0f;
    }

    private static boolean overflowed;

    public static boolean hasOverflowed() {
        return overflowed;
    }

    public static int depth() {
        return top;
    }
}
