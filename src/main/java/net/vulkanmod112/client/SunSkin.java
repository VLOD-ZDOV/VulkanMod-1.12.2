package net.vulkanmod112.client;

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
        // How much of the square the disc fills. Vanilla's own sun covers most
        // of its texture, so the middle of the range lands near where the game
        // put it and the ends are visibly smaller and larger.
        float fill = 0.25f + VulkanConfig.getSunSize() / 100.0f * 0.65f;
        float warmth = VulkanConfig.getSunWarmth() / 100.0f;
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        float centre = (SIZE - 1) / 2.0f;
        float radius = centre * fill;
        // The edge is softened over a band that grows with the disc, so a small
        // sun does not turn into a smudge and a large one keeps an edge.
        float edge = Math.max(1.5f, radius * 0.10f);
        float haloReach = radius * 2.2f;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float dx = x - centre;
                float dy = y - centre;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                float disc = clamp((radius - distance) / edge);
                // Faint, wide, and falling off as the square of the distance,
                // which is what makes it read as glare around a light instead
                // of a second ring.
                float halo = 0.0f;
                if (distance > radius - edge && distance < haloReach) {
                    float t = clamp((haloReach - distance) / (haloReach - radius + edge));
                    halo = t * t * 0.30f;
                }
                float alpha = Math.min(1.0f, disc + halo * (1.0f - disc));
                if (alpha <= 0.002f) {
                    image.setRGB(x, y, 0);
                    continue;
                }
                // White at the centre, warm at the rim: a sun that is one flat
                // colour looks painted on, and the game's own is not flat
                // either.
                float toRim = clamp(distance / Math.max(1.0f, radius));
                float red = 1.0f;
                float green = 1.0f - 0.28f * warmth * toRim;
                float blue = 1.0f - 0.72f * warmth * toRim - 0.15f * warmth;
                image.setRGB(x, y, (int) (alpha * 255) << 24
                        | (int) (clamp(red) * 255) << 16
                        | (int) (clamp(green) * 255) << 8
                        | (int) (clamp(blue) * 255));
            }
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc.getTextureManager().getDynamicTextureLocation(
                "vulkanmod112_sun", new DynamicTexture(image));
    }

    private static float clamp(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }
}
