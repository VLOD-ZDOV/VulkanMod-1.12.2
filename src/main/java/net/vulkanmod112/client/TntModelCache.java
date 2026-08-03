package net.vulkanmod112.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Draws the primed TNT block from a list built once instead of from scratch
 * every frame, for every charge.
 *
 * <h2>What the game does</h2>
 *
 * {@code RenderTNTPrimed} ends at {@code renderBlockBrightness}, and that is
 * not a display list — it looks the model up, walks its six face lists and its
 * general list, builds each into a {@code BufferBuilder} and draws it. Seven
 * real draw calls and a full tessellation of the same twelve quads, per
 * charge, per frame. Five hundred lit charges is three and a half thousand
 * draw calls in every frame, all of them describing one identical cube.
 *
 * <h2>Why a list per brightness</h2>
 *
 * The obvious saving is one list and a {@code glColor} for the brightness, and
 * it does not work: the quads carry their colour in the vertices, so the
 * current colour is ignored. Baking the brightness into the list is therefore
 * necessary — and cheap, because there are only sixteen brightnesses to bake.
 * {@code Entity.getBrightness()} reads {@code getLightBrightnessTable()[level]}
 * with a level of 0 to 15, so the set of values a charge can ask for is small,
 * fixed, and known to be exact rather than rounded.
 *
 * The flash asks for 1.0 and lands in the same cache beside them.
 *
 * <h2>What comes out</h2>
 *
 * The same vertices in the same order with the same colours: the list is
 * recorded from the game's own call. Nothing here decides what a charge looks
 * like, only how many times the same answer is worked out.
 */
public final class TntModelCache {

    /**
     * Brightnesses seen, and the list recorded for each. Sixteen at most in
     * practice; the cap is a guard against a modded light table rather than an
     * expectation.
     */
    private static final int CAPACITY = 32;
    private static final float[] KEYS = new float[CAPACITY];
    private static final int[] LISTS = new int[CAPACITY];
    private static int count;

    /** Off after any failure: one bad list is not worth losing the entity over. */
    private static boolean broken;

    private static long calls;
    private static long recorded;

    private TntModelCache() {
    }

    /**
     * Draws the state at that brightness, recording the list on first sight.
     *
     * @return false when nothing was drawn and the caller must do it itself
     */
    public static boolean draw(IBlockState state, float brightness) {
        if (broken || !VulkanConfig.isCacheBlockEntityModels()) {
            return false;
        }
        calls++;
        for (int i = 0; i < count; i++) {
            if (KEYS[i] == brightness) {
                GlStateManager.callList(LISTS[i]);
                return true;
            }
        }
        if (count >= CAPACITY) {
            return false;
        }
        try {
            int list = GlStateManager.glGenLists(1);
            if (list == 0) {
                broken = true;
                return false;
            }
            // COMPILE_AND_EXECUTE: the frame that pays for the recording also
            // gets its picture, so the first charge of a session does not blink.
            GlStateManager.glNewList(list, org.lwjgl.opengl.GL11.GL_COMPILE_AND_EXECUTE);
            Minecraft.getMinecraft().getBlockRendererDispatcher()
                    .renderBlockBrightness(state, brightness);
            GlStateManager.glEndList();
            KEYS[count] = brightness;
            LISTS[count] = list;
            count++;
            recorded++;
            return true;
        } catch (Throwable t) {
            broken = true;
            net.vulkanmod112.VulkanMod112.LOGGER.warn(
                    "Could not record the primed TNT model; it will be built per frame as before",
                    t);
            return false;
        }
    }

    /**
     * Drops every list, because the atlas moved under them.
     *
     * A recorded list holds texture coordinates, and stitching decides those
     * afresh on every resource reload. Keeping them would draw last pack's
     * pixels from this pack's atlas.
     */
    public static void forget() {
        for (int i = 0; i < count; i++) {
            try {
                GlStateManager.glDeleteLists(LISTS[i], 1);
            } catch (Throwable ignored) {
                // Leaking a display list on the way out of a resource reload is
                // not worth a crash report.
            }
        }
        count = 0;
    }

    /** Read and reset, for the diagnostics report. */
    public static String stats() {
        if (calls == 0) {
            return "tnt model cache: nothing asked for it since the last report";
        }
        String line = String.format(
                "tnt model cache: %d draws served, %d lists recorded, %d held%s",
                calls, recorded, count, broken ? " (off after a failure)" : "");
        calls = 0;
        recorded = 0;
        return line;
    }
}
