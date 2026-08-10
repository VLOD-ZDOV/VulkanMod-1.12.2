package net.vulkanmod112.client;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112");

    /** Start time, then the chunk's own position, so a slow one can be named. */
    private static final ThreadLocal<long[]> STARTED = new ThreadLocal<long[]>() {
        @Override
        protected long[] initialValue() {
            // start, x, y, z, processor time at start, collections at start
            return new long[6];
        }
    };

    private static final AtomicLong rebuilds = new AtomicLong();
    private static final AtomicLong nanos = new AtomicLong();
    private static final AtomicLong worst = new AtomicLong();

    /**
     * How the rebuilds are spread, not just what they average.
     *
     * The average has been known for a while and is a couple of milliseconds.
     * What is open is the other end: rebuilds taking three hundred milliseconds
     * and more were seen once, while measuring something else, and never
     * explained. An average cannot show them — a hundred ordinary chunks bury
     * one bad one — so the buckets are here to say how often it happens before
     * anybody tries to work out why.
     *
     * Edges in milliseconds: under 2, under 8, under 32, under 128, the rest.
     */
    private static final long[] BUCKET_EDGES_NANOS = {
        2_000_000L, 8_000_000L, 32_000_000L, 128_000_000L, Long.MAX_VALUE
    };
    private static final AtomicLongArray buckets = new AtomicLongArray(BUCKET_EDGES_NANOS.length);

    /**
     * Above this, one line is written naming the chunk.
     *
     * A number rather than a bucket, because the question about these is not how
     * many there are but what they have in common — the same place every time
     * means the world, a different place every time means the machine.
     */
    private static final long SLOW_NANOS = Long.getLong("vulkanmod112.slowChunkMs", 100L) * 1_000_000L;
    /** At most one line a second: a bad moment can be thousands of chunks. */
    private static final long SLOW_LOG_INTERVAL_NANOS = 1_000_000_000L;
    private static final AtomicLong lastSlowLogNanos = new AtomicLong();
    private static final AtomicLong slowSeen = new AtomicLong();
    /** Processor time the builds really used, against the wall time they took. */
    private static final AtomicLong cpuNanos = new AtomicLong();
    /** Builds over eight milliseconds that spent most of them not running. */
    private static final AtomicLong stalled = new AtomicLong();
    /** Builds over eight milliseconds with a collection inside them. */
    private static final AtomicLong duringCollection = new AtomicLong();

    private ChunkBuildStats() {
    }

    public static void begin(float x, float y, float z) {
        long[] started = STARTED.get();
        started[0] = System.nanoTime();
        started[1] = (long) x;
        started[2] = (long) y;
        started[3] = (long) z;
        started[4] = JvmPauses.threadCpuNanos();
        started[5] = JvmPauses.collections();
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
        // What the thread actually did with that time. Wall time alone
        // cannot tell a hundred milliseconds of meshing from a hundred
        // milliseconds of waiting to be allowed to mesh, and every reading
        // of the meshing code in the world will not tell them apart either.
        long cpu = JvmPauses.threadCpuNanos();
        long ranFor = cpu >= 0L && started[4] >= 0L ? cpu - started[4] : -1L;
        long collected = JvmPauses.collections() - started[5];
        started[0] = 0L;
        rebuilds.incrementAndGet();
        nanos.addAndGet(elapsed);
        if (ranFor >= 0L) {
            cpuNanos.addAndGet(ranFor);
            // A build that spent less than half its time on a processor was
            // mostly waiting. Counted rather than averaged: the average is
            // dominated by the thousands of ordinary builds, and the question
            // is about the handful of bad ones.
            if (elapsed > 8_000_000L && ranFor * 2L < elapsed) {
                stalled.incrementAndGet();
            }
        }
        if (collected > 0L && elapsed > 8_000_000L) {
            duringCollection.incrementAndGet();
        }
        // Read-modify-write on a shared maximum, so a compare-and-set loop
        // rather than a compare followed by a store.
        long seen = worst.get();
        while (elapsed > seen && !worst.compareAndSet(seen, elapsed)) {
            seen = worst.get();
        }
        for (int i = 0; i < BUCKET_EDGES_NANOS.length; i++) {
            if (elapsed < BUCKET_EDGES_NANOS[i]) {
                buckets.incrementAndGet(i);
                break;
            }
        }
        if (elapsed >= SLOW_NANOS) {
            noteSlow(elapsed, started[1], started[2], started[3], ranFor, collected);
        }
    }

    private static void noteSlow(long elapsed, long x, long y, long z,
                                 long ranFor, long collected) {
        long count = slowSeen.incrementAndGet();
        long now = System.nanoTime();
        long last = lastSlowLogNanos.get();
        if (now - last < SLOW_LOG_INTERVAL_NANOS && last != 0L) {
            return;
        }
        if (!lastSlowLogNanos.compareAndSet(last, now)) {
            return;
        }
        // The breakdown is the whole value of this line. "Ran for 3 ms of a
        // 104 ms build" says the meshing is innocent and something held the
        // thread; "ran for 98 of 104" says it really did that much work.
        String spent = ranFor < 0L ? "thread time unknown"
                : String.format("ran for %.0f ms of it", ranFor / 1_000_000.0);
        LOGGER.info("Slow chunk build: {} ms at block ({}, {}, {}) on {} — {}{} — "
                        + "{} so far this session",
                String.format("%.0f", elapsed / 1_000_000.0), x, y, z,
                Thread.currentThread().getName(), spent,
                collected > 0L ? ", and the collector ran during it" : "", count);
    }

    /** Read and reset, for the diagnostics report. */
    public static String stats() {
        long count = rebuilds.getAndSet(0L);
        if (count == 0L) {
            return "chunk builds: none since the last report";
        }
        long total = nanos.getAndSet(0L);
        long slowest = worst.getAndSet(0L);
        StringBuilder spread = new StringBuilder();
        String[] names = {"<2 ms", "<8 ms", "<32 ms", "<128 ms", ">=128 ms"};
        for (int i = 0; i < names.length; i++) {
            long n = buckets.getAndSet(i, 0L);
            if (i > 0) {
                spread.append(", ");
            }
            spread.append(names[i]).append(' ').append(n);
        }
        long ran = cpuNanos.getAndSet(0L);
        long waited = stalled.getAndSet(0L);
        long collected = duringCollection.getAndSet(0L);
        // Wall time and processor time side by side, because the gap between
        // them is the answer to the question these buckets have been asking
        // without being able to answer since they were written.
        String running = !JvmPauses.cpuTimeReadable() ? "thread time unavailable here"
                : String.format("%.1f ms of it on a processor (%.0f%%)",
                        ran / 1_000_000.0, 100.0 * ran / Math.max(1L, total));
        return String.format(
                "chunk builds: %d rebuilds, %.2f ms each on average, worst %.2f ms, "
                        + "%.1f ms of wall time in total, %s; spread %s; "
                        + "%d slow builds spent most of their time not running, "
                        + "%d had a collection inside them; %d over %d ms this session",
                count, total / (count * 1_000_000.0), slowest / 1_000_000.0,
                total / 1_000_000.0, running, spread, waited, collected,
                slowSeen.get(), SLOW_NANOS / 1_000_000L);
    }
}
