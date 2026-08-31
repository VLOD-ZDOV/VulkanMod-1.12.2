package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;

/**
 * A round, warm sun, drawn rather than shipped.
 *
 * <h2>Why a picture built at runtime and not a file</h2>
 *
 * Because then it is a slider. Vanilla's sun is a square texture on a fixed
 * quad, and the quad is not ours to resize without cutting into the middle of
 * a vanilla method — but the disc inside the texture is entirely ours, so how
 * much of that square it fills *is* the size control, for free. The same is
 * true of its colour and of how soft its edge is. A file would have frozen all
 * three at whatever looked right on one machine.
 *
 * It also keeps this mod's promise about shader packs: nothing is copied from
 * anybody. A disc with a warm falloff is arithmetic, and the arithmetic is
 * here in the open.
 *
 * <h2>How it is drawn</h2>
 *
 * Vanilla draws the sun added to the sky rather than blended over it, so what
 * matters is the falloff: a hard circle reads as a sticker, and a circle that
 * fades too far reads as fog. The edge is smoothed over a small band, and
 * outside the disc there is a much wider, much fainter halo — that halo is
 * what makes it look like light rather than like a shape.
 */
public final class SunSkin {

    private static final int SIZE = 128;

    private static ResourceLocation location;
    private static int builtFor = Integer.MIN_VALUE;

    private SunSkin() {
    }

    /** Null when the vanilla sun is wanted. */
    public static ResourceLocation current() {
        if (!VulkanConfig.isRoundSun()) {
            return null;
        }
        int wanted = VulkanConfig.getSunSize() * 1000 + VulkanConfig.getSunWarmth();
        if (location != null && builtFor == wanted) {
            return location;
        }
        try {
            location = build();
            builtFor = wanted;
        } catch (Throwable t) {
            // A sun we cannot draw is a sun the game draws instead.
            location = null;
        }
        return location;
    }

    private static ResourceLocation build() {
        return disc(false);
    }

    /**
     * How much of the square's half-width the disc itself may take.
     *
     * The first version went up to nine tenths and read as enormous — but the
     * disc was not what made it enormous, the halo was. A glow that runs into
     * the edge of the texture is a glow with a straight side, and the eye reads
     * that square long before it reads the circle inside it. Both ends of this
     * range now leave the halo somewhere to fade out.
     */
    private static final float MIN_DISC = 0.08f;
    private static final float MAX_DISC = 0.34f;
    /** Where the glow must have reached nothing at all, short of the border. */
    private static final float HALO_LIMIT = 0.94f;

    private static ResourceLocation disc(boolean moon) {
        float fill = MIN_DISC + VulkanConfig.getSunSize() / 100.0f * (MAX_DISC - MIN_DISC);
        float warmth = moon ? 0.0f : VulkanConfig.getSunWarmth() / 100.0f;
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        float centre = (SIZE - 1) / 2.0f;
        float radius = centre * fill;
        float edge = Math.max(1.2f, radius * 0.16f);
        // Clamped so the glow is already nothing before the texture runs out.
        // Without this the halo is cut off by the border and the sun grows a
        // square, which is exactly what it looked like.
        float haloReach = Math.min(radius * 3.4f, centre * HALO_LIMIT);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float dx = x - centre;
                float dy = y - centre;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                float disc = clamp((radius - distance) / edge);
                float halo = 0.0f;
                if (distance > radius - edge && distance < haloReach) {
                    float t = clamp((haloReach - distance) / Math.max(1.0f, haloReach - radius));
                    // Cubed rather than squared: the glow has to be gone well
                    // before it runs out of room, not merely faint there.
                    halo = t * t * t * (moon ? 0.16f : 0.26f);
                }
                float alpha = Math.min(1.0f, disc + halo * (1.0f - disc));
                if (alpha <= 0.004f) {
                    image.setRGB(x, y, 0);
                    continue;
                }
                float toRim = clamp(distance / Math.max(1.0f, radius));
                float red = moon ? 0.92f : 1.0f;
                float green = moon ? 0.95f : 1.0f - 0.28f * warmth * toRim;
                float blue = moon ? 1.0f : 1.0f - 0.72f * warmth * toRim - 0.15f * warmth;
                image.setRGB(x, y, (int) (alpha * 255) << 24
                        | (int) (clamp(red) * 255) << 16
                        | (int) (clamp(green) * 255) << 8
                        | (int) (clamp(blue) * 255));
            }
        }
        return upload(moon ? "vulkanmodnext_moon_disc" : "vulkanmodnext_sun", image);
    }

    /**
     * The moon, as the eight pictures the game expects rather than as one.
     *
     * Vanilla does not draw a moon; it draws one cell of a four-by-two sheet,
     * chosen by tonight's phase, and a single disc put in its place would be
     * full every night of the month. So the sheet is built the way the sky
     * makes it: a lit disc with the shadow of the world creeping across it,
     * which is one circle cut by a moving ellipse and no more than that.
     *
     * Cell zero is full and cell four is new, because that is the order the
     * game's own sheet is in and it is the game that indexes it.
     */
    private static ResourceLocation moonSheet() {
        int cell = SIZE / 2;
        BufferedImage image = new BufferedImage(cell * 4, cell * 2, BufferedImage.TYPE_INT_ARGB);
        float centre = (cell - 1) / 2.0f;
        // A third of the cell rather than two thirds. The first version filled
        // it, and a moon that fills its cell is drawn at the full width of the
        // quad the game gives it — which is a great deal larger than vanilla's
        // own moon, because vanilla's does not fill its cell either.
        float radius = centre * (0.16f + VulkanConfig.getMoonSize() / 100.0f * 0.34f);
        float edge = Math.max(1.0f, radius * 0.14f);
        float haloReach = Math.min(radius * 2.0f, centre * HALO_LIMIT);
        for (int phase = 0; phase < 8; phase++) {
            int originX = (phase % 4) * cell;
            int originY = (phase / 4) * cell;
            // -1 leaves the whole disc lit, +1 leaves none of it, and halfway
            // between is the straight edge of a quarter moon.
            float terminator = (float) -Math.cos(Math.PI * phase / 4.0);
            for (int y = 0; y < cell; y++) {
                for (int x = 0; x < cell; x++) {
                    float dx = x - centre;
                    float dy = y - centre;
                    float distance = (float) Math.sqrt(dx * dx + dy * dy);
                    float disc = clamp((radius - distance) / edge);
                    if (disc > 0.0f) {
                        // The shadow's edge is an ellipse across the disc, which
                        // is what makes a crescent curve instead of being cut
                        // off with a ruler.
                        float halfWidth = (float) Math.sqrt(
                                Math.max(0.0, radius * radius - dy * dy));
                        float shadowEdge = terminator * halfWidth;
                        float lit = clamp((dx - shadowEdge) / Math.max(1.0f, edge));
                        disc *= lit;
                    }
                    float halo = 0.0f;
                    if (disc > 0.0f && distance > radius - edge && distance < haloReach) {
                        float t = clamp((haloReach - distance)
                                / Math.max(1.0f, haloReach - radius));
                        halo = t * t * t * 0.14f;
                    }
                    float alpha = Math.min(1.0f, disc + halo * (1.0f - disc));
                    if (alpha <= 0.004f) {
                        image.setRGB(originX + x, originY + y, 0);
                        continue;
                    }
                    image.setRGB(originX + x, originY + y,
                            (int) (alpha * 255) << 24 | 0xEAF0FF);
                }
            }
        }
        return upload("vulkanmodnext_moon", image);
    }

    private static ResourceLocation moonLocation;
    private static int moonBuiltFor = Integer.MIN_VALUE;

    /** Null when the vanilla moon is wanted. */
    public static ResourceLocation currentMoon() {
        if (!VulkanConfig.isRoundMoon()) {
            return null;
        }
        int wanted = VulkanConfig.getMoonSize();
        if (moonLocation != null && moonBuiltFor == wanted) {
            return moonLocation;
        }
        try {
            moonLocation = moonSheet();
            moonBuiltFor = wanted;
        } catch (Throwable t) {
            moonLocation = null;
        }
        return moonLocation;
    }

    private static ResourceLocation upload(String name, BufferedImage image) {
        return Minecraft.getMinecraft().getTextureManager()
                .getDynamicTextureLocation(name, new DynamicTexture(image));
    }

    private static float clamp(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }
}
