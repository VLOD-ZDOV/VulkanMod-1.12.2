package net.vulkanmod112.client;

import java.util.concurrent.atomic.AtomicLong;

/**
 * How long turning a chunk of blocks into triangles actually takes.
 *
 * This is the frame's dominant cost while the world is filling in — 330 fps
 * standing still against 90 to 200 flying, and chunk building is what the
 * difference is spent on — so it is worth a number of its own regardless of
 * what is being added around it.
 *
 * <h2>Why here and not per block</h2>
 *
 * The first attempt at measuring the material recording put a clock either
 * side of every block, and reported 24 ns a block. Two calls to
 * {@code System.nanoTime} cost about that between them on their own: the
 * measurement was most of what it measured, and no conclusion could be drawn
 * about the work. A whole chunk is forty thousand block positions, so one
 * clock pair around the rebuild is four orders of magnitude cheaper per block
 * and reports the same total.
 *
 * That also makes the number the useful one. Nobody wants to know what a
 * hashmap lookup costs; the question is whether a chunk takes longer to build
 * with a feature on than with it off, and that is exactly what this is.
 *
 * <h2>Threads</h2>
 *
 * Chunks are built on worker threads and, close to the camera, on the render
 * thread. The clock start is therefore per thread, and the totals are atomics.
 */
public final class ChunkBuildStats {

    private static final ThreadLocal<long[]> STARTED = new ThreadLocal<long[]>() {
        @Override
        protected long[] initialValue() {
            return new long[1];
        }
    };

    private static final AtomicLong rebuilds = new AtomicLong();
    private static final AtomicLong nanos = new AtomicLong();
    private static final AtomicLong worst = new AtomicLong();

    private ChunkBuildStats() {
    }

    public static void begin() {
        STARTED.get()[0] = System.nanoTime();
    }

    public static void end() {
        long[] started = STARTED.get();
        if (started[0] == 0L) {
            // A rebuild whose start was never seen: the mixin was added to a
            // running game, or an exception unwound past it. Not counted rather
            // than counted from zero, which would report a rebuild taking the
            // age of the process.
            return;
        }
        long elapsed = System.nanoTime() - started[0];
        started[0] = 0L;
        rebuilds.incrementAndGet();
        nanos.addAndGet(elapsed);
        // Read-modify-write on a shared maximum, so a compare-and-set loop
        // rather than a compare followed by a store.
        long seen = worst.get();
        while (elapsed > seen && !worst.compareAndSet(seen, elapsed)) {
            seen = worst.get();
        }
    }

    /** Read and reset, for the diagnostics report. */
    public static String stats() {
        long count = rebuilds.getAndSet(0L);
        if (count == 0L) {
            return "chunk builds: none since the last report";
        }
        long total = nanos.getAndSet(0L);
        long slowest = worst.getAndSet(0L);
        return String.format(
                "chunk builds: %d rebuilds, %.2f ms each on average, worst %.2f ms, "
                        + "%.1f ms of thread time in total",
                count, total / (count * 1_000_000.0), slowest / 1_000_000.0,
                total / 1_000_000.0);
    }
}
