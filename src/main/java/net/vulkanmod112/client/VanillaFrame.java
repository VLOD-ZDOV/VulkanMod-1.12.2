package net.vulkanmod112.client;

/**
 * Stopwatches on the two vanilla methods that walk the visible-chunk list.
 *
 * Standing still at render distance 64 over an ocean, with the world built and
 * uploads stopped, the frame takes 45 ms and everything this mod can account
 * for adds up to under two of them. Reading the 1.12.2 source narrowed the
 * suspects to work that is upstream of where our mixins interpose, and so is
 * invisible to every timer we had:
 *
 * - {@code renderEntities} runs twice a frame (Forge renders pass 0 and pass 1)
 *   and each call scans the visible-chunk list twice, once for entities and
 *   once for tile entities, doing a chunk lookup per element. At 17 778 chunks
 *   that is about 71 000 lookups a frame for an ocean that contains nearly
 *   nothing.
 * - {@code renderBlockLayer} filters the visible-chunk list into a draw list
 *   before handing it to the container we hook. That loop runs for all four
 *   layers, including the three we draw ourselves, so taking a layer over into
 *   Vulkan never removed it.
 *
 * Those are structural readings of the code, not measurements — the point of
 * this class is to stop guessing. Three hypotheses in two days died to a
 * counter that took twenty lines.
 */
public final class VanillaFrame {

    private static long entityStart;
    private static long entityNanos;
    private static long layerStart;
    private static long layerNanos;
    private static long frames;

    private VanillaFrame() {
    }

    public static void beginEntities() {
        entityStart = System.nanoTime();
    }

    public static void endEntities() {
        if (entityStart != 0L) {
            entityNanos += System.nanoTime() - entityStart;
            entityStart = 0L;
        }
    }

    /** {@code firstLayer} marks the frame boundary: SOLID is drawn once per frame. */
    public static void beginLayer(boolean firstLayer) {
        if (firstLayer) {
            frames++;
        }
        layerStart = System.nanoTime();
    }

    public static void endLayer() {
        if (layerStart != 0L) {
            layerNanos += System.nanoTime() - layerStart;
            layerStart = 0L;
        }
    }

    /** Reads and resets, so each snapshot covers only the interval since the last. */
    public static String stats() {
        if (frames == 0) {
            return "vanilla frame: not rendered";
        }
        String line = String.format(
                "vanilla frame: renderEntities %.2f ms, renderBlockLayer (all 4) %.2f ms per frame over %d frames",
                entityNanos / 1_000_000.0 / frames, layerNanos / 1_000_000.0 / frames, frames);
        entityNanos = 0L;
        layerNanos = 0L;
        frames = 0L;
        return line;
    }
}
