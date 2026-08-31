package net.vulkanmodnext.client;

import net.minecraft.client.renderer.chunk.RenderChunk;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Chunks that finished building since the last visibility search.
 *
 * The search gathers what each visible section contains while it walks — blocks
 * for the layer passes, block entities for the pass that draws chests — and
 * that is right for every section as it sees it. But a section already on the
 * list can finish building a hundred frames later, and then it holds geometry
 * the shortened lists have never heard of. That window was measured rather than
 * argued about: with the shortened list checked against the full scan it
 * replaces, the creature half missed nothing at all and the block-entity half
 * missed 186 sections over one interval while a world was filling in.
 *
 * So the sections that arrive late are named here and folded in before either
 * list is handed over. Only chunks that ended up with something in them are
 * queued, and the search empties the queue when it starts, because everything
 * before that point is in the answer it just built.
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
public final class CompiledArrivals {

    private static final Queue<RenderChunk> ARRIVED = new ConcurrentLinkedQueue<RenderChunk>();

    /**
     * The most that may wait at once. Reached only if nobody is draining —
     * which is what happens while the shortened lists are switched off — and
     * dropping the oldest is right there, because the next search rebuilds the
     * answer from the chunks themselves.
     */
    private static final int LIMIT = 4096;

    private CompiledArrivals() {
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
