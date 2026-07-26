package net.vulkanmod112.client;

/**
 * Implemented on VertexBuffer by the mirror mixin so the draw path can ask a
 * chunk's buffer where its mirror entry lives without a lookup.
 *
 * Declared outside the mixin package because {@link TerrainHooks} casts to it
 * on the hot path, and the game-side classes should not have to reach into
 * mixin internals to do that.
 */
public interface VertexBufferSlot {

    /** The mirror slot, or {@link ChunkSlots#UNASSIGNED} if never uploaded. */
    int vulkanmod112$slot();
}
