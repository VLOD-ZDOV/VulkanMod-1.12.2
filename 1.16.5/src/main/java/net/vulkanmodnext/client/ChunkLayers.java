package net.vulkanmodnext.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.vertex.VertexBuffer;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Which layer a chunk buffer belongs to.
 *
 * <p>A map rather than a field on the buffer because it is asked rarely — once
 * per chunk buffer, ever — and answered often enough not to matter. Weak keys:
 * a chunk buffer that the game has dropped must not be kept alive by our
 * bookkeeping, and there are hundreds of thousands of them over a session.
 *
 * <p>The answer is a boolean rather than the layer itself, because that is the
 * whole of what the mirror does with it: the translucent layer is sorted, the
 * other four are not.
 */
public final class ChunkLayers {

    private static final Map<VertexBuffer, Boolean> TRANSLUCENT =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ChunkLayers() {
    }

    public static void note(VertexBuffer buffer, RenderType layer) {
        if (buffer != null) {
            TRANSLUCENT.put(buffer, layer == RenderType.translucent());
        }
    }

    /**
     * @return true when this buffer holds the translucent layer. Unknown reads
     *         as false, which is the safe direction: an unsorted chunk draws
     *         whole, where sorting one that should not be is wasted work on
     *         every rebuild.
     */
    public static boolean isTranslucent(VertexBuffer buffer) {
        return Boolean.TRUE.equals(TRANSLUCENT.get(buffer));
    }

    /**
     * Whether this buffer belongs to a chunk at all.
     *
     * <h2>Why the mirror has to ask</h2>
     *
     * {@code VertexBuffer} is not only used for chunks. The sky, the stars and
     * the clouds are drawn from ones of their own, with vertex formats of their
     * own — the sky is three floats a vertex where a chunk is thirty-two bytes.
     * Mirroring those means reading a position out of what is somebody else's
     * texture coordinate.
     *
     * <p>It showed up as a number rather than a crash: the packer counted
     * thirty-seven thousand positions, thirty-four thousand light values and
     * forty-one thousand texture coordinates outside the range they are
     * supposed to occupy, and the picture lost half its colours. Nothing threw.
     *
     * <p>A chunk buffer is one the game asked for by layer, which is exactly
     * what this map records — so the answer is already here.
     */
    public static boolean isChunkBuffer(VertexBuffer buffer) {
        return TRANSLUCENT.containsKey(buffer);
    }
}
