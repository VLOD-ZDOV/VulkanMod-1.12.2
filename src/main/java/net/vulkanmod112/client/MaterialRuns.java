package net.vulkanmod112.client;

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
     * The table recorded for a finished buffer.
     *
     * @return null when nothing was recorded, which is every chunk while the
     *         setting is off and every empty layer while it is on
     */
    public static Table take(BufferBuilder builder) {
        Table table = TABLES.get(builder);
        return table == null || table.count == 0 ? null : table;
    }

    /**
     * What a block is, for the purposes of drawing it.
     *
     * Vanilla's own {@link Material} rather than a table of block names: a
     * modded leaf block that declares itself as leaves is then foliage here
     * without this having to know it exists, which is the same reasoning as
     * the dynamic light levels.
     */
    private static int materialOf(IBlockState state) {
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
        // Leaves, grass, flowers, crops, vines. Not cactus and not pumpkins:
        // they are made of plant too, and they are rigid blocks that would look
        // wrong swaying, which is what this tag is going to be asked to decide.
        if (material == Material.LEAVES || material == Material.PLANTS
                || material == Material.VINE) {
            return FOLIAGE;
        }
        if (material == Material.GLASS) {
            return GLASS;
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
                        + "%.0f%% of them one plain run",
                blockCount, tableCount,
                tableCount == 0 ? 0.0 : runs / (double) tableCount,
                tableCount == 0 ? 0.0 : 100.0 * plain / tableCount);
    }
}
