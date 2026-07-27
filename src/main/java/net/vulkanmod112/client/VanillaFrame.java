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

    /**
     * Why each visibility walk happened, counted.
     *
     * Written because the throttle that suppresses redundant walks showed no
     * effect at all in an A/B run, and "no effect" has two very different
     * causes: the walk was not the cost, or the throttle never fired. Exactly
     * this trap already cost a day once — a change that looked correct, was
     * correct, and was reached 0.3% of the time.
     */
    private static long walkAsked;
    private static long walkQueuePending;
    private static long walkCameraMoved;
    private static long walkSuppressed;

    /**
     * Both conditions are recorded on every call, not just the one that
     * happened to short-circuit first. The first version of this counter
     * returned at "queue empty" before ever looking at the camera, so it
     * reported the camera as never moving — a counter that cannot distinguish
     * "did not happen" from "was not looked at" is worse than none.
     */
    private static long walkDeferredPaid;
    private static long walkRan;
    /** Own frame count: {@link #stats()} runs first and resets the shared one. */
    private static long walkFramesSeen;

    public static void countWalkFrame() {
        walkFramesSeen++;
    }

    /**
     * How often the flood fill actually started, whatever triggered it.
     *
     * Without this the counters cannot tell "the walk runs every frame and the
     * throttle is failing to catch it" from "the walk is already rare and there
     * is nothing to catch" — and those call for opposite next moves.
     */
    public static void countWalkRan() {
        walkRan++;
    }

    /** A held-back walk being paid back; if this stays 0 the deferral never runs. */
    public static void countDeferredWalk() {
        walkDeferredPaid++;
    }

    public static void countWalk(boolean queuePending, boolean cameraMoved, boolean suppressed) {
        walkAsked++;
        if (queuePending) {
            walkQueuePending++;
        }
        if (cameraMoved) {
            walkCameraMoved++;
        }
        if (suppressed) {
            walkSuppressed++;
        }
    }

    /**
     * The replacement walk, measured in the only terms that can be compared
     * against vanilla's: how long one walk takes and how much of the grid it
     * touched to get there.
     *
     * The framerate on its own cannot answer whether this works, because the
     * walk does not run every frame and the frames it does not run are the fast
     * ones. A per-walk millisecond figure is comparable between the setting on
     * and off; an average frame time is not.
     */
    private static long ownWalks;
    private static long ownWalkNanos;
    private static long ownWalkVisited;
    private static long ownWalkVisible;
    /**
     * Walks handed back to vanilla. Any non-zero value here means the
     * replacement met a case it does not implement, and the per-walk timings
     * above then describe a mixture of the two.
     */
    private static long ownWalkFellBack;

    public static void countOwnWalk(int visited, int visible, long nanos) {
        ownWalks++;
        ownWalkNanos += nanos;
        ownWalkVisited += visited;
        ownWalkVisible += visible;
    }

    public static void countOwnWalkFallback() {
        ownWalkFellBack++;
    }

    /** Reads and resets, like the others, so a snapshot covers one interval. */
    public static String ownWalkStats() {
        if (ownWalks == 0 && ownWalkFellBack == 0) {
            return "own visibility walk: off";
        }
        String line = ownWalks == 0
                ? String.format("own visibility walk: never ran, fell back to vanilla %d times",
                        ownWalkFellBack)
                : String.format(
                        "own visibility walk: %d walks, %.2f ms each, %d visited → %d visible "
                                + "per walk, fell back to vanilla %d times",
                        ownWalks, ownWalkNanos / 1_000_000.0 / ownWalks,
                        ownWalkVisited / ownWalks, ownWalkVisible / ownWalks, ownWalkFellBack);
        ownWalks = 0L;
        ownWalkNanos = 0L;
        ownWalkVisited = 0L;
        ownWalkVisible = 0L;
        ownWalkFellBack = 0L;
        return line;
    }

    /**
     * Frustum tests asked for, counted so the cost of one can be worked out
     * from the walk timings rather than argued about. Everything that culls
     * against the camera lands here, entities included, but the visibility
     * search is far and away the loudest caller.
     */
    private static long frustumTests;

    public static void countFrustumTest() {
        frustumTests++;
    }

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
                "vanilla frame: renderEntities %.2f ms, renderBlockLayer (all 4) %.2f ms per frame "
                        + "over %d frames, %d frustum tests per frame (%s)",
                entityNanos / 1_000_000.0 / frames, layerNanos / 1_000_000.0 / frames, frames,
                frustumTests / frames,
                VulkanConfig.isFastFrustumTest() ? "far corner" : "vanilla eight corners");
        entityNanos = 0L;
        layerNanos = 0L;
        frames = 0L;
        frustumTests = 0L;
        return line;
    }

    /**
     * Where the visibility-walk decision went. {@code asked} counts only the
     * frames that reached our test at all — a dirty flag set elsewhere
     * short-circuits ahead of it, and the gap between {@code asked} and the
     * frame count is itself the answer to why a throttle did nothing.
     */
    public static String walkStats() {
        if (walkAsked == 0) {
            return "visibility walk: never reached our check — the dirty flag was already set";
        }
        String line = String.format(
                "visibility walk: RAN %d of %d frames — %d arm requests, churn %d, camera moved %d, "
                        + "deferred %d, paid back %d",
                walkRan, walkFramesSeen, walkAsked, walkQueuePending,
                walkCameraMoved, walkSuppressed, walkDeferredPaid);
        walkRan = 0L;
        walkFramesSeen = 0L;
        walkAsked = 0L;
        walkQueuePending = 0L;
        walkCameraMoved = 0L;
        walkSuppressed = 0L;
        walkDeferredPaid = 0L;
        return line;
    }
}
