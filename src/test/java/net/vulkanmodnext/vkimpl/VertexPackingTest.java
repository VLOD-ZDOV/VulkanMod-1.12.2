package net.vulkanmodnext.vkimpl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic that turns a vertex into sixteen bytes, checked without a card.
 *
 * This exists because of what the packing is asked to promise. The claim in the
 * layout's own notes is that a face shared by two chunks lands on exactly the
 * same lattice point from both sides, so there are no hairline cracks at chunk
 * seams — and that claim was made from the arithmetic rather than from looking
 * at a world. Half of it can be settled here, at build time, on every machine,
 * without anybody flying to a chunk boundary: whether the encoding loses
 * anything at all for the coordinates the game actually produces.
 *
 * What it cannot settle is the other half — that the shader decodes it the same
 * way — so the eye checks stay. But "the numbers are exact" stops being a thing
 * somebody has to take on trust.
 */
class VertexPackingTest {

    /** The reverse of {@code position()}, exactly as the vertex shader spells it. */
    private static float decode(short packed) {
        return packed * (1.0f / VertexLayout.POSITION_SCALE) + VertexLayout.POSITION_ORIGIN;
    }

    /**
     * Vanilla builds models on a sixteenth of a block, so this is every
     * coordinate a vanilla chunk can hand over — and it must survive untouched.
     */
    @Test
    void everySixteenthOfABlockSurvivesExactly() {
        // The whole representable span: eight blocks below the section and
        // eight above it, and the top end is open — see the test below.
        for (int sixteenths = -128; sixteenths < 384; sixteenths++) {
            float value = sixteenths / 16.0f;
            float back = decode(VertexLayout.position(value));
            assertEquals(value, back, 0.0f, "coordinate " + value + " did not survive packing");
        }
    }

    /**
     * The one coordinate in the span that does not survive, named on purpose.
     *
     * A signed short reaches 32 767 upwards and 32 768 downwards, so the range
     * around the section's middle is not symmetric: the bottom end, eight blocks
     * below the section, lands exactly on the last value there is, and the top
     * end is one lattice unit — a two-thousand-and-forty-eighth of a block —
     * short of it. Nothing vanilla can produce goes anywhere near either: a
     * block model is allowed one block outside its own cube, not eight.
     *
     * Written down as a test rather than as a sentence because the next person
     * to change the scale will run this and not read that.
     */
    @Test
    void theVeryTopOfTheSpanIsOneLatticeUnitShort() {
        assertEquals(Short.MIN_VALUE, VertexLayout.position(-8.0f));
        assertEquals(Short.MAX_VALUE, VertexLayout.position(24.0f));
        float last = 24.0f - 1.0f / VertexLayout.POSITION_SCALE;
        assertEquals(last, decode(VertexLayout.position(last)), 0.0f);
    }

    /**
     * The seam itself, stated as the two chunks state it.
     *
     * A vertex on the boundary is offered by the chunk below it as 16.0 and by
     * the chunk above it as 0.0, and each is packed against its own section's
     * middle. Adding back the sixteen blocks between the two sections has to
     * land on the same number to the last bit; anything less is a crack.
     */
    @Test
    void aSharedFaceLandsOnTheSamePointFromBothSides() {
        for (int sixteenths = 0; sixteenths <= 16; sixteenths++) {
            float fromBelow = 16.0f + sixteenths / 16.0f;
            float fromAbove = sixteenths / 16.0f;
            float below = decode(VertexLayout.position(fromBelow));
            float above = decode(VertexLayout.position(fromAbove)) + 16.0f;
            assertEquals(below, above, 0.0f, "seam at " + fromAbove + " splits");
        }
    }

    /** A model may hang out of its own section; sixteen blocks of room each way. */
    @Test
    void aSectionHasHeadroomOnBothSides() {
        assertEquals(-16384, VertexLayout.position(0.0f));
        assertEquals(0, VertexLayout.position(8.0f));
        assertEquals(16384, VertexLayout.position(16.0f));
        // Eight blocks below the section is the last value a short holds, and it
        // holds it: this is the edge of the span, not past it.
        assertEquals(Short.MIN_VALUE, VertexLayout.position(-8.0f));
        assertTrue(VertexLayout.position(20.0f) < Short.MAX_VALUE,
                "four blocks above the section is well inside the span");
    }

    /** Past the headroom it clamps rather than wraps — a wrapped vertex is a spike. */
    @Test
    void beyondTheHeadroomItClampsRatherThanWraps() {
        assertEquals(Short.MAX_VALUE, VertexLayout.position(1000.0f));
        assertEquals(Short.MIN_VALUE, VertexLayout.position(-1000.0f));
    }

    /**
     * Light is claimed to be exact, not approximated: the game writes shorts and
     * never goes above 240, which is a light level times sixteen.
     */
    @Test
    void everyLightValueTheGameCanWriteIsExact() {
        for (int level = 0; level <= 15; level++) {
            int coordinate = level * 16;
            assertEquals(coordinate, VertexLayout.light(coordinate));
        }
        assertEquals(240, VertexLayout.light(240));
        assertEquals(255, VertexLayout.light(255));
    }

    /** Anything wider than a byte is counted and clamped, never wrapped to black. */
    @Test
    void lightAboveAByteClampsRatherThanWraps() {
        assertEquals(255, VertexLayout.light(256));
        assertEquals(255, VertexLayout.light(0xF000));
    }

    /**
     * The two light values ride in one short, and the shader takes them apart
     * with a shift and a mask. Packing and unpacking are written in two
     * different languages in two different files, which is the whole reason to
     * check that they agree.
     */
    @Test
    void bothLightValuesComeBackOutOfOneShort() {
        for (int first = 0; first <= 240; first += 16) {
            for (int second = 0; second <= 240; second += 16) {
                short word = (short) (VertexLayout.light(first) << 8 | VertexLayout.light(second));
                int packed = word & 0xFFFF;
                assertEquals(first, packed >> 8);
                assertEquals(second, packed & 0xFF);
            }
        }
    }

    /** The ends of the atlas have to be the ends of the range. */
    @Test
    void textureCoordinatesKeepTheirEnds() {
        assertEquals(0, VertexLayout.texture(0.0f) & 0xFFFF);
        assertEquals(65535, VertexLayout.texture(1.0f) & 0xFFFF);
    }

    /**
     * The one field that really loses something, and by how much.
     *
     * Sixteen bits over the whole atlas is a sixteenth of a texel at 4096 pixels
     * and a quarter of one at 16 384 — which is why the wide layout is kept for
     * a large atlas. The number is asserted rather than described so that
     * changing the layout has to face it.
     */
    @Test
    void textureErrorStaysUnderAQuarterOfATexelOnTheLargestSafeAtlas() {
        float texelsAcross = VertexLayout.LARGEST_SAFE_ATLAS;
        float worst = 0.0f;
        for (int i = 0; i <= 4096; i++) {
            float value = i / 4096.0f;
            float back = (VertexLayout.texture(value) & 0xFFFF) / 65535.0f;
            worst = Math.max(worst, Math.abs(back - value) * texelsAcross);
        }
        assertTrue(worst < 0.25f, "worst error was " + worst + " texels");
    }

    /** Coordinates outside the atlas clamp instead of folding to the other edge. */
    @Test
    void textureCoordinatesOutsideTheAtlasClamp() {
        assertEquals(0, VertexLayout.texture(-0.5f) & 0xFFFF);
        assertEquals(65535, VertexLayout.texture(1.5f) & 0xFFFF);
    }

    /** Sixteen bytes is what the shader's input block is written against. */
    @Test
    void theStrideIsWhatTheShaderExpects() {
        assertEquals(28, VertexLayout.SOURCE_STRIDE);
        assertEquals(16, VertexLayout.COMPACT_STRIDE);
    }
}
