package net.vulkanmodnext.client;

import net.minecraft.client.renderer.GlStateManager;

/**
 * Save a settings profile called 67 and the screen nods once.
 *
 * That is the whole of it. Five seconds, once, at the moment the profile is
 * written — not for as long as it exists. A screen that rocks whenever you open
 * it stops being a joke by the third time and becomes a thing to turn off.
 *
 * Rocked rather than shaken, and faded in and out rather than started and
 * stopped: the settings screen is a thing people read, and a page that jumps
 * reads as a fault however it was meant.
 */
public final class SixSeven {

    /** The name that arms it. */
    public static final String NAME = "67";

    /** Half a line of text, so nothing ever leaves the screen. */
    private static final float REACH = 5.0f;
    private static final float SPEED = 2.6f;
    private static final long DURATION_MILLIS = 5000L;

    private static long startedAt;

    private SixSeven() {
    }

    /** Called where a profile is written, and only for that one name. */
    public static void noteProfileSaved(String name) {
        if (NAME.equals(name)) {
            startedAt = System.currentTimeMillis();
        }
    }

    /** Pushes a matrix and rocks it while the five seconds last. Pair with {@link #end()}. */
    public static void begin() {
        GlStateManager.pushMatrix();
        if (startedAt == 0L) {
            return;
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed < 0L || elapsed >= DURATION_MILLIS) {
            startedAt = 0L;
            return;
        }
        // Full strength in the middle, nothing at either end: a sine over the
        // whole five seconds is zero where it begins and where it stops, so the
        // page never jumps into or out of the motion.
        float through = elapsed / (float) DURATION_MILLIS;
        float envelope = (float) Math.sin(through * Math.PI);
        float t = elapsed / 1000.0f;
        GlStateManager.translate(0.0f,
                (float) Math.sin(t * SPEED) * REACH * envelope, 0.0f);
    }

    public static void end() {
        GlStateManager.popMatrix();
    }
}
