package net.vulkanmodnext.client;

/**
 * Implemented on VertexBuffer by the mirror mixin so the draw path can ask a
 * chunk's buffer where its mirror entry lives without a lookup.
 *
 * Declared outside the mixin package because {@link TerrainHooks} casts to it
 * on the hot path, and the game-side classes should not have to reach into
 * mixin internals to do that.
 */
public interface VertexBufferSlot {

    /** The mirror slot, or {@link ChunkSlots#UNASSIGNED} if none yet. */
    int vulkanmodnext$slot();

    /**
     * The mirror slot, assigning one if this buffer has none.
     *
     * Callable from the render thread and from the chunk builder threads, so
     * it has to be atomic: a plain check-then-assign would let two threads
     * take a slot each for the same buffer and leak one of them.
     */
    int vulkanmodnext$slotOrAssign();
}
