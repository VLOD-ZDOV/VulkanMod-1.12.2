package net.vulkanmodnext.client;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.vulkanmodnext.VulkanModNext;
import net.vulkanmodnext.mixin.BufferBuilderAccess;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a built chunk actually contains, checked against what we believe it
 * contains.
 *
 * <h2>Why this is the first thing the port does with a chunk</h2>
 *
 * Everything downstream — the mirror, the vertex layout, the packing, the
 * facing groups — is written against one belief about the shape of a vertex.
 * On 1.12.2 that shape is 28 bytes and carries no normal. Reading 1.16.5's
 * declaration says it should be <b>32</b> bytes with a normal in it, and that
 * difference is not a detail: a stride that is wrong by four bytes produces a
 * world of spikes rather than an error, and this project has already spent a
 * day on exactly that.
 *
 * <p>So the belief is written down here as a number, and the first chunks the
 * game builds are measured against it. The check says which of the two it is —
 * "the format is not what we expect" and "the format is what we expect and the
 * numbers in it are nonsense" need different work.
 */
public final class ChunkGeometry {

    /** POSITION 12 + COLOR 4 + UV0 8 + UV2 4 + NORMAL 3 + PADDING 1. */
    private static final int EXPECTED_STRIDE = 32;

    /** Only the first few: this is a check, not a running cost. */
    private static final int CHUNKS_TO_CHECK = 3;

    private static final AtomicInteger checked = new AtomicInteger();

    /** How many layers to see before saying whether the mirror is taking them. */
    private static final int ANNOUNCE_AFTER = 200;

    private static final AtomicInteger mirrored = new AtomicInteger();
    private static final AtomicInteger refused = new AtomicInteger();
    private static final AtomicLong mirroredBytes = new AtomicLong();
    private static volatile boolean announced;
    private static volatile boolean complained;

    private ChunkGeometry() {
    }

    /**
     * Called on a chunk builder thread, before the game uploads the layer.
     *
     * Nothing here may take the geometry or move a cursor: the upload that
     * follows is the game's, and it reads the same bytes.
     */
    public static void offer(int slot, BufferBuilder builder) {
        try {
            BufferBuilderAccess access = (BufferBuilderAccess) builder;
            List<BufferBuilder.DrawState> states = access.vulkanmodnext$drawStates();
            int next = access.vulkanmodnext$nextDrawState();
            if (states == null || next >= states.size()) {
                return;
            }
            BufferBuilder.DrawState state = states.get(next);
            int vertices = state.vertexCount();
            int stride = state.format().getVertexSize();
            if (vertices == 0) {
                return;
            }
            if (checked.get() < CHUNKS_TO_CHECK) {
                describe(access, state);
            }

            int at = access.vulkanmodnext$uploadedBytes();
            int length = vertices * stride;
            ByteBuffer whole = access.vulkanmodnext$buffer();
            if (at < 0 || length <= 0 || at + length > whole.capacity()) {
                return;
            }
            // A view, not a copy, and never the buffer itself: the upload that
            // follows this hook reads the same object, and moving its position
            // would hand the game a chunk that starts in the middle of itself.
            ByteBuffer layer = whole.duplicate();
            layer.order(ByteOrder.LITTLE_ENDIAN);
            layer.position(at).limit(at + length);

            if (ChunkMirror.onWorkerBuild(slot, layer)) {
                mirrored.incrementAndGet();
                mirroredBytes.addAndGet(length);
            } else {
                refused.incrementAndGet();
            }
            announceOnce();
        } catch (Throwable failed) {
            // A copy must never be the thing that breaks a chunk: whatever
            // happens here, the game still uploads the same geometry it always
            // did.
            if (!complained) {
                complained = true;
                VulkanModNext.LOGGER.warn("Could not mirror a built chunk, the game is unaffected",
                        failed);
            }
        }
    }

    /**
     * Says once, out loud, whether the mirror is actually receiving anything.
     *
     * A count that is quietly zero is the failure this port is most likely to
     * have and least likely to notice: everything starts, nothing is mirrored,
     * and the log looks the same as a working one.
     */
    private static void announceOnce() {
        if (announced) {
            return;
        }
        int done = mirrored.get();
        int missed = refused.get();
        if (done + missed < ANNOUNCE_AFTER) {
            return;
        }
        announced = true;
        if (done == 0) {
            VulkanModNext.LOGGER.warn("Chunk mirror: {} layers offered and none taken — the "
                    + "geometry is being read but Vulkan is not keeping it", missed);
            return;
        }
        VulkanModNext.LOGGER.info("Chunk mirror: {} of {} layers copied into Vulkan, {} KiB",
                done, done + missed, mirroredBytes.get() / 1024);
    }

    private static void describe(BufferBuilderAccess access, BufferBuilder.DrawState state) {
        VertexFormat format = state.format();
        int vertices = state.vertexCount();
        int stride = format.getVertexSize();
        if (checked.getAndIncrement() >= CHUNKS_TO_CHECK) {
            return;
        }

        StringBuilder elements = new StringBuilder();
        for (VertexFormatElement element : format.getElements()) {
            if (elements.length() != 0) {
                elements.append(" + ");
            }
            elements.append(element.getUsage()).append(':')
                    .append(element.getElementCount()).append('x').append(element.getType());
        }

        boolean asExpected = stride == EXPECTED_STRIDE && format == DefaultVertexFormats.BLOCK;
        String verdict = asExpected ? "as expected"
                : "NOT what the port is written for (expected " + EXPECTED_STRIDE
                        + " bytes of DefaultVertexFormats.BLOCK)";

        ByteBuffer bytes = access.vulkanmodnext$buffer().duplicate();
        bytes.order(ByteOrder.LITTLE_ENDIAN);
        int at = access.vulkanmodnext$uploadedBytes();

        VulkanModNext.LOGGER.info("Chunk layer: {} vertices, {} bytes each — {}. Layout: {}",
                vertices, stride, verdict, elements);
        if (stride >= EXPECTED_STRIDE && at + stride <= bytes.capacity()) {
            // The first vertex, read the way the renderer would read it. A
            // position outside a sixteen-block cube is the clearest sign that
            // the stride or the offsets are wrong, and it costs one line to see.
            VulkanModNext.LOGGER.info(
                    "  first vertex: position ({}, {}, {}), colour {}, uv ({}, {}), light {}/{}",
                    bytes.getFloat(at), bytes.getFloat(at + 4), bytes.getFloat(at + 8),
                    String.format("%08X", bytes.getInt(at + 12)),
                    String.format("%.4f", bytes.getFloat(at + 16)),
                    String.format("%.4f", bytes.getFloat(at + 20)),
                    bytes.getShort(at + 24) & 0xFFFF, bytes.getShort(at + 26) & 0xFFFF);
        }
    }
}
