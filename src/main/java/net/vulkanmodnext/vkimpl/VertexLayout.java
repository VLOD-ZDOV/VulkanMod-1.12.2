package net.vulkanmodnext.vkimpl;

import org.lwjgl.system.MemoryUtil;

/**
 * How a chunk's vertices are laid out in video memory, and the packing that
 * gets them there.
 *
 * <h2>Why pack at all</h2>
 *
 * The terrain pass was measured against render distance and against window
 * size, and the two answers together name what it is waiting for: **0.048 ms
 * per million vertices, and flat in pixels** — 0.44 ms at 0.9 megapixels
 * against 0.50 at 7.6. Nine million vertices at twenty-eight bytes is 252 MB
 * of vertex fetch a frame, which at the measured 0.43 ms is about 580 GB/s.
 * That is the card's memory bandwidth. The pass is not filling pixels or
 * running out of shader; it is reading vertices.
 *
 * <h2>The layout</h2>
 *
 * Vanilla's is twenty-eight bytes and this mod mirrors it byte for byte, which
 * is the reason the mod never has to understand geometry it did not build. The
 * packed one is sixteen:
 *
 * <pre>
 *   0  position x, y, z   3 x int16   1/2048 of a block, from the section's middle
 *   6  both light values  1 x int16   one byte each, side by side
 *   8  colour             4 x uint8   unchanged
 *  12  texture u, v       2 x uint16  normalised, and vanilla's are already 0..1
 * </pre>
 *
 * <h2>What each field gives up, and what it does not</h2>
 *
 * <b>Position</b> keeps 1/2048 of a block. Vanilla's own models are built on a
 * sixteenth of a block and its textures are sixteen pixels across one, so this
 * is a hundred and twenty-eight times finer than a texel. It is measured from
 * the middle of the section rather than its corner, which puts the sixteen
 * blocks of headroom evenly on both sides — a model that hangs out of its own
 * section has room in either direction. The scale is a power of two and the
 * sections sit on a sixteen-block grid, so a face shared by two chunks lands on
 * exactly the same lattice point from both sides: no cracks, no z-fighting.
 * The exact span is −8 to +24 blocks with the top end open — a signed short
 * reaches one further down than up — and a vanilla model may hang one block out
 * of its cube, not eight, so nothing goes near either end. Both statements are
 * asserted in {@code VertexPackingTest} rather than left here to be believed.
 *
 * <b>Light</b> is exact. The game writes the two lightmap coordinates as shorts
 * but never above 240 — they are a light level times sixteen, and smooth
 * lighting averages them, which cannot leave the range — so a byte holds every
 * value they can take. Anything that arrives above a byte is counted and
 * clamped rather than silently wrapped.
 *
 * <b>Texture coordinates</b> are the one field that can actually lose
 * something. Sixteen bits normalised is one part in 65 535 of the whole atlas,
 * which on a 4096-pixel atlas is a sixteenth of a texel and on a 16 384-pixel
 * one is a quarter of it. A quarter of a texel at a sprite's edge is enough to
 * fetch the neighbouring sprite once mipmaps are involved. So the atlas is
 * checked, and a large one keeps the wide layout.
 *
 * <b>Colour</b> is untouched: it carries the ambient occlusion as well as the
 * biome tint, and anything narrower than a byte a channel bands visibly on a
 * smoothly lit wall.
 *
 * <h2>Why not smaller than sixteen</h2>
 *
 * Ten bits an axis would make the position four bytes and the vertex twelve,
 * but ten bits over the same span is 1/43 of a block — coarser than the
 * sixteenth vanilla models are built on, which is where two quads that should
 * share an edge stop sharing it. Everything else is already at its floor: the
 * colour bands if narrowed, the light is exactly a byte, and the texture
 * coordinates are the field that is already the tightest of the four. Sixteen
 * is where the next byte costs picture rather than bandwidth.
 */
public final class VertexLayout {

    /**
     * Which way each quad faces, tallied while the geometry is copied.
     *
     * An instrument, not a feature, and it exists to price one specific idea
     * before anybody builds it. Half the faces of a world made of boxes point
     * away from wherever you stand, and the card throws those away — but only
     * after fetching and transforming every one of their vertices, because
     * back-face culling happens after the vertex shader. Grouping a chunk's
     * quads by facing at copy time and skipping the groups that point away
     * would drop them before the fetch, and the terrain pass is bound by
     * exactly that fetch: measured, it is 0.32 ms of fixed cost against 0.0095
     * ms per megapixel, so nine times the pixels cost it a fifth.
     *
     * What the theory cannot say is how the world actually divides, because a
     * Minecraft world is not a uniform box: the ground is one enormous
     * upward-facing sheet, and plants are crossed quads that face nothing and
     * can never be grouped. Hence six buckets and a seventh for those.
     *
     * Off unless {@code -Dvulkanmodnext.countFacings=true}: it is a cross
     * product per quad on the chunk builder threads, and those are quiet but
     * not free.
     */
    private static final boolean COUNT_FACINGS = Boolean.getBoolean("vulkanmodnext.countFacings");

    /** -X, +X, -Y, +Y, -Z, +Z, and quads that lie on no axis. */
    private static final java.util.concurrent.atomic.AtomicLongArray FACINGS =
            new java.util.concurrent.atomic.AtomicLongArray(7);

    /** Vanilla's BLOCK vertex: pos 3f | colour 4ub | uv 2f | lightmap 2s. */
    public static final int SOURCE_STRIDE = 28;

    /** The packed one. */
    public static final int COMPACT_STRIDE = 16;

    /** Units of packed position per block, and the block they are counted from. */
    static final float POSITION_SCALE = 2048.0f;
    static final float POSITION_ORIGIN = 8.0f;

    /**
     * Beyond this many pixels across, a sixteen-bit texture coordinate is worth
     * less than a quarter of a texel and sprite edges start to bleed.
     */
    static final int LARGEST_SAFE_ATLAS = 8192;

    /**
     * Settled when this class is loaded, and never afterwards.
     *
     * It has to be, and the first attempt at it is why. Deciding inside the
     * renderer's own start-up looked early enough and was not: the sky and the
     * first chunks are mirrored several seconds before that runs, so they went
     * into the buffer at twenty-eight bytes and everything after them at
     * sixteen. One buffer, two layouts, and a world drawn as spikes reaching to
     * the horizon. A field read from a JVM flag has no such moment — it is true
     * before the first line of the mod runs.
     */
    private static final boolean COMPACT = Boolean.getBoolean("vulkanmodnext.compactVertices")
            && !atlasTooLargeLastTime();

    /**
     * Whether the atlas the last session saw was too big for sixteen-bit
     * texture coordinates.
     *
     * The size of the sheet is not known when this decision has to be made —
     * the pack has not loaded — and the decision cannot be moved later, because
     * the sky and the first chunks are already mirrored by then. So the answer
     * comes from the session before: whatever atlas was seen last time is
     * written into the settings, and read back here before anything else runs.
     *
     * A pack that has just been installed therefore gets one session on the old
     * answer. That is the whole cost of it, and it is the right way round: the
     * first session on a huge atlas may have slightly soft sprite edges, and
     * every session after it is correct without anybody reading a log.
     */
    private static boolean atlasTooLargeLastTime() {
        return Integer.getInteger("vulkanmodnext.atlasPixelsSeen", 0) > LARGEST_SAFE_ATLAS;
    }

    private static String reason = COMPACT ? "packed to 16 bytes"
            : atlasTooLargeLastTime() ? "kept at 28 bytes (the atlas is too large to pack)"
            : "kept at 28 bytes";
    private static int atlasPixels;
    private static boolean atlasWarned;

    /**
     * Counted rather than atomic. These increment only when a value did not
     * fit, which is meant never to happen, and the packing runs on the chunk
     * builder threads for every vertex in the world — a shared atomic there
     * cost a fifth of chunk building the last time this project reached for
     * one. A lost count under a race is the right trade for a number whose
     * only job is to be nonzero.
     */
    private static long clampedPositions;
    private static long clampedLight;
    private static long clampedTexture;
    private static long packedVertices;
    /**
     * Buffers whose length is not a whole number of vanilla vertices. The sky
     * and the star field pass through the same mirror as the chunks and are
     * built from a different, shorter vertex — nothing draws them through this
     * renderer, so packing them as though they were chunk geometry is harmless,
     * but a number that is quietly not zero should be visible rather than
     * assumed.
     */
    private static long raggedBuffers;

    private VertexLayout() {
    }

    public static boolean isCompact() {
        return COMPACT;
    }



    /** One line for the log, at the moment the choice is made. */
    static String describe() {
        return "Chunk vertices " + reason;
    }

    /**
     * The atlas this session will sample, once it is known.
     *
     * A packed texture coordinate is one part in 65 535 of the whole sheet, so
     * how much of a texel that is depends entirely on how big the sheet turned
     * out to be — and that is not known until the pack has loaded, which is
     * after the layout has already been settled. So this warns rather than
     * decides, and the warning names the thing to turn off.
     */
    static void noteAtlas(int pixels) {
        atlasPixels = pixels;
        // Left where the next session's decision will find it, whatever this
        // session chose. Written as a plain property; the game side copies it
        // into the settings file, because this half cannot reach them.
        System.setProperty("vulkanmodnext.atlasPixels", Integer.toString(pixels));
        if (COMPACT && pixels > LARGEST_SAFE_ATLAS && !atlasWarned) {
            atlasWarned = true;
            org.apache.logging.log4j.LogManager.getLogger("VulkanModNext/Terrain").warn(
                    "The block atlas is {} pixels across and chunk vertices are packed to 16"
                            + " bytes. A packed texture coordinate is {} of a texel at that size,"
                            + " which is enough for sprite edges to bleed. Nothing needs doing:"
                            + " the packing turns itself off for this pack from the next start.",
                    pixels, "1/" + Math.max(1, 65535 / pixels));
        }
    }

    /** What a mirrored vertex takes in video memory. */
    public static int stride() {
        return COMPACT ? COMPACT_STRIDE : SOURCE_STRIDE;
    }

    /** What {@code sourceBytes} of vanilla geometry becomes once packed. */
    public static int packedSize(int sourceBytes) {
        return COMPACT ? sourceBytes / SOURCE_STRIDE * COMPACT_STRIDE : sourceBytes;
    }

    /**
     * Moves one chunk layer's geometry into the staging ring, packing it on the
     * way when the layout calls for it.
     *
     * Runs on the chunk builder threads, which is where it belongs: the copy it
     * replaces was a plain move of twenty-eight bytes a vertex, and this writes
     * sixteen. Those threads were measured at about one per cent busy while
     * flying, so the arithmetic has somewhere to go, and the write is smaller
     * than the one it replaces.
     *
     * @param sourceBytes how many bytes of vanilla geometry, a multiple of 28
     */
    public static void copy(long source, long destination, int sourceBytes) {
        if (COUNT_FACINGS) {
            tallyFacings(source, sourceBytes);
        }
        if (!COMPACT) {
            MemoryUtil.memCopy(source, destination, sourceBytes);
            return;
        }
        int count = sourceBytes / SOURCE_STRIDE;
        if (count * SOURCE_STRIDE != sourceBytes) {
            raggedBuffers++;
        }
        for (int i = 0; i < count; i++) {
            packVertex(source + (long) i * SOURCE_STRIDE, destination + (long) i * COMPACT_STRIDE);
        }
        packedVertices += count;
    }

    /** One vanilla vertex into one packed vertex. */
    private static void packVertex(long from, long to) {
        MemoryUtil.memPutShort(to, position(MemoryUtil.memGetFloat(from)));
        MemoryUtil.memPutShort(to + 2, position(MemoryUtil.memGetFloat(from + 4)));
        MemoryUtil.memPutShort(to + 4, position(MemoryUtil.memGetFloat(from + 8)));
        int first = light(MemoryUtil.memGetShort(from + 24) & 0xFFFF);
        int second = light(MemoryUtil.memGetShort(from + 26) & 0xFFFF);
        MemoryUtil.memPutShort(to + 6, (short) (first << 8 | second));
        // Colour is four bytes in both layouts and in the same order.
        MemoryUtil.memPutInt(to + 8, MemoryUtil.memGetInt(from + 12));
        MemoryUtil.memPutShort(to + 12, texture(MemoryUtil.memGetFloat(from + 16)));
        MemoryUtil.memPutShort(to + 14, texture(MemoryUtil.memGetFloat(from + 20)));
    }

    /** Which way a quad's face points, as far as skipping it goes. */
    public static final int GROUP_DOWN = 0;
    public static final int GROUP_MIDDLE = 1;
    public static final int GROUP_UP = 2;
    public static final int GROUP_NEG_X = 3;
    public static final int GROUP_NEG_Z = 4;
    public static final int GROUP_POS_X = 5;
    public static final int GROUP_POS_Z = 6;

    /** Shelf 16: down-facing quads outside the section, never skipped. */
    private static final int DOWN_OUTSIDE = 16;
    /**
     * Shelves 17..20: the four sideways facings, in a ring.
     *
     * The order is −X, −Z, +X, +Z rather than the obvious −X, +X, −Z, +Z, and
     * that is the whole trick of it. A camera sees exactly one of each opposite
     * pair, so the two shelves it can see are one from the X pair and one from
     * the Z pair — and in a ring, any such choice is two neighbours. Three of
     * the four corners of the world therefore leave a single hole in the range
     * and the fourth leaves two, instead of two holes every time.
     */
    private static final int SIDE_SHELF = 17;
    /** Shelf 21: quads that face along no axis at all — crossed plants, slopes. */
    private static final int MIDDLE_SHELF = 21;
    /** Shelf 22: up-facing quads outside the section's own levels. */
    private static final int UP_SHELF = 22;
    /** Down 0..16, the four sides, the middle, up 22..38. */
    public static final int SHELVES = 39;
    /** Where the three tables the draw side reads begin inside {@code counts}. */
    public static final int DOWN_TABLE = 4;
    public static final int UP_TABLE = 21;
    /** Five quad offsets: where each of the four side shelves starts, and their end. */
    public static final int SIDE_TABLE = 38;
    /** How long {@code counts} has to be. */
    public static final int COUNTS = 43;
    /** How many of {@code counts} the draw side keeps, from {@link #DOWN_TABLE} on. */
    public static final int DRAW_TABLES = COUNTS - DOWN_TABLE;

    private static final ThreadLocal<int[]> SHELF_TALLY = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[SHELVES];
        }
    };

    private static final ThreadLocal<int[]> SHELF_START = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[SHELVES];
        }
    };

    /**
     * Copies a chunk layer with its quads sorted into down-facing, everything
     * else, and up-facing — in that order, each group keeping its own order.
     *
     * <h2>What it buys</h2>
     *
     * A camera standing above a chunk cannot see one of that chunk's
     * downward-facing quads, and one standing below cannot see an upward-facing
     * one. The card already knows this and throws those triangles away — but
     * only after the vertex shader has run, which means every one of their
     * vertices was fetched first, and fetching is exactly what this pass is
     * bound by: 0.32 ms of fixed cost against 0.0095 ms per megapixel. Sorted
     * this way, the draw can simply stop short of the down group or start after
     * it, and the vertices are never read.
     *
     * <h2>Why three groups and not seven</h2>
     *
     * Six would let a camera skip about 43% of a chunk rather than the 22% or
     * 29% here — but it takes four draw commands per chunk-layer where this
     * takes one, because the visible groups are not adjacent. Four commands is
     * around 0.1 ms a frame of extra writing on the render thread, and the
     * processor and the card in this renderer are 0.85 ms against 0.95: a
     * change that takes 0.15 ms off the card and puts 0.1 ms on the processor
     * moves the ceiling rather than lowering it. Three groups cost the draw
     * side nothing at all.
     *
     * <h2>Why the groups are shelved by height</h2>
     *
     * A single "is the camera above every down-facing quad in this chunk" test
     * was tried and answers for a seventh of the geometry where this answers for
     * a fifth: one overhang near the top of a section makes the answer no for
     * everything below it, and the ground and the camera are usually inside the
     * same sixteen-block band anyway. Sorting each group by the block level it
     * sits on turns one conservative answer into seventeen exact ones, and lets
     * both ends of the range be trimmed in the same frame — the quads under the
     * camera and the ones over it are different quads.
     *
     * @param quadGroup scratch, one byte per quad, at least {@code sourceBytes / 112} long
     * @param counts filled with the down and up vertex counts, then 17 running
     *               totals of down-facing quads below each block level and 17
     *               of up-facing quads at or above it
     * @return false if the geometry could not be grouped and was left alone
     */
    public static boolean copyGrouped(long source, long destination, int sourceBytes,
                                      byte[] quadShelf, int[] quadTarget, int[] counts) {
        int quads = sourceBytes / (SOURCE_STRIDE * 4);
        if (quads <= 0 || quads * SOURCE_STRIDE * 4 != sourceBytes
                || quads > quadShelf.length || quads > quadTarget.length) {
            copy(source, destination, sourceBytes);
            counts[0] = 0;
            counts[1] = 0;
            return false;
        }
        // Shelves 0..15 are the section's own levels for down-facing quads,
        // low to high, so the ones under a camera are a prefix of the range.
        // Shelf 16 holds the down-facing quads that sit outside those levels
        // and may never be skipped, and it comes after them for that reason:
        // put first, as it was at first, the prefix skip eats it. Shelf 17 is
        // everything facing neither way. Shelf 18 is the up-facing quads
        // outside the levels, before 19..34, which are the levels low to high,
        // so the ones over a camera are a suffix.
        int[] tally = SHELF_TALLY.get();
        java.util.Arrays.fill(tally, 0);
        for (int q = 0; q < quads; q++) {
            long quad = source + (long) q * 4 * SOURCE_STRIDE;
            int group = facingGroup(quad);
            int shelf;
            if (group == GROUP_MIDDLE) {
                shelf = MIDDLE_SHELF;
            } else if (group == GROUP_DOWN || group == GROUP_UP) {
                int level = (int) Math.floor(MemoryUtil.memGetFloat(quad + 4));
                boolean inside = level >= 0 && level < 16;
                shelf = group == GROUP_DOWN
                        ? (inside ? level : DOWN_OUTSIDE)
                        : (inside ? UP_SHELF + 1 + level : UP_SHELF);
            } else {
                // The sideways ones are not shelved by which column they stand
                // in, and that is a deliberate difference from the vertical
                // ones. A camera is nearly always outside a section's own
                // sixteen blocks of x and of z — at this render distance all but
                // a handful of sections are — so the whole-section answer is
                // already the exact one, and seventeen shelves an axis would buy
                // a fraction of a per cent for four times the bookkeeping.
                //
                // That answer is only exact while the quad is inside the
                // section it was built for. A model may reach past its own
                // block — vanilla's do not, but a mod's may — and one that
                // reaches out to the east would be dropped by a camera further
                // east still, which is the section's answer and not the quad's.
                // Those go in the middle, where nothing is ever skipped.
                boolean alongX = group == GROUP_NEG_X || group == GROUP_POS_X;
                float on = MemoryUtil.memGetFloat(quad + (alongX ? 0 : 8));
                shelf = on >= 0.0f && on <= 16.0f
                        ? SIDE_SHELF + (group - GROUP_NEG_X)
                        : MIDDLE_SHELF;
            }
            quadShelf[q] = (byte) shelf;
            tally[shelf]++;
        }
        int[] start = SHELF_START.get();
        int at = 0;
        for (int shelf = 0; shelf < SHELVES; shelf++) {
            start[shelf] = at;
            at += tally[shelf];
        }
        // What the draw side reads: how many down-facing quads lie below each
        // level, and how many up-facing ones at or above it. Both count only
        // quads inside the section, so the outside ones are never skipped.
        int below = 0;
        for (int level = 0; level <= 16; level++) {
            counts[DOWN_TABLE + level] = below;
            if (level < 16) {
                below += tally[level];
            }
        }
        int above = 0;
        counts[UP_TABLE + 16] = 0;
        for (int level = 15; level >= 0; level--) {
            above += tally[UP_SHELF + 1 + level];
            counts[UP_TABLE + level] = above;
        }
        counts[0] = (tally[DOWN_OUTSIDE] + below) * 4;
        counts[1] = (tally[UP_SHELF] + above) * 4;
        // Where each side shelf begins and where the last one ends, in quads.
        // Taken before the placement loop below, which spends `start` as it
        // goes.
        for (int side = 0; side < 4; side++) {
            counts[SIDE_TABLE + side] = start[SIDE_SHELF + side];
        }
        counts[SIDE_TABLE + 4] = start[MIDDLE_SHELF];
        int stride = stride();
        for (int q = 0; q < quads; q++) {
            int target = start[quadShelf[q] & 0xFF]++;
            quadTarget[q] = target;
            long from = source + (long) q * 4 * SOURCE_STRIDE;
            long to = destination + (long) target * 4 * stride;
            if (COMPACT) {
                packVertex(from, to);
                packVertex(from + SOURCE_STRIDE, to + COMPACT_STRIDE);
                packVertex(from + SOURCE_STRIDE * 2L, to + COMPACT_STRIDE * 2L);
                packVertex(from + SOURCE_STRIDE * 3L, to + COMPACT_STRIDE * 3L);
            } else {
                MemoryUtil.memCopy(from, to, SOURCE_STRIDE * 4);
            }
        }
        if (COMPACT) {
            packedVertices += quads * 4;
        }
        return true;
    }

    /**
     * Which of the three groups a quad belongs to, from its own geometry.
     *
     * The sign convention is the one the card culls by, and it is not asserted
     * here — it is checked by the picture: get it backwards and the ground
     * disappears from under the camera the moment this is switched on, which is
     * the loudest failure available and the cheapest to see.
     */
    static int facingGroup(long quad) {
        float ax = MemoryUtil.memGetFloat(quad + SOURCE_STRIDE) - MemoryUtil.memGetFloat(quad);
        float ay = MemoryUtil.memGetFloat(quad + SOURCE_STRIDE + 4) - MemoryUtil.memGetFloat(quad + 4);
        float az = MemoryUtil.memGetFloat(quad + SOURCE_STRIDE + 8) - MemoryUtil.memGetFloat(quad + 8);
        float bx = MemoryUtil.memGetFloat(quad + SOURCE_STRIDE * 2L) - MemoryUtil.memGetFloat(quad);
        float by = MemoryUtil.memGetFloat(quad + SOURCE_STRIDE * 2L + 4) - MemoryUtil.memGetFloat(quad + 4);
        float bz = MemoryUtil.memGetFloat(quad + SOURCE_STRIDE * 2L + 8) - MemoryUtil.memGetFloat(quad + 8);
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float square = nx * nx + ny * ny + nz * nz;
        if (square <= 1.0e-18f) {
            return GROUP_MIDDLE;
        }
        // Along one axis only: a plant's crossed quad is at forty-five degrees
        // and stays in the middle, where it is always drawn.
        if (ny * ny / square >= 0.998f) {
            return ny > 0.0f ? GROUP_UP : GROUP_DOWN;
        }
        if (nx * nx / square >= 0.998f) {
            return nx > 0.0f ? GROUP_POS_X : GROUP_NEG_X;
        }
        if (nz * nz / square >= 0.998f) {
            return nz > 0.0f ? GROUP_POS_Z : GROUP_NEG_Z;
        }
        return GROUP_MIDDLE;
    }

    static short position(float value) {
        int units = Math.round((value - POSITION_ORIGIN) * POSITION_SCALE);
        if (units > Short.MAX_VALUE) {
            clampedPositions++;
            return Short.MAX_VALUE;
        }
        if (units < Short.MIN_VALUE) {
            clampedPositions++;
            return Short.MIN_VALUE;
        }
        return (short) units;
    }

    static int light(int value) {
        if (value > 0xFF) {
            clampedLight++;
            return 0xFF;
        }
        return value;
    }

    static short texture(float value) {
        int units = Math.round(value * 65535.0f);
        if (units < 0) {
            clampedTexture++;
            return 0;
        }
        if (units > 65535) {
            clampedTexture++;
            return (short) 0xFFFF;
        }
        return (short) units;
    }

    /**
     * Reads and resets the three counts that say whether the packing is honest.
     * All three are meant to stay at zero, and a report that never shows them
     * is a report that cannot tell anybody it went wrong.
     */
    /**
     * Counts one chunk layer's quads by which way they face.
     *
     * Accumulated locally and published once for the whole layer: a counter
     * touched per quad from every builder thread would cost more than the
     * thing it is measuring, which this project has already paid for once.
     */
    private static void tallyFacings(long source, int sourceBytes) {
        int quads = sourceBytes / (SOURCE_STRIDE * 4);
        int[] local = new int[7];
        for (int q = 0; q < quads; q++) {
            long v0 = source + (long) q * 4 * SOURCE_STRIDE;
            long v1 = v0 + SOURCE_STRIDE;
            long v2 = v1 + SOURCE_STRIDE;
            float ax = MemoryUtil.memGetFloat(v1) - MemoryUtil.memGetFloat(v0);
            float ay = MemoryUtil.memGetFloat(v1 + 4) - MemoryUtil.memGetFloat(v0 + 4);
            float az = MemoryUtil.memGetFloat(v1 + 8) - MemoryUtil.memGetFloat(v0 + 8);
            float bx = MemoryUtil.memGetFloat(v2) - MemoryUtil.memGetFloat(v0);
            float by = MemoryUtil.memGetFloat(v2 + 4) - MemoryUtil.memGetFloat(v0 + 4);
            float bz = MemoryUtil.memGetFloat(v2 + 8) - MemoryUtil.memGetFloat(v0 + 8);
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length <= 1.0e-9f) {
                local[6]++;
                continue;
            }
            nx /= length;
            ny /= length;
            nz /= length;
            // On an axis only if it is on it squarely; a plant's crossed quad
            // sits at forty-five degrees and belongs in the last bucket, since
            // no grouping by facing could ever skip it.
            float straight = 0.999f;
            if (nx > straight) {
                local[1]++;
            } else if (nx < -straight) {
                local[0]++;
            } else if (ny > straight) {
                local[3]++;
            } else if (ny < -straight) {
                local[2]++;
            } else if (nz > straight) {
                local[5]++;
            } else if (nz < -straight) {
                local[4]++;
            } else {
                local[6]++;
            }
        }
        for (int i = 0; i < 7; i++) {
            if (local[i] != 0) {
                FACINGS.addAndGet(i, local[i]);
            }
        }
    }

    public static String stats() {
        String line = "vertex layout: " + reason
                + (atlasPixels > 0 ? ", atlas " + atlasPixels + " px" : "");
        if (COMPACT) {
            line += String.format(", %d vertices packed, out of range: position %d, light %d,"
                            + " texture %d, not whole vertices %d", packedVertices,
                    clampedPositions, clampedLight, clampedTexture, raggedBuffers);
            packedVertices = 0L;
            raggedBuffers = 0L;
            clampedPositions = 0L;
            clampedLight = 0L;
            clampedTexture = 0L;
        }
        if (COUNT_FACINGS) {
            long total = 0L;
            for (int i = 0; i < 7; i++) {
                total += FACINGS.get(i);
            }
            if (total != 0L) {
                String[] names = {"-X", "+X", "down", "up", "-Z", "+Z", "on no axis"};
                StringBuilder facing = new StringBuilder("\n  quad facings: ");
                for (int i = 0; i < 7; i++) {
                    facing.append(names[i]).append(' ')
                            .append(String.format("%.1f%%", 100.0 * FACINGS.get(i) / total));
                    facing.append(i == 6 ? "" : ", ");
                }
                facing.append(" — over ").append(total).append(" quads");
                line += facing.toString();
            }
        }
        return line;
    }
}
