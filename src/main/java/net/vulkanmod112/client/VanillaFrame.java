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
    /** Walks a chunk asked for and did not get this frame. */
    private static long ownWalkHeld;

    public static void countOwnWalk(int visited, int visible, long nanos) {
        ownWalks++;
        ownWalkNanos += nanos;
        ownWalkVisited += visited;
        ownWalkVisible += visible;
    }

    /** Slots checked against the position their chunk actually holds. */
    private static long derivationsChecked;
    private static long derivationsWrong;
    private static String derivationFirstWrong;

    /**
     * Confirms the one claim that could fail silently: that stepping 16 blocks
     * and one grid slot lands on the chunk vanilla's lookup by world position
     * would have returned. Only called when the verification switch is on,
     * because the dereference it needs is exactly the one being avoided.
     */
    public static void countWalkDerivation(net.minecraft.client.renderer.chunk.RenderChunk chunk,
                                           int x, int y, int z) {
        derivationsChecked++;
        net.minecraft.util.math.BlockPos held = chunk == null ? null : chunk.getPosition();
        if (held != null && held.getX() == x && held.getY() == y && held.getZ() == z) {
            return;
        }
        derivationsWrong++;
        if (derivationFirstWrong == null) {
            derivationFirstWrong = String.format("derived %d,%d,%d but slot holds %s", x, y, z, held);
        }
    }

    public static void countOwnWalkFallback() {
        ownWalkFellBack++;
    }

    public static void countOwnWalkHeld() {
        ownWalkHeld++;
    }

    /**
     * The rebuild pass at the end of {@code setupTerrain}, split three ways.
     *
     * The game's profiler calls all of it one section and puts it at 19.1% of
     * the frame, which is not enough to act on: a scan of every visible chunk
     * and a synchronous chunk build live in there together, and they want
     * opposite fixes. So the whole section, the filter this mod puts in front of
     * it, and the builds are timed separately. {@code kept} against
     * {@code scanned} is the number the filter exists to move — everything not
     * kept is a chunk vanilla would have dereferenced to learn nothing.
     */
    private static long rebuildFrames;
    private static long rebuildNanos;
    private static long rebuildScanned;
    private static long filterRuns;
    private static long filterNanos;
    private static long filterKept;
    private static long filterPending;
    private static long buildNearCount;
    private static long buildNearNanos;

    public static void countRebuildNear(long nanos) {
        rebuildFrames++;
        rebuildNanos += nanos;
    }

    /**
     * The size of the list the pass is about to walk, and of the rebuild queue
     * it will be asked about. Recorded whether or not the filter is on, because
     * these two are the pass's input and the only honest way to compare two
     * flights over different ground is to divide by them.
     */
    public static void countRebuildScan(int listSize, int pending) {
        rebuildScanned += listSize;
        filterPending += pending;
    }

    /**
     * How far out of near-to-far order the visible list actually is.
     *
     * B2 on the roadmap proposed sorting each layer front to back so the depth
     * buffer fills early. The premise was that the list might be unsorted — but
     * both searches, vanilla's and this mod's, are breadth-first from the
     * camera's own chunk through a FIFO queue, so the list comes out in
     * non-decreasing graph distance by construction. Graph distance is not
     * Euclidean distance, though: a chunk reached the long way round a wall sits
     * later in the list than its distance deserves. This counts how often that
     * happens, so the item can be closed on a number rather than on the
     * argument above.
     *
     * Opt-in, because it costs the dereference per chunk that the rest of this
     * work exists to avoid.
     */
    private static long orderPairs;
    private static long orderInversions;

    public static void countListOrder(int pairs, int inversions) {
        orderPairs += pairs;
        orderInversions += inversions;
    }

    public static void countRebuildFilter(int kept, long nanos) {
        filterRuns++;
        filterNanos += nanos;
        filterKept += kept;
    }

    public static void countBuildNear(long nanos) {
        buildNearCount++;
        buildNearNanos += nanos;
    }

    /** Reads and resets, like the others, so a snapshot covers one interval. */
    public static String rebuildNearStats() {
        if (rebuildFrames == 0) {
            return "rebuild near: no frames";
        }
        StringBuilder line = new StringBuilder(200);
        long perFrame = rebuildScanned / rebuildFrames;
        // Nanoseconds per chunk on the list is the figure that survives a
        // different route: two flights never cover the same ground, but they can
        // be divided by how much was on screen.
        line.append(String.format("rebuild near: %.3f ms per frame over %d frames, "
                        + "%d on the list, %d queued, %.1f ns each",
                rebuildNanos / 1_000_000.0 / rebuildFrames, rebuildFrames, perFrame,
                filterPending / rebuildFrames,
                perFrame == 0 ? 0.0 : (double) rebuildNanos / rebuildFrames / perFrame));
        if (buildNearCount > 0) {
            line.append(String.format(" | %.3f ms of it building %d chunks on the spot",
                    buildNearNanos / 1_000_000.0 / rebuildFrames, buildNearCount));
        }
        if (filterRuns > 0) {
            line.append(String.format(" | filter %.3f ms → %d kept",
                    filterNanos / 1_000_000.0 / filterRuns, filterKept / filterRuns));
        } else {
            line.append(" | filter off");
        }
        if (orderPairs > 0) {
            line.append(String.format(" | order: %d of %d pairs go backwards (%.1f%%)",
                    orderInversions, orderPairs, 100.0 * orderInversions / orderPairs));
            orderPairs = 0L;
            orderInversions = 0L;
        }
        rebuildFrames = 0L;
        rebuildNanos = 0L;
        rebuildScanned = 0L;
        filterRuns = 0L;
        filterNanos = 0L;
        filterKept = 0L;
        filterPending = 0L;
        buildNearCount = 0L;
        buildNearNanos = 0L;
        return line.toString();
    }

    /**
     * How long the lists handed to the two passes inside {@code renderEntities}
     * were, against how long the visible list they replace is.
     *
     * The pair is the whole point of the change and belongs next to it: the
     * frame time alone cannot say whether the short list is short because the
     * work went away or because the answer did.
     */
    private static long entitySectionRuns;
    private static long entitySectionShort;
    private static long entitySectionFull;
    private static long tileSectionRuns;
    private static long tileSectionShort;
    private static long tileSectionFull;

    private static long sectionChecks;
    private static long sectionsExpected;
    private static long sectionsMissing;
    private static long tileChecks;
    private static long tileExpected;
    private static long tileMissing;

    private static long layerSectionRuns;
    private static long layerSectionShort;
    private static long layerSectionFull;
    private static long layerChecks;
    private static long layerExpected;
    private static long layerMissing;

    public static void countLayerSections(int shortened, int full) {
        layerSectionRuns++;
        layerSectionShort += shortened;
        layerSectionFull += full;
    }

    public static void countLayerSectionCheck(int expected, int missing) {
        layerChecks++;
        layerExpected += expected;
        layerMissing += missing;
    }

    /**
     * The list the game's own layer filter walks, against the visible list it
     * would have walked. Reported apart from the entity pass because the two
     * fail differently: a short entity list loses a creature, a short layer
     * list loses a chunk.
     */
    public static String layerSectionStats() {
        if (layerSectionRuns == 0) {
            return "layer filter list: full — nothing shortened";
        }
        String line = String.format("layer filter list: %d of %d sections over %d passes",
                layerSectionShort / layerSectionRuns, layerSectionFull / layerSectionRuns,
                layerSectionRuns);
        if (layerChecks > 0) {
            line += String.format(" | checked against the full scan: %d expected, %d missing",
                    layerExpected / layerChecks, layerMissing);
        }
        layerSectionRuns = 0L;
        layerSectionShort = 0L;
        layerSectionFull = 0L;
        layerChecks = 0L;
        layerExpected = 0L;
        layerMissing = 0L;
        return line;
    }

    public static void countEntitySections(int shortened, int full) {
        entitySectionRuns++;
        entitySectionShort += shortened;
        entitySectionFull += full;
    }

    public static void countTileEntitySections(int shortened, int full) {
        tileSectionRuns++;
        tileSectionShort += shortened;
        tileSectionFull += full;
    }

    public static void countEntitySectionCheck(int expected, int handed, int missing) {
        sectionChecks++;
        sectionsExpected += expected;
        sectionsMissing += missing;
    }

    public static void countTileEntitySectionCheck(int expected, int handed, int missing) {
        tileChecks++;
        tileExpected += expected;
        tileMissing += missing;
    }

    /**
     * Reads and resets. Both passes run once per render pass, so the divisor is
     * the number of times the shortening ran, not the number of frames.
     */
    public static String entitySectionStats() {
        if (entitySectionRuns == 0 && tileSectionRuns == 0) {
            return "entity pass lists: full — nothing shortened";
        }
        StringBuilder line = new StringBuilder("entity pass lists: ");
        if (entitySectionRuns == 0) {
            line.append("creatures full");
        } else {
            line.append(String.format("creatures %d of %d sections",
                    entitySectionShort / entitySectionRuns, entitySectionFull / entitySectionRuns));
        }
        if (tileSectionRuns == 0) {
            line.append(", block entities full");
        } else {
            line.append(String.format(", block entities %d of %d sections",
                    tileSectionShort / tileSectionRuns, tileSectionFull / tileSectionRuns));
        }
        if (sectionChecks > 0 || tileChecks > 0) {
            line.append(String.format(" | checked against the full scan: creatures %d expected, "
                            + "%d missing; block entities %d expected, %d missing",
                    sectionChecks == 0 ? 0 : sectionsExpected / sectionChecks, sectionsMissing,
                    tileChecks == 0 ? 0 : tileExpected / tileChecks, tileMissing));
        }
        entitySectionRuns = 0L;
        entitySectionShort = 0L;
        entitySectionFull = 0L;
        tileSectionRuns = 0L;
        tileSectionShort = 0L;
        tileSectionFull = 0L;
        sectionChecks = 0L;
        sectionsExpected = 0L;
        sectionsMissing = 0L;
        tileChecks = 0L;
        tileExpected = 0L;
        tileMissing = 0L;
        return line.toString();
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
        if (derivationsChecked > 0) {
            line += String.format(" | derivation checked %d, wrong %d%s",
                    derivationsChecked, derivationsWrong,
                    derivationFirstWrong == null ? "" : " (" + derivationFirstWrong + ")");
            derivationsChecked = 0L;
            derivationsWrong = 0L;
        }
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
    /**
     * The one method in the frame that costs nothing standing still and a great
     * deal moving, and which nothing here had ever timed.
     *
     * It decides which chunks are on screen, and it does that work again every
     * time the camera moves far enough. Standing still this renderer is within
     * a tenth of the fastest thing on this version; flying at a long render
     * distance it is not, and the difference had to be somewhere nobody was
     * looking. This is the only candidate left that behaves the same way.
     */
    private static long setupStart;
    private static long setupNanos;
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

    /**
     * The block-entity half of {@code renderEntities}, split out of the whole.
     *
     * Both are inside the entity timer, and 6.2% against 16% is not something to
     * read off one number. This runs twice a frame, because Forge renders pass 0
     * and pass 1, so the total is what a frame spends on it rather than what one
     * call costs.
     */
    private static long blockEntityStart;
    private static long blockEntityNanos;

    public static void beginBlockEntities() {
        blockEntityStart = System.nanoTime();
    }

    public static void endBlockEntities() {
        if (blockEntityStart != 0L) {
            blockEntityNanos += System.nanoTime() - blockEntityStart;
            blockEntityStart = 0L;
        }
    }

    /** {@code firstLayer} marks the frame boundary: SOLID is drawn once per frame. */
    public static void beginLayer(boolean firstLayer) {
        if (firstLayer) {
            frames++;
        }
        layerStart = System.nanoTime();
    }

    public static void beginSetupTerrain() {
        setupStart = System.nanoTime();
    }

    public static void endSetupTerrain() {
        if (setupStart != 0L) {
            setupNanos += System.nanoTime() - setupStart;
            setupStart = 0L;
        }
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
                "vanilla frame: renderEntities %.2f ms (block entities %.2f of it), "
                        + "setupTerrain %.2f ms, "
                        + "renderBlockLayer (all 4) %.2f ms per frame (of it, this mod %.2f) "
                        + "over %d frames, %d frustum tests per frame (%s)",
                entityNanos / 1_000_000.0 / frames, blockEntityNanos / 1_000_000.0 / frames,
                setupNanos / 1_000_000.0 / frames,
                layerNanos / 1_000_000.0 / frames,
                net.vulkanmod112.client.TerrainHooks.ourLayerMillis() / frames, frames,
                frustumTests / frames,
                VulkanConfig.isFastFrustumTest() ? "far corner" : "vanilla eight corners");
        blockEntityNanos = 0L;
        entityNanos = 0L;
        layerNanos = 0L;
        setupNanos = 0L;
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
                // "deferred" and "paid back" are gone with the throttle that
                // produced them. Two counters that could only ever print zero
                // are two numbers that read as measurements and are not.
                "visibility walk: RAN %d of %d frames — %d arm requests, churn %d, camera moved %d",
                walkRan, walkFramesSeen, walkAsked, walkQueuePending, walkCameraMoved);
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
