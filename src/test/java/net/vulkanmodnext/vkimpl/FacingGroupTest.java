package net.vulkanmodnext.vkimpl;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permutation that sorts a chunk's quads by which way they face.
 *
 * Written because the picture said it was wrong and reading it said it was
 * right, twice. What the eye could see was that materials landed on the wrong
 * blocks, and a material is carried beside the geometry rather than inside it —
 * so the question is exactly whether the two are moved by the same map, and
 * that is a question about arithmetic, answerable here in a second rather than
 * in a ninety-second flight.
 */
class FacingGroupTest {

    private static final int STRIDE = VertexLayout.SOURCE_STRIDE;

    /** One vanilla quad: four vertices, a chosen normal direction and height. */
    private static void writeQuad(ByteBuffer buffer, int quad, float y, int facing, int tag) {
        // facing: +1 up, -1 down, 0 a wall. The winding is what decides it, so
        // the corners are laid out in the order the game lays them out.
        float[][] corners;
        if (facing > 0) {
            corners = new float[][] {{0, y, 0}, {0, y, 1}, {1, y, 1}, {1, y, 0}};
        } else if (facing < 0) {
            corners = new float[][] {{0, y, 0}, {1, y, 0}, {1, y, 1}, {0, y, 1}};
        } else {
            corners = new float[][] {{0, y, 0}, {0, y + 1, 0}, {1, y + 1, 0}, {1, y, 0}};
        }
        for (int c = 0; c < 4; c++) {
            int at = (quad * 4 + c) * STRIDE;
            buffer.putFloat(at, corners[c][0]);
            buffer.putFloat(at + 4, corners[c][1]);
            buffer.putFloat(at + 8, corners[c][2]);
            // Colour doubles as a tag, so a moved vertex can be recognised.
            buffer.putInt(at + 12, tag);
            buffer.putFloat(at + 16, 0.0f);
            buffer.putFloat(at + 20, 0.0f);
            buffer.putShort(at + 24, (short) 240);
            buffer.putShort(at + 26, (short) 240);
        }
    }

    /** The same walk the mirror does over the runs, so the two cannot drift. */
    private static int[] expand(int[] runs, int runCount, int vertices) {
        int[] out = new int[vertices];
        int written = 0;
        for (int r = 0; r < runCount && written < vertices; r++) {
            int end = Math.min(runs[r * 2], vertices);
            while (written < end) {
                out[written++] = runs[r * 2 + 1];
            }
        }
        return out;
    }

    @Test
    void everyQuadLandsWhereItsMaterialDoes() {
        int quads = 40;
        ByteBuffer source = MemoryUtil.memAlloc(quads * 4 * STRIDE);
        ByteBuffer packed = MemoryUtil.memAlloc(quads * 4 * VertexLayout.SOURCE_STRIDE);
        try {
            // A mix: walls, floors and ceilings scattered up the section, so the
            // shelves interleave rather than arriving already sorted.
            int[] material = new int[quads];
            for (int q = 0; q < quads; q++) {
                int facing = q % 3 == 0 ? 1 : q % 3 == 1 ? -1 : 0;
                float y = q % 16;
                writeQuad(source, q, y, facing, q);
                material[q] = q % 7;
            }
            byte[] shelf = new byte[quads];
            int[] target = new int[quads];
            int[] counts = new int[VertexLayout.COUNTS];
            assertTrue(VertexLayout.copyGrouped(MemoryUtil.memAddress(source),
                    MemoryUtil.memAddress(packed), quads * 4 * STRIDE, shelf, target, counts),
                    "a whole number of quads should group");

            // Every quad landed somewhere, and nowhere twice.
            boolean[] taken = new boolean[quads];
            for (int q = 0; q < quads; q++) {
                assertTrue(target[q] >= 0 && target[q] < quads, "target in range for quad " + q);
                assertTrue(!taken[target[q]], "two quads sent to slot " + target[q]);
                taken[target[q]] = true;
            }

            // The runs the mirror would build, permuted the way it permutes them.
            int vertices = quads * 4;
            int[] runs = new int[quads * 2 * 2];
            int runCount = 0;
            int start = 0;
            while (start < quads) {
                int value = material[start];
                int end = start + 1;
                while (end < quads && material[end] == value) {
                    end++;
                }
                runs[runCount * 2] = end * 4;
                runs[runCount * 2 + 1] = value;
                runCount++;
                start = end;
            }
            int[] before = expand(runs, runCount, vertices);
            int[] after = new int[vertices];
            for (int q = 0; q < quads; q++) {
                for (int c = 0; c < 4; c++) {
                    after[target[q] * 4 + c] = before[q * 4 + c];
                }
            }

            // The claim: the vertex now sitting at position p carries the
            // material of the quad that was moved to p.
            for (int p = 0; p < quads; p++) {
                int tag = MemoryUtil.memGetInt(MemoryUtil.memAddress(packed)
                        + (long) p * 4 * VertexLayout.stride() + colourOffset());
                assertEquals(material[tag], after[p * 4],
                        "material follows the quad it belongs to, at slot " + p);
            }
        } finally {
            MemoryUtil.memFree(source);
            MemoryUtil.memFree(packed);
        }
    }

    /** One quad facing squarely along an axis, or along none of them. */
    private static void writeFacing(ByteBuffer buffer, int quad, float y, int facing, int tag) {
        // 0 down, 1 up, 2 −X, 3 +X, 4 −Z, 5 +Z, 6 on no axis at all. The
        // winding decides the normal, so these are corner orders and nothing
        // else — get one backwards and the test says so rather than the world.
        float[][] corners;
        switch (facing) {
            case 0:
                corners = new float[][] {{0, y, 0}, {1, y, 0}, {1, y, 1}, {0, y, 1}};
                break;
            case 1:
                corners = new float[][] {{0, y, 0}, {0, y, 1}, {1, y, 1}, {1, y, 0}};
                break;
            case 2:
                corners = new float[][] {{0, y, 0}, {0, y, 1}, {0, y + 1, 1}, {0, y + 1, 0}};
                break;
            case 3:
                corners = new float[][] {{1, y, 0}, {1, y + 1, 0}, {1, y + 1, 1}, {1, y, 1}};
                break;
            case 4:
                corners = new float[][] {{0, y, 0}, {0, y + 1, 0}, {1, y + 1, 0}, {1, y, 0}};
                break;
            case 5:
                corners = new float[][] {{0, y, 1}, {1, y, 1}, {1, y + 1, 1}, {0, y + 1, 1}};
                break;
            default:
                corners = new float[][] {{0, y, 0}, {1, y, 1}, {1, y + 1, 1}, {0, y + 1, 0}};
                break;
        }
        for (int c = 0; c < 4; c++) {
            int at = (quad * 4 + c) * STRIDE;
            buffer.putFloat(at, corners[c][0]);
            buffer.putFloat(at + 4, corners[c][1]);
            buffer.putFloat(at + 8, corners[c][2]);
            buffer.putInt(at + 12, tag);
            buffer.putFloat(at + 16, 0.0f);
            buffer.putFloat(at + 20, 0.0f);
            buffer.putShort(at + 24, (short) 240);
            buffer.putShort(at + 26, (short) 240);
        }
    }

    @Test
    void sidewaysQuadsLandInTheRingInOrder() {
        int perFacing = 3;
        int quads = 7 * perFacing;
        ByteBuffer source = MemoryUtil.memAlloc(quads * 4 * STRIDE);
        ByteBuffer packed = MemoryUtil.memAlloc(quads * 4 * VertexLayout.SOURCE_STRIDE);
        try {
            // Interleaved rather than grouped, so arriving sorted cannot pass
            // for having been sorted.
            for (int q = 0; q < quads; q++) {
                writeFacing(source, q, q % 16, q % 7, q);
            }
            byte[] shelf = new byte[quads];
            int[] target = new int[quads];
            int[] counts = new int[VertexLayout.COUNTS];
            assertTrue(VertexLayout.copyGrouped(MemoryUtil.memAddress(source),
                    MemoryUtil.memAddress(packed), quads * 4 * STRIDE, shelf, target, counts),
                    "a whole number of quads should group");

            // The ring is −X, −Z, +X, +Z, and each of its four shelves holds
            // its three quads and only those.
            int[] ring = {2, 4, 3, 5};
            for (int side = 0; side < 4; side++) {
                int from = counts[VertexLayout.SIDE_TABLE + side];
                int to = counts[VertexLayout.SIDE_TABLE + side + 1];
                assertEquals(perFacing, to - from,
                        "shelf " + side + " of the ring holds its own quads");
                for (int p = from; p < to; p++) {
                    int tag = MemoryUtil.memGetInt(MemoryUtil.memAddress(packed)
                            + (long) p * 4 * VertexLayout.stride() + colourOffset());
                    assertEquals(ring[side], tag % 7,
                            "quad at slot " + p + " faces the way its shelf says");
                }
            }

            // The four shelves are consecutive, which is what lets two of them
            // be skipped as one hole.
            assertEquals(counts[VertexLayout.SIDE_TABLE] + 4 * perFacing,
                    counts[VertexLayout.SIDE_TABLE + 4],
                    "the ring is one unbroken stretch");

            // And the vertical shelves still answer as they did: a camera above
            // the section skips every down-facing quad and no side-facing one.
            assertEquals(perFacing, counts[0] / 4, "one facing down per group");
            assertEquals(perFacing, counts[1] / 4, "one facing up per group");
        } finally {
            MemoryUtil.memFree(source);
            MemoryUtil.memFree(packed);
        }
    }

    /** Where the untouched colour word sits, in whichever layout is compiled in. */
    private static int colourOffset() {
        return VertexLayout.stride() == VertexLayout.COMPACT_STRIDE ? 8 : 12;
    }

    @Test
    void downFacingQuadsComeFirstAndUpFacingLast() {
        int quads = 24;
        ByteBuffer source = MemoryUtil.memAlloc(quads * 4 * STRIDE);
        ByteBuffer packed = MemoryUtil.memAlloc(quads * 4 * VertexLayout.SOURCE_STRIDE);
        try {
            for (int q = 0; q < quads; q++) {
                int facing = q % 3 == 0 ? 1 : q % 3 == 1 ? -1 : 0;
                writeQuad(source, q, q % 16, facing, q);
            }
            byte[] shelf = new byte[quads];
            int[] target = new int[quads];
            int[] counts = new int[VertexLayout.COUNTS];
            VertexLayout.copyGrouped(MemoryUtil.memAddress(source),
                    MemoryUtil.memAddress(packed), quads * 4 * STRIDE, shelf, target, counts);
            int down = counts[0] / 4;
            int up = counts[1] / 4;
            assertEquals(8, down, "one quad in three faces down");
            assertEquals(8, up, "one quad in three faces up");
            // A camera above everything skips every down-facing quad, and one
            // below skips every up-facing one.
            assertEquals(down, counts[VertexLayout.DOWN_TABLE + 16],
                    "all down-facing quads lie below the top of the section");
            assertEquals(up, counts[VertexLayout.UP_TABLE],
                    "all up-facing quads lie at or above the bottom");
        } finally {
            MemoryUtil.memFree(source);
            MemoryUtil.memFree(packed);
        }
    }
}
