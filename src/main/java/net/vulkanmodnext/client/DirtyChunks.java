package net.vulkanmodnext.client;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Which chunks of the grid are waiting to be rebuilt, held as bits beside the
 * grid instead of as a flag inside each chunk object.
 *
 * <h2>The loop this exists for</h2>
 *
 * The last thing {@code setupTerrain} does is walk the whole visible list and
 * ask each chunk whether it needs rebuilding:
 *
 * <pre>
 *   for (info : renderInfos)
 *       if (info.renderChunk.needsUpdate() || pending.contains(info.renderChunk))
 *           ...
 * </pre>
 *
 * The game's own profiler puts that loop at 19.1% of the frame at render
 * distance 64, where the visible list runs to some 8 600 entries. Neither test
 * is expensive in itself; what they cost is where they read from.
 * {@code needsUpdate()} is one boolean inside a {@code RenderChunk}, and those
 * objects are scattered across the heap — a cache miss each, 8 600 of them a
 * frame, to collect 8 600 bits. Then {@code contains} hashes into a set at
 * another unrelated address.
 *
 * That is the same disease the visibility search had, in the loop next door,
 * and it has the same cure: put the answer somewhere the loop can read without
 * following a pointer. One bit per grid slot is 33 KiB for a 266 000-chunk
 * grid, which fits in first-level cache; the chunks it describes are hundreds
 * of megabytes of objects. The visible list is in search order, which is
 * breadth-first from the camera, so consecutive entries land in nearby slots
 * and the reads stay local.
 *
 * <h2>Keeping it true</h2>
 *
 * Every transition is caught at the source: the two methods on
 * {@code RenderChunk} that set and clear the flag are the only ways it can
 * change, and both are hooked, so a mod that marks a chunk dirty is caught as
 * well as the game. Inside those methods the chunk is already in cache, so
 * reading its slot number off it costs nothing.
 *
 * The failure to be afraid of is a bit that reads clean while the chunk is
 * dirty: that chunk would never be queued and would stay stale on screen. So
 * the rules are one-sided — the grid starts with every bit set, because a fresh
 * {@code RenderChunk} starts dirty, and a slot number out of range answers
 * "dirty" rather than being ignored. A bit that reads dirty while the chunk is
 * clean costs one wasted check and nothing else.
 *
 * <h2>Why the dirty half is atomic and the other half is not</h2>
 *
 * Chunks are marked dirty from the threads that build them, not only from the
 * one that draws: a build that ends without finishing puts the chunk back in
 * the queue from inside the worker. Sixty-four slots share a word here, so two
 * threads setting bits in the same word with a plain read-modify-write can lose
 * one of them — and the bit that goes missing is a bit that said "rebuild me",
 * which is exactly the failure that leaves stale terrain on screen. So that
 * half is a compare-and-set. It is on the path that runs when a block changes,
 * not the one that runs 8 600 times a frame.
 *
 * The queued half is touched only by the render thread, in one loop, so it is a
 * plain array.
 */
public final class DirtyChunks {

    private static final AtomicLongArray EMPTY = new AtomicLongArray(0);

    /** Waiting to be rebuilt: mirrors {@code RenderChunk.needsUpdate}. */
    private static volatile AtomicLongArray dirty = EMPTY;
    /**
     * Already queued in {@code chunksToUpdate}. Set and cleared around the one
     * loop that asks, because that set is small and the alternative is hashing
     * every visible chunk against it.
     */
    private static long[] pending = new long[0];
    private static int slots;

    private DirtyChunks() {
    }

    /**
     * A new chunk grid exists. Every chunk in it is born needing an update, so
     * every bit starts set.
     */
    public static void grid(int count) {
        int size = count < 0 ? 0 : count;
        int words = (size + 63) >> 6;
        AtomicLongArray fresh = new AtomicLongArray(words);
        for (int i = 0; i < words; i++) {
            fresh.set(i, -1L);
        }
        pending = new long[words];
        slots = size;
        // Published last: a worker thread still holding a chunk from the old
        // grid then marks the array that is being thrown away rather than
        // writing a stale bit into the new one.
        dirty = fresh;
    }

    public static boolean ready() {
        return slots > 0;
    }

    public static void markDirty(int slot) {
        if (slot < 0) {
            return;
        }
        AtomicLongArray bits = dirty;
        int word = slot >> 6;
        if (word >= bits.length()) {
            return;
        }
        long bit = 1L << slot;
        for (;;) {
            long current = bits.get(word);
            if ((current & bit) != 0L || bits.compareAndSet(word, current, current | bit)) {
                return;
            }
        }
    }

    public static void clearDirty(int slot) {
        if (slot < 0) {
            return;
        }
        AtomicLongArray bits = dirty;
        int word = slot >> 6;
        if (word >= bits.length()) {
            return;
        }
        long bit = 1L << slot;
        for (;;) {
            long current = bits.get(word);
            if ((current & bit) == 0L || bits.compareAndSet(word, current, current & ~bit)) {
                return;
            }
        }
    }

    public static void markPending(int slot) {
        if (slot >= 0 && slot < slots) {
            pending[slot >> 6] |= 1L << slot;
        }
    }

    public static void clearPending(int slot) {
        if (slot >= 0 && slot < slots) {
            pending[slot >> 6] &= ~(1L << slot);
        }
    }

    /**
     * Whether this slot answers yes to either half of vanilla's test. A slot
     * outside the grid answers yes, so an unknown chunk is checked rather than
     * skipped.
     */
    public static boolean dirtyOrPending(int slot) {
        if (slot < 0 || slot >= slots) {
            return true;
        }
        int word = slot >> 6;
        long bit = 1L << slot;
        return ((dirty.get(word) | pending[word]) & bit) != 0L;
    }
}
