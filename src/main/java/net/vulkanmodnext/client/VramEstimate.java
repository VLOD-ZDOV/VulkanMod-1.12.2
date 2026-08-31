package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.vulkanmodnext.VulkanBridge;
import net.vulkanmodnext.VulkanLoader;

/**
 * How much video memory the settings as they stand are asking for.
 *
 * Two halves, and they are not equally trustworthy, which is the whole reason
 * this class is written the way it is.
 *
 * <p>The <b>screen-sized part</b> is arithmetic and exact. A colour target at
 * this resolution is width times height times four bytes, and there is no
 * judgement in it. Every effect that needs a target of its own adds another
 * one, which is why turning on three effects at four megapixels is not the free
 * change it looks like on a switch.
 *
 * <p>The <b>world part</b> cannot be calculated at all, and pretending
 * otherwise is how an estimate becomes a lie. What a world costs depends on
 * what is in it: a plains biome and a cave system at the same render distance
 * are not within a factor of two of each other. So it is not calculated — it is
 * <i>measured</i>, from the geometry this player's world is holding right now,
 * and only the effect of <i>changing</i> a setting is worked out from there. A
 * render distance twice as far covers four times the ground, so the same world
 * costs about four times as much; that ratio is honest even when the absolute
 * number could never be.
 *
 * <p>With no world loaded there is nothing to measure, and the world part is
 * reported as unknown rather than guessed.
 */
public final class VramEstimate {

    /** Bytes per pixel of every full-screen target this renderer keeps. */
    private static final int BYTES_PER_PIXEL = 4;

    private VramEstimate() {
    }

    /** One line per component, for the tooltip. */
    public static final class Breakdown {
        public int targetsMiB;
        public int effectsMiB;
        public int geometryMiB;
        public int fixedMiB;
        /** True when a world is loaded and the geometry figure is a measurement. */
        public boolean geometryKnown;

        public int totalMiB() {
            return targetsMiB + effectsMiB + geometryMiB + fixedMiB;
        }
    }

    public static Breakdown current() {
        Minecraft mc = Minecraft.getMinecraft();
        Breakdown out = new Breakdown();
        long pixels = (long) Math.max(1, mc.displayWidth) * Math.max(1, mc.displayHeight);

        // The three shared with OpenGL: colour, depth, and the transparent
        // layer's own colour. Counted once each — the OpenGL textures are the
        // same memory seen through the other API, not a second copy.
        long targets = pixels * BYTES_PER_PIXEL * 3;
        out.targetsMiB = mib(targets);

        long effects = 0;
        if (VulkanConfig.getAmbientOcclusion() > 0) {
            effects += pixels * BYTES_PER_PIXEL;
        }
        if (VulkanConfig.getScreenReflections() > 0) {
            effects += pixels * BYTES_PER_PIXEL;
            // Reflections need to know where things moved since last frame.
            effects += pixels * BYTES_PER_PIXEL;
        }
        if (VulkanConfig.getBloom() > 0) {
            // Half of the width and half of the height is a quarter of the
            // pixels, and there are two of them to blur back and forth between.
            effects += pixels * BYTES_PER_PIXEL / 2;
        }
        out.effectsMiB = mib(effects);

        // Everything on the card that is not this renderer's own targets or
        // geometry: the game's block atlas and every other texture it has
        // loaded, its own framebuffer, and what the driver keeps for holding an
        // OpenGL and a Vulkan context open in one process at once.
        //
        // A measured figure, not a derived one: what the driver reports for the
        // whole process, minus everything above that this class can account
        // for. It will not be exactly this anywhere else, and a wrong constant
        // is a better answer than leaving out a third of the total, which is
        // what the first version of this did. The measurement is in the
        // roadmap, with how to repeat it.
        out.fixedMiB = 300;

        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        int measured = bridge == null ? 0 : bridge.geometryMegabytes();
        if (measured <= 0) {
            out.geometryKnown = false;
            return out;
        }
        out.geometryKnown = true;

        // Taken at the distance that is set right now, so here it is simply the
        // measurement. Predicting another distance is a separate question with
        // its own method.
        long geometry = measured;

        // Without the drop, the game holds the same world in its own buffers,
        // on the same card — not a rounding error but the largest single line
        // in the whole estimate.
        if (!VulkanConfig.isDropVanillaBuffers()) {
            geometry *= 2;
        }
        out.geometryMiB = (int) geometry;
        return out;
    }

    /**
     * What the same world would cost at another render distance.
     *
     * Kept apart from {@link #current()} because it answers a different
     * question — not "what is this using" but "what would this cost me" — and
     * the settings screen wants both while the slider is being dragged.
     */
    public static int geometryAtDistance(int distance) {
        Minecraft mc = Minecraft.getMinecraft();
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        int measured = bridge == null ? 0 : bridge.geometryMegabytes();
        if (measured <= 0) {
            return 0;
        }
        int now = Math.max(1, mc.gameSettings.renderDistanceChunks);
        double ratio = (double) distance * distance / ((double) now * now);
        int scaled = (int) (measured * ratio);
        return VulkanConfig.isDropVanillaBuffers() ? scaled : scaled * 2;
    }

    /** Device-local memory the card has, or 0 before Vulkan picked one. */
    public static int availableMiB() {
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        return bridge == null ? 0 : bridge.vramMegabytes();
    }

    private static int mib(long bytes) {
        return (int) (bytes / (1024L * 1024L));
    }
}
