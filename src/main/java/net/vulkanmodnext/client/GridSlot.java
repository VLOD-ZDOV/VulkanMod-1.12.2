package net.vulkanmodnext.client;

/**
 * A {@code RenderChunk}'s position in the grid, carried on the chunk itself.
 *
 * The grid is a fixed array built once when the world loads, and a chunk never
 * moves within it — only the world position it stands for changes, when the
 * grid recentres on the player. So the array index is a stable, dense name for
 * a chunk, which is what {@link DirtyChunks} indexes by and what the visibility
 * search already computes for every chunk it reaches.
 *
 * Vanilla gives {@code RenderChunk} an {@code index} of its own, but it counts
 * in a different order than the array is laid out, and it is never read. This
 * is the array position.
 *
 * <h2>Why it is stored as one more than it is</h2>
 *
 * A mixin's added field starts at zero, and zero is a real slot — the corner of
 * the grid. Storing {@code slot + 1} makes the untouched value mean "not set",
 * which matters because records the game allocates itself never pass through
 * the code that fills this in, and reading their zero as a slot would attribute
 * one chunk's state to another.
 */
public interface GridSlot {

    /** The grid position, or -1 if this chunk was never registered. */
    int vulkanmodnext$gridSlot();

    void vulkanmodnext$setGridSlot(int slot);
}
