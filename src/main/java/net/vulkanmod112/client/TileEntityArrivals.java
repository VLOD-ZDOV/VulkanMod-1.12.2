package net.vulkanmod112.client;

import net.minecraft.client.renderer.chunk.RenderChunk;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Chunks that finished building with block entities in them since the last
 * visibility search.
 *
 * The search gathers which visible sections hold block entities while it walks,
 * which is right for every section it sees — but a section already on the list
 * can finish building afterwards, and then it holds a chest the list has never
 * heard of. That window was measured rather than argued about: with the
 * shortened list checked against the full scan it replaces, the creature half
 * missed nothing at all and this half missed 186 sections over one interval
 * while a world was filling in.
 *
 * So the sections that arrive late are named here and folded in before the list
 * is handed over. Only chunks that actually carry block entities are queued,
 * which is a small minority of builds, and the search empties the queue when it
 * starts because everything before that point is in the answer it just built.
 *
 * <h2>Threading</h2>
 *
 * Every call is expected on the render thread: a chunk's compiled form is
 * installed from a callback on the upload future, and both of that future's
 * paths — uploaded inline by the client thread, or queued and run by
 * {@code runChunkUploads} — complete there. The queue is concurrent anyway,
 * because the cost of being wrong about that would be a block entity that is
 * not drawn, and the cost of the queue is nothing.
 */
public final class TileEntityArrivals {

    private static final Queue<RenderChunk> ARRIVED = new ConcurrentLinkedQueue<RenderChunk>();

    /**
     * The most that may wait at once. Reached only if nobody is draining —
     * which is what happens while the shortened lists are switched off — and
     * dropping the oldest is right there, because the next search rebuilds the
     * answer from the chunks themselves.
     */
    private static final int LIMIT = 4096;

    private TileEntityArrivals() {
    }

    /** Called when a chunk's compiled form is installed. */
    public static void arrived(RenderChunk chunk) {
        if (chunk == null) {
            return;
        }
        ARRIVED.add(chunk);
        while (ARRIVED.size() > LIMIT) {
            ARRIVED.poll();
        }
    }

    public static RenderChunk poll() {
        return ARRIVED.poll();
    }

    /** Emptied when a search starts: what it is about to build supersedes this. */
    public static void clear() {
        ARRIVED.clear();
    }
}
