package net.vulkanmod112.client;

import net.minecraft.client.renderer.GlStateManager;

/**
 * Save a settings profile called 67 and the settings screen starts nodding.
 *
 * That is the whole of it. It reads the list of saved profiles, and if one of
 * them is named 67 the screen rocks gently up and down for as long as it is
 * open. Nothing else in the mod knows this exists, nothing is written anywhere,
 * and deleting the profile stops it.
 *
 * Rocked rather than shaken: the screen is a thing people read, and a moving
 * page that cannot be read is a bug however it was meant. Half a character
 * height, slowly, is enough to be plainly deliberate.
 */
public final class SixSeven {

    private static final String NAME = "67";
    /** Half a line of text, so nothing ever leaves the screen. */
    private static final float REACH = 5.0f;
    private static final float SPEED = 2.6f;

    /** Rechecked on a timer rather than per frame: it reads a directory. */
    private static long checkedAt;
    private static boolean armed;

    private SixSeven() {
    }

    public static boolean armed() {
        long now = System.currentTimeMillis();
        if (now - checkedAt > 1000L) {
            checkedAt = now;
            try {
                armed = VulkanProfiles.exists(NAME);
            } catch (Throwable ignored) {
                armed = false;
            }
        }
        return armed;
    }

    /** Pushes a matrix and rocks it. Always paired with {@link #end()}. */
    public static void begin() {
        GlStateManager.pushMatrix();
        if (!armed()) {
            return;
        }
        float t = System.nanoTime() / 1_000_000_000.0f;
        GlStateManager.translate(0.0f, (float) Math.sin(t * SPEED) * REACH, 0.0f);
    }

    public static void end() {
        GlStateManager.popMatrix();
    }
}
