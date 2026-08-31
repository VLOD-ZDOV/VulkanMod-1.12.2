package net.vulkanmodnext.client;

import net.minecraft.client.renderer.OpenGlHelper;

/**
 * Which texture the game has bound, kept by watching what it binds.
 *
 * <h2>Why this is wanted</h2>
 *
 * A creature's skin is not part of its geometry. The shape is baked once into
 * a display list; which picture is stretched over it is decided a moment
 * before the model is drawn, by a texture bind, and nothing in the model says
 * what it was. To draw a creature ourselves we have to know that — and to know
 * how many different ones a scene needs, which is the number that decides
 * whether a cache of these fits in video memory at all.
 *
 * <h2>Why watched rather than asked</h2>
 *
 * {@code glGetInteger(GL_TEXTURE_BINDING_2D)} is a driver round trip, and this
 * would be asked once per model part — the same shape of cost that made asking
 * the driver for a bone's matrix untenable at two and a half microseconds a
 * call. Every bind already goes through one method, and mirroring an integer
 * assignment costs nothing measurable.
 *
 * <h2>What it cannot see</h2>
 *
 * A mod calling {@code GL11.glBindTexture} directly. That is why what is stored
 * is treated as a hint about which creature is being drawn rather than as
 * something to render from without checking — and why the count of distinct
 * textures is reported, so a number that looks wrong can be seen to be wrong.
 */
public final class GlTextureMirror {

    /** Textures bound per unit; only the first is of any interest here. */
    private static final int UNITS = 8;
    private static final int[] BOUND = new int[UNITS];
    private static int activeUnit;

    private GlTextureMirror() {
    }

    /**
     * The game hands this the raw enum, the same as OpenGL takes, and turns it
     * into an index the same way — subtracting the enum of the first unit.
     */
    public static void setActiveTexture(int textureEnum) {
        int unit = textureEnum - OpenGlHelper.defaultTexUnit;
        activeUnit = unit >= 0 && unit < UNITS ? unit : -1;
    }

    public static void bindTexture(int texture) {
        if (activeUnit >= 0) {
            BOUND[activeUnit] = texture;
        }
    }

    /** What is bound on the unit a model's own skin goes on. 0 when nothing is. */
    public static int boundOnDefaultUnit() {
        return BOUND[0];
    }

    /**
     * Whether the calls happening now are about the unit a skin is sampled on.
     *
     * OpenGL keeps a texture matrix per unit, and the game uses that: the light
     * map lives on its own unit with a permanent scale and offset on it, set
     * once and never taken off. Anything mirroring the texture matrix without
     * asking which unit it belongs to reads that offset as though it were the
     * skin's, which is the difference between "nothing is transforming these
     * coordinates" and "everything is".
     */
    public static boolean onDefaultUnit() {
        return activeUnit == 0;
    }

    /**
     * The colour and the light the fixed-function pipeline would have applied.
     *
     * A model's vertices carry neither. The display list holds position,
     * texture coordinate and normal; everything else — the white a sheep is
     * tinted, the red flash of something hurt, the dye on leather, and how much
     * light the creature stands in — is state set just before the draw and
     * multiplied in by a pipeline that no longer exists once we are the one
     * drawing. So it is mirrored at the two places the game sets it.
     */
    private static float red = 1.0f;
    private static float green = 1.0f;
    private static float blue = 1.0f;
    private static float alpha = 1.0f;
    private static float lightU;
    private static float lightV;

    public static void color(float r, float g, float b, float a) {
        red = r;
        green = g;
        blue = b;
        alpha = a;
    }

    /** Packed the way a vertex wants it: red in the low byte through to alpha. */
    public static int packedColor() {
        return clampByte(red) | clampByte(green) << 8 | clampByte(blue) << 16
                | clampByte(alpha) << 24;
    }

    private static int clampByte(float v) {
        int i = (int) (v * 255.0f + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    public static void lightmap(float u, float v) {
        lightU = u;
        lightV = v;
    }

    public static short lightU() {
        return (short) lightU;
    }

    public static short lightV() {
        return (short) lightV;
    }

    public static int activeUnit() {
        return activeUnit;
    }
}
