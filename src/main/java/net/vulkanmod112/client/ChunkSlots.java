package net.vulkanmod112.client;

/**
 * Hands every mirrored VertexBuffer a small dense index of its own.
 *
 * The mirror used to be keyed by the buffer's GL name, which forced a hash
 * table: GL names are assigned by the driver, are not dense, and at render
 * distance 64 there are hundreds of thousands of them. Every chunk of every
 * drawn layer cost a lookup that landed somewhere unrelated in memory.
 *
 * A slot is dense by construction, so the mirror can be a plain array and the
 * lookup becomes an index. Slots are recycled when a buffer is deleted, which
 * keeps the array roughly the size of the live chunk grid instead of growing
 * with every rebuild the session has ever done.
 *
 * Keyed on the VertexBuffer rather than on the GL name for a second reason:
 * the object exists from the moment the chunk is created, while the GL name
 * only exists once the render thread uploads. That is what will let the mirror
 * copy move off the render thread later.
 */
public final class ChunkSlots {

    /** Not yet mirrored. Callers must treat this as "no entry". */
    public static final int UNASSIGNED = -1;

    private static int next;
    private static int[] free = new int[256];
    private static int freeCount;

    private ChunkSlots() {
    }

    public static synchronized int allocate() {
        if (freeCount > 0) {
            return free[--freeCount];
        }
        return next++;
    }

    public static synchronized void release(int slot) {
        if (slot < 0) {
            return;
        }
        if (freeCount == free.length) {
            int[] grown = new int[free.length * 2];
            System.arraycopy(free, 0, grown, 0, free.length);
            free = grown;
        }
        free[freeCount++] = slot;
    }

    /** How many slots have ever been handed out; the mirror sizes itself by this. */
    public static synchronized int highWaterMark() {
        return next;
    }
}
