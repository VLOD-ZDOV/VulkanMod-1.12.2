package net.vulkanmod112.client;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What each stretch of a chunk's geometry is made of.
 *
 * <h2>Why this exists</h2>
 *
 * The renderer draws terrain in vanilla's four layers, and a layer is not a
 * material. TRANSLUCENT is water and stained glass together; CUTOUT is grass
 * and torches and rails and ladders together. Every effect worth having next
 * needs to tell those apart — water wants a fresnel term and moving normals,
 * foliage wants to sway and to be lit as a soft volume rather than as the two
 * flat vertical quads it actually is, and a ray needs to know what it passed
 * through. The vertex carries no such thing: 28 bytes of position, colour,
 * texture and light map, and that is the game's format, mirrored byte for
 * byte.
 *
 * <h2>Where the information is</h2>
 *
 * It exists exactly once, for a moment, in {@code RenderChunk.rebuildChunk}:
 * the loop walks the blocks of a chunk and calls {@code renderBlock} for each
 * one. Before that call the block state is in hand; after it, the vertices it
 * produced are on the end of the layer's buffer. Nowhere later does anything
 * know which block a vertex came from.
 *
 * So the block's material is recorded against the range of vertices it wrote,
 * and consecutive blocks of the same material are merged into one run. A chunk
 * of stone is one run; a hillside of grass and dirt is a handful. What comes
 * out is a small table per chunk layer, in vertex order, which the Vulkan side
 * can later turn into a buffer beside the geometry rather than inside it —
 * leaving the mirrored vertex format untouched, which is the whole point.
 *
 * <h2>Threads</h2>
 *
 * Chunks are built on worker threads and uploaded from either a worker or the
 * render thread, so a table is written by one thread and read by another. The
 * handover is the buffer itself: a table is keyed by the {@link BufferBuilder}
 * the vertices went into, and whoever uploads that buffer takes the table with
 * them. {@code BufferBuilder} does not override equals or hashCode, so the map
 * is an identity map already, and there are only ever a few builders alive —
 * four per build thread.
 */
public final class MaterialRuns {

    /** Nothing special; the great majority of a world. */
    public static final int PLAIN = 0;
    public static final int WATER = 1;
    /** Leaves, grass, flowers, crops — anything that should move in wind. */
    public static final int FOLIAGE = 2;
    public static final int GLASS = 3;
    public static final int LAVA = 4;
    public static final int ICE = 5;
    /** A cross-shaped plant: lit as a volume like foliage, and the only thing that sways. */
    public static final int PLANT = 6;
    /**
     * Not a material but a flag beside one: this block gives off light itself.
     *
     * The top bit, so it can be true of any of the above and of none of them —
     * lava is a material and a light, glowstone is a light and nothing in
     * particular. Bloom reads it, and it has to be a fact about the block
     * rather than about the pixel: a glowstone block is a light across its
     * whole face including the dark texels of its texture, and anything worked
     * out from what a fragment can see gets that wrong.
     */
    public static final int EMITS = 0x80;

    /**
     * A run is two ints: the vertex one past the end of the run, and what the
     * run is made of. The start is the previous run's end, so it is not stored.
     */
    private static final int RUN_INTS = 2;

    /**
     * How many runs a table starts with. A chunk layer that is all one material
     * needs one; the worst honest case seen is a shoreline, and that is tens
     * rather than hundreds, because runs merge.
     */
    private static final int INITIAL_RUNS = 16;

    public static final class Table {
        private int[] runs = new int[INITIAL_RUNS * RUN_INTS];
        private int count;
        /** Material of the run being built, so a repeat is a no-op. */
        private int openMaterial = -1;
        /**
         * Blocks recorded into this table, counted here rather than into the
         * shared total.
         *
         * A table belongs to one buffer and a buffer to one build thread, so
         * this is a plain field on data nobody else touches. The first version
         * incremented a shared AtomicLong per block instead, and that measured:
         * a dozen build threads pushing four hundred thousand blocks a second
         * through one cache line cost more than the recording it was counting.
         */
        private int blocks;

        /** Records that vertices up to {@code endVertex} are of this material. */
        void extend(int endVertex, int material) {
            blocks++;
            if (material == openMaterial && count > 0) {
                runs[(count - 1) * RUN_INTS] = endVertex;
                return;
            }
            if (count * RUN_INTS == runs.length) {
                int[] bigger = new int[runs.length * 2];
                System.arraycopy(runs, 0, bigger, 0, runs.length);
                runs = bigger;
            }
            runs[count * RUN_INTS] = endVertex;
            runs[count * RUN_INTS + 1] = material;
            count++;
            openMaterial = material;
        }

        void reset() {
            count = 0;
            blocks = 0;
            openMaterial = -1;
        }

        public int count() {
            return count;
        }

        public int endOf(int run) {
            return runs[run * RUN_INTS];
        }

        public int materialOf(int run) {
            return runs[run * RUN_INTS + 1];
        }

        /** True when the whole table is one material and that material is plain. */
        public boolean plain() {
            return count == 0 || (count == 1 && materialOf(0) == PLAIN);
        }
    }

    private static final Map<BufferBuilder, Table> TABLES = new ConcurrentHashMap<>();

    private static final AtomicLong blocks = new AtomicLong();
    private static final AtomicLong tables = new AtomicLong();
    private static final AtomicLong runsTotal = new AtomicLong();
    private static final AtomicLong plainTables = new AtomicLong();
    private static final AtomicLong published = new AtomicLong();
    private static final AtomicLong reordered = new AtomicLong();
    private static final AtomicLong resorted = new AtomicLong();

    private MaterialRuns() {
    }

    /**
     * Called around the one {@code renderBlock} call in the chunk rebuild loop.
     *
     * The vertex count is read from the buffer rather than counted here,
     * because a block may write to more than one layer and may write nothing at
     * all, and the buffer is the only thing that knows.
     */
    public static void record(IBlockState state, BufferBuilder builder, int endVertex) {
        Table table = TABLES.get(builder);
        if (table == null) {
            table = new Table();
            Table raced = TABLES.putIfAbsent(builder, table);
            if (raced != null) {
                table = raced;
            }
        }
        table.extend(endVertex, materialOf(state));
    }

    /**
     * A buffer is starting a new chunk layer; whatever it held is last chunk's.
     *
     * The table is emptied here rather than after it has been read, because a
     * build can be thrown away without ever being uploaded — a chunk rebuilt
     * twice before the first result is wanted — and a table cleared only on the
     * way out would carry the abandoned chunk's runs into the next one.
     */
    public static void begin(BufferBuilder builder) {
        Table table = TABLES.get(builder);
        if (table != null) {
            // Whatever is in it belongs to the chunk layer that just finished,
            // so this is where a completed table can be counted. The first
            // version counted them in take() instead — which nothing calls yet,
            // so the one number the next slice actually needs came out as zero.
            measure(table);
            table.reset();
        }
    }

    private static void measure(Table table) {
        if (table.count == 0) {
            return;
        }
        tables.incrementAndGet();
        runsTotal.addAndGet(table.count);
        blocks.addAndGet(table.blocks);
        if (table.plain()) {
            plainTables.incrementAndGet();
        }
    }

    /**
     * Sends a finished layer's runs to the renderer, keyed by the slot its
     * geometry will arrive under.
     *
     * @param sorted whether the game is about to reorder this layer's quads.
     *               It does that to the translucent layer, and only to that
     *               one, so that water draws back to front — and the runs are
     *               numbered by vertex, so after the reordering they describe
     *               the wrong vertices. A layer made of a single material
     *               survives it untouched, because there is nothing to permute
     *               between; anything else is dropped rather than sent wrong.
     *               That covers most water, since a chunk's translucent layer
     *               is usually water and nothing else, and it leaves the mixed
     *               ones plain until there is a real answer for them.
     */
    public static void publish(int slot, BufferBuilder builder, boolean sorted) {
        Table table = TABLES.get(builder);
        if (table == null || table.count == 0) {
            // Nothing was recorded into this buffer, so no chunk was built into
            // it: this is the game re-sorting the translucent layer, which it
            // does as the camera moves so that water draws back to front. It
            // walks no blocks — it takes the quads that are already there,
            // permutes them and uploads them again.
            //
            // Saying nothing is the whole point. The renderer then leaves the
            // materials it already has, which describe these same vertices. The
            // first version had no way to say nothing, and the silence read as
            // "plain": water turned grey a chunk at a time as you moved.
            resorted.incrementAndGet();
            return;
        }
        published.incrementAndGet();
        if (sorted) {
            // The translucent layer, whose quads the game is about to sort by
            // distance — and will sort again, without rebuilding, every time
            // the camera moves far enough, so that water draws back to front.
            // Runs numbered by vertex describe the wrong surface the moment the
            // quads move, so none are sent for this layer at all. Its material
            // is read off the block atlas in the shader instead, because a sort
            // that moves whole quads cannot separate one from its own texture
            // coordinates. See MaterialSprites.
            //
            // Sending plain rather than saying nothing: silence means "keep
            // what is there", and what is there may be an older chunk's runs.
            reordered.incrementAndGet();
            ChunkMirror.onMaterials(slot, ALL_PLAIN, 1);
            return;
        }
        if (table.plain()) {
            // Said rather than left unsaid, for the same reason: a chunk whose
            // torches somebody just took away is plain now, and only an
            // explicit answer clears the runs still in the buffer.
            ChunkMirror.onMaterials(slot, ALL_PLAIN, 1);
            return;
        }
        ChunkMirror.onMaterials(slot, table.runs, table.count);
    }

    /**
     * One run covering everything, made of nothing in particular.
     *
     * The end is past any vertex count there could be; the renderer clamps it
     * to what actually arrived.
     */
    private static final int[] ALL_PLAIN = {Integer.MAX_VALUE, PLAIN};

    /**
     * What a block is, for the purposes of drawing it.
     *
     * Vanilla's own {@link Material} rather than a table of block names: a
     * modded leaf block that declares itself as leaves is then foliage here
     * without this having to know it exists, which is the same reasoning as
     * the dynamic light levels.
     */
    private static int materialOf(IBlockState state) {
        return shapeOf(state) | lightOf(state);
    }

    /**
     * Whether the block is a light source, from the block itself rather than
     * from a list here — so a modded lamp is one without this knowing it
     * exists, the same reasoning as the dynamic light levels.
     */
    private static int lightOf(IBlockState state) {
        try {
            return state.getLightValue() > 0 ? EMITS : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int shapeOf(IBlockState state) {
        Material material;
        try {
            material = state.getMaterial();
        } catch (Throwable t) {
            // A block that cannot describe itself is not worth failing a chunk
            // build over; it draws as it always did.
            return PLAIN;
        }
        if (material == Material.WATER) {
            return WATER;
        }
        if (material == Material.LAVA) {
            return LAVA;
        }
        // Grass, flowers, saplings, crops: the things drawn as two flat quads
        // crossing each other, which are the only ones that can be made to
        // sway. Everything about a cross model is vertical, so the top pair of
        // corners of every quad is its top and moving them is the whole of it.
        //
        // Two are held back. A plant that spans more than one block — a double
        // plant, sugar cane — has the top of the lower block and the bottom of
        // the upper block at the same height, and only the first of the two
        // would move: the stem would come apart at the seam. Doing that
        // properly means knowing how far up its own plant a block is, which is
        // not something a single block state can say.
        // Both, and this is not tidiness. Vanilla's Material is a table of
        // physical behaviour, not of shape, and in this version ordinary grass,
        // ferns, dead bushes, real vines and two-block plants are all
        // Material.VINE together. Reading that name as "a vine" is what left
        // every patch of grass in the world standing perfectly still while the
        // flowers beside it moved. What the shape is has to come from the block.
        if (material == Material.PLANTS || material == Material.VINE) {
            Block block = state.getBlock();
            boolean still =
                    // Hangs from above, so the end that must stay put is the
                    // top one — the opposite rule to everything else here.
                    block instanceof net.minecraft.block.BlockVine
                    // Taller than one block: the top of the lower half and the
                    // bottom of the upper half are at the same height, so only
                    // the first would move and the stem would come apart.
                    || block instanceof net.minecraft.block.BlockDoublePlant
                    || block instanceof net.minecraft.block.BlockReed
                    // Flat on the water: all four of its corners are level, and
                    // the rule that moves the top pair would tear it in half.
                    || block instanceof net.minecraft.block.BlockLilyPad
                    // Boxes rather than crossed quads. A cocoa pod is a small
                    // block fixed to the side of a trunk and chorus is a
                    // structure of joined boxes: shearing the top of either
                    // away from its bottom pulls it off what it is growing on.
                    || block instanceof net.minecraft.block.BlockCocoa
                    || block instanceof net.minecraft.block.BlockChorusPlant
                    || block instanceof net.minecraft.block.BlockChorusFlower;
            return still ? FOLIAGE : PLANT;
        }
        // Leaves: lit as a volume like the rest, never moved. A leaf block is a
        // full cube, so its top face is four corners at one height, and moving
        // the top pair of each quad would tear that face apart.
        if (material == Material.LEAVES) {
            return FOLIAGE;
        }
        if (material == Material.GLASS) {
            return GLASS;
        }
        if (material == Material.ICE || material == Material.PACKED_ICE) {
            return ICE;
        }
        return PLAIN;
    }

    /** Read and reset, for the diagnostics report. */
    public static String stats() {
        if (!VulkanConfig.isMaterialTags()) {
            return "material tags: off";
        }
        long blockCount = blocks.getAndSet(0L);
        long tableCount = tables.getAndSet(0L);
        if (blockCount == 0L) {
            return "material tags: on, nothing built yet";
        }
        long runs = runsTotal.getAndSet(0L);
        long plain = plainTables.getAndSet(0L);
        // No time here on purpose. This runs once per block, and two calls to
        // the clock around a map lookup and an array write cost more than the
        // work between them — the first version reported 24 ns a block and most
        // of that was the reading of it. What the cost of this actually is has
        // to be read off the whole chunk rebuild, which is what ChunkBuildStats
        // times, with one clock pair per forty thousand blocks instead of two.
        return String.format(
                "material tags: %d blocks recorded, %d chunk layers averaging %.1f runs, "
                        + "%.0f%% of them one plain run; %d layers sent to the renderer, "
                        + "%d of those translucent and left to the atlas, "
                        + "%d re-sorts left alone",
                blockCount, tableCount,
                tableCount == 0 ? 0.0 : runs / (double) tableCount,
                tableCount == 0 ? 0.0 : 100.0 * plain / tableCount,
                published.getAndSet(0L), reordered.getAndSet(0L), resorted.getAndSet(0L));
    }
}
