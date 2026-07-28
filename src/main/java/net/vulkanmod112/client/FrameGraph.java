package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

import java.util.Arrays;

/**
 * A frame-time graph in the corner, in the shape DXVK draws one.
 *
 * <h2>Why an average framerate is the wrong number</h2>
 *
 * The number the game already shows is frames divided by seconds, and it
 * cannot tell a steady 120 from a 240 that stalls every tenth frame — both
 * average out the same, and only one of them is pleasant to play. What tells
 * them apart is the shape of the distribution, so this reports the worst
 * frames rather than the mean:
 *
 * <ul>
 * <li><b>1% low</b> — the frame time only 1% of frames exceed, quoted as a
 *     framerate. A stall shows up here immediately and barely moves the
 *     average at all.</li>
 * <li><b>min and max</b> — the best and worst single frame in the window.</li>
 * <li>the graph itself, one bar per frame, so a periodic hitch is visible as a
 *     pattern rather than inferred from a number.</li>
 * </ul>
 *
 * <h2>What it costs</h2>
 *
 * Nothing while it is off: recording is skipped entirely, so there is not even
 * a timestamp per frame.
 *
 * With it on, a frame pays one {@code nanoTime}, one array write, and one draw
 * call for the whole graph — every bar goes into a single buffer rather than
 * being drawn separately, because {@code drawRect} would otherwise submit two
 * hundred and forty of them. The statistics need the window sorted, which is
 * the only part that is more than trivial, so it is recomputed a few times a
 * second instead of every frame; text that changes faster than that cannot be
 * read anyway.
 *
 * It also times itself, and the diagnostics report prints what it cost. A
 * measurement tool that quietly distorts the thing it measures would be worse
 * than none, so the claim above is checkable rather than asserted.
 */
public final class FrameGraph {

    /** Frames kept. At 120 fps this is about two seconds of history. */
    private static final int SAMPLES = 240;
    /**
     * How often the numbers above the graph are recomputed.
     *
     * Deliberately slow. Figures that change every frame cannot be read at all
     * — the eye gets a blur of digits — and the graph underneath already shows
     * everything happening frame by frame. A second is long enough to read a
     * number and short enough to still feel live.
     */
    private static final long STATS_INTERVAL_NANOS = 1_000_000_000L;

    private static final int WIDTH = SAMPLES;
    private static final int HEIGHT = 40;
    private static final int MARGIN = 4;

    private static final int COLOUR_MIN = 0x55C355;
    private static final int COLOUR_MAX = 0xE0A040;
    private static final int COLOUR_BACKGROUND = 0xE0000000;
    private static final int COLOUR_TRACE = 0xFFFFFFFF;
    private static final int COLOUR_BASELINE = 0x60FFFFFF;

    private static final int[] frameMicros = new int[SAMPLES];
    private static int writeIndex;
    private static int filled;
    private static long lastFrameNanos;

    private static final int[] sorted = new int[SAMPLES];
    private static long lastStatsNanos;
    private static int statMin;
    private static int statMax;
    private static int statAverage;
    private static int statOnePercentLow;

    /**
     * Self-measurement, so the overlay's own cost is reported rather than
     * claimed — and the first thing it reported was that the claim was wrong.
     * "One draw call, so it costs nothing" measured at 0.2 to 0.5 ms a frame,
     * which is what the entire Vulkan terrain pass costs. Split in two here
     * because the fix depends on which half it is, and guessing that has cost
     * this project a day more than once.
     */
    private static long drawNanos;
    private static long barNanos;
    private static long textNanos;
    private static long drawFrames;

    private FrameGraph() {
    }

    /**
     * One frame has passed. Called from the render tick whether or not the
     * Vulkan renderer loaded, so the graph works on the OpenGL fallback too.
     */
    public static void record() {
        if (!VulkanConfig.isFrameGraph()) {
            // Drop the reference point as well: coming back from disabled must
            // not record one enormous frame covering the whole time it was off.
            lastFrameNanos = 0L;
            filled = 0;
            writeIndex = 0;
            return;
        }
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            long elapsed = now - lastFrameNanos;
            int micros = (int) Math.min(elapsed / 1000L, Integer.MAX_VALUE);
            frameMicros[writeIndex] = micros;
            writeIndex = (writeIndex + 1) % SAMPLES;
            if (filled < SAMPLES) {
                filled++;
            }
        }
        lastFrameNanos = now;
    }

    public static void draw(ScaledResolution resolution) {
        if (!VulkanConfig.isFrameGraph() || filled < 2) {
            return;
        }
        long started = System.nanoTime();
        Minecraft mc = Minecraft.getMinecraft();

        refreshStats(started);

        int left = MARGIN;
        int bottom = resolution.getScaledHeight() - MARGIN;
        int top = bottom - HEIGHT;

        // Drawn as deviation from the middle rather than as bars standing on
        // the floor. A frame quicker than the window's average goes down, a
        // slower one goes up, and a perfectly even scene is a flat line — which
        // is the thing worth seeing at a glance. Bars from the floor spend most
        // of their height saying "the framerate is roughly what the number
        // above already said", and the interesting part is the wobble on top.
        int centre = top + HEIGHT / 2;
        int baseline = Math.max(statAverage, 1);
        int spread = Math.max(Math.max(statMax - baseline, baseline - statMin), 500);

        long barsStarted = System.nanoTime();
        Gui.drawRect(left - 1, top - 1, left + WIDTH + 1, bottom + 1, COLOUR_BACKGROUND);

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(7, DefaultVertexFormats.POSITION_COLOR);
        quad(buffer, left, centre, left + WIDTH, centre + 1, COLOUR_BASELINE);
        for (int i = 0; i < filled; i++) {
            int micros = frameMicros[(writeIndex - filled + i + SAMPLES * 2) % SAMPLES];
            int offset = (micros - baseline) * (HEIGHT / 2) / spread;
            offset = Math.max(-HEIGHT / 2, Math.min(HEIGHT / 2, offset));
            int x = left + i * WIDTH / SAMPLES;
            if (offset >= 0) {
                quad(buffer, x, centre - offset, x + 1, centre + 1, COLOUR_TRACE);
            } else {
                quad(buffer, x, centre, x + 1, centre - offset + 1, COLOUR_TRACE);
            }
        }
        tessellator.draw();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        barNanos += System.nanoTime() - barsStarted;

        long textStarted = System.nanoTime();

        // Laid out the way DXVK lays its frame-time readout out: the best and
        // the worst frame of the window named and coloured, sitting on the two
        // ends of the graph they describe, rather than run together into one
        // line of grey text where neither stands out.
        String min = String.format("min: %.1f ms", statMin / 1000.0);
        String max = String.format("max: %.1f ms", statMax / 1000.0);
        mc.fontRenderer.drawStringWithShadow(min, left, top - 10, COLOUR_MIN);
        mc.fontRenderer.drawStringWithShadow(max,
                left + WIDTH - mc.fontRenderer.getStringWidth(max), top - 10, COLOUR_MAX);

        // The average is what every framerate counter already shows. The 1% low
        // is the one that separates a steady 120 from a 240 that stalls, so it
        // is kept beside it rather than left to the log.
        String rate = String.format("%d fps", statAverage == 0 ? 0 : 1_000_000 / statAverage);
        String low = String.format("1%% low: %d fps",
                statOnePercentLow == 0 ? 0 : 1_000_000 / statOnePercentLow);
        mc.fontRenderer.drawStringWithShadow(rate, left, top - 21, 0xFFFFFF);
        mc.fontRenderer.drawStringWithShadow(low,
                left + WIDTH - mc.fontRenderer.getStringWidth(low), top - 21, 0xB0B0B0);

        textNanos += System.nanoTime() - textStarted;
        drawNanos += System.nanoTime() - started;
        drawFrames++;
    }

    private static void refreshStats(long now) {
        if (now - lastStatsNanos < STATS_INTERVAL_NANOS && statMax != 0) {
            return;
        }
        lastStatsNanos = now;
        int n = filled;
        // Copied out oldest-first into a scratch array rather than sorting the
        // ring in place: sorting the ring would destroy the order the graph is
        // drawn in, and sorting the whole array while it is still filling would
        // let the untouched zeros become the minimum.
        for (int i = 0; i < n; i++) {
            sorted[i] = frameMicros[(writeIndex - n + i + SAMPLES * 2) % SAMPLES];
        }
        Arrays.sort(sorted, 0, n);
        statMin = sorted[0];
        statMax = sorted[n - 1];
        long total = 0L;
        for (int i = 0; i < n; i++) {
            total += sorted[i];
        }
        statAverage = (int) (total / n);
        // The frame time 99% of frames come in under. Everything above it is
        // the worst one per cent, which is where a stall lives and where an
        // average hides it.
        int index = Math.max(0, Math.min(n - 1, (int) Math.ceil(n * 0.99) - 1));
        statOnePercentLow = sorted[index];
    }

    private static void quad(BufferBuilder buffer, int x0, int y0, int x1, int y1, int argb) {
        float a = (argb >>> 24) / 255.0f;
        float r = (argb >> 16 & 255) / 255.0f;
        float g = (argb >> 8 & 255) / 255.0f;
        float b = (argb & 255) / 255.0f;
        buffer.pos(x0, y1, 0.0D).color(r, g, b, a).endVertex();
        buffer.pos(x1, y1, 0.0D).color(r, g, b, a).endVertex();
        buffer.pos(x1, y0, 0.0D).color(r, g, b, a).endVertex();
        buffer.pos(x0, y0, 0.0D).color(r, g, b, a).endVertex();
    }

    /** Reads and resets, like the other counters, so a snapshot covers one interval. */
    public static String stats() {
        if (drawFrames == 0) {
            return "frame graph: off";
        }
        String line = String.format(
                "frame graph: %.3f ms per frame (bars %.3f, text %.3f) over %d frames",
                drawNanos / 1_000_000.0 / drawFrames, barNanos / 1_000_000.0 / drawFrames,
                textNanos / 1_000_000.0 / drawFrames, drawFrames);
        drawNanos = 0L;
        barNanos = 0L;
        textNanos = 0L;
        drawFrames = 0L;
        return line;
    }
}
