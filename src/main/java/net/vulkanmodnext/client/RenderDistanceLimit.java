package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;

/**
 * How far the render-distance slider is allowed to go.
 *
 * Vanilla stops at 16, or 32 on a 64-bit JVM with a large enough heap. This mod
 * has raised that to 64 since 0.2.0, and {@link #EXTREME} raises it again to
 * 128 for anyone who wants to find out what their machine does with it.
 *
 * <h2>What the extra distance actually costs</h2>
 *
 * Not "more chunks drawn" — more chunks <em>allocated</em>. {@code ViewFrustum}
 * builds a {@code (2d+1) x (2d+1) x 16} grid of {@code RenderChunk} objects when
 * the world loads and keeps every one of them alive, each with four OpenGL
 * buffer names on it, whether or not there is terrain in that cell:
 *
 * <pre>
 *   32  ->    65  x  65 x 16 =     67 600 chunks
 *   64  ->   129 x 129 x 16 =    266 256 chunks
 *  128  ->   257 x 257 x 16 =  1 056 784 chunks
 * </pre>
 *
 * So the step from 64 to 128 is four times the objects and four times the
 * memory before a single block is drawn. Whether the world out there arrives at
 * all is a separate question: a server caps its own view distance, and vanilla's
 * integrated one is asked for exactly this number, so in single player the grid
 * and the chunk loading grow together.
 *
 * <h2>Why the cap moves rather than disappearing</h2>
 *
 * Lowering the limit has to pull the current value down with it, or turning the
 * switch back off would leave the game running at a distance its own slider can
 * no longer express — vanilla's video settings would show the bar pinned at the
 * far end and moving it would jump the value, which reads as a bug rather than
 * as a setting.
 */
public final class RenderDistanceLimit {

    /** What this mod has allowed since 0.2.0. */
    public static final int NORMAL = 64;
    /** What the switch unlocks. */
    public static final int EXTREME = 128;

    private RenderDistanceLimit() {
    }

    public static int max() {
        return VulkanConfig.isExtremeRenderDistance() ? EXTREME : NORMAL;
    }

    /**
     * Applies the current limit to the game's own slider and pulls the render
     * distance back inside it. Safe to call before a world exists.
     */
    public static void apply() {
        int max = max();
        GameSettings.Options.RENDER_DISTANCE.setValueMax(max);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        if (mc.gameSettings.renderDistanceChunks > max) {
            mc.gameSettings.renderDistanceChunks = max;
            mc.gameSettings.saveOptions();
        }
    }
}
