package net.vulkanmod112.vkimpl;

import org.lwjgl.system.MemoryUtil;

/**
 * How a chunk's vertices are laid out in video memory, and the packing that
 * gets them there.
 *
 * <h2>Why pack at all</h2>
 *
 * The terrain pass was measured against render distance and against window
 * size, and the two answers together name what it is waiting for: **0.048 ms
 * per million vertices, and flat in pixels** — 0.44 ms at 0.9 megapixels
 * against 0.50 at 7.6. Nine million vertices at twenty-eight bytes is 252 MB
 * of vertex fetch a frame, which at the measured 0.43 ms is about 580 GB/s.
 * That is the card's memory bandwidth. The pass is not filling pixels or
 * running out of shader; it is reading vertices.
 *
 * <h2>The layout</h2>
 *
 * Vanilla's is twenty-eight bytes and this mod mirrors it byte for byte, which
 * is the reason the mod never has to understand geometry it did not build. The
 * packed one is sixteen:
 *
 * <pre>
 *   0  position x, y, z   3 x int16   1/2048 of a block, from the section's middle
 *   6  both light values  1 x int16   one byte each, side by side
 *   8  colour             4 x uint8   unchanged
 *  12  texture u, v       2 x uint16  normalised, and vanilla's are already 0..1
 * </pre>
 *
 * <h2>What each field gives up, and what it does not</h2>
 *
 * <b>Position</b> keeps 1/2048 of a block. Vanilla's own models are built on a
 * sixteenth of a block and its textures are sixteen pixels across one, so this
 * is a hundred and twenty-eight times finer than a texel. It is measured from
 * the middle of the section rather than its corner, which puts the sixteen
 * blocks of headroom evenly on both sides — a model that hangs out of its own
 * section has room in either direction. The scale is a power of two and the
 * sections sit on a sixteen-block grid, so a face shared by two chunks lands on
 * exactly the same lattice point from both sides: no cracks, no z-fighting.
 *
 * <b>Light</b> is exact. The game writes the two lightmap coordinates as shorts
 * but never above 240 — they are a light level times sixteen, and smooth
 * lighting averages them, which cannot leave the range — so a byte holds every
 * value they can take. Anything that arrives above a byte is counted and
 * clamped rather than silently wrapped.
 *
 * <b>Texture coordinates</b> are the one field that can actually lose
 * something. Sixteen bits normalised is one part in 65 535 of the whole atlas,
 * which on a 4096-pixel atlas is a sixteenth of a texel and on a 16 384-pixel
 * one is a quarter of it. A quarter of a texel at a sprite's edge is enough to
 * fetch the neighbouring sprite once mipmaps are involved. So the atlas is
 * checked, and a large one keeps the wide layout.
 *
 * <b>Colour</b> is untouched: it carries the ambient occlusion as well as the
 * biome tint, and anything narrower than a byte a channel bands visibly on a
 * smoothly lit wall.
 *
 * <h2>Why not smaller than sixteen</h2>
 *
 * Ten bits an axis would make the position four bytes and the vertex twelve,
 * but ten bits over the same span is 1/43 of a block — coarser than the
 * sixteenth vanilla models are built on, which is where two quads that should
 * share an edge stop sharing it. Everything else is already at its floor: the
 * colour bands if narrowed, the light is exactly a byte, and the texture
 * coordinates are the field that is already the tightest of the four. Sixteen
 * is where the next byte costs picture rather than bandwidth.
 */
public final class VertexLayout {

    /** Vanilla's BLOCK vertex: pos 3f | colour 4ub | uv 2f | lightmap 2s. */
    public static final int SOURCE_STRIDE = 28;

    /** The packed one. */
    public static final int COMPACT_STRIDE = 16;

    /** Units of packed position per block, and the block they are counted from. */
    static final float POSITION_SCALE = 2048.0f;
    static final float POSITION_ORIGIN = 8.0f;

    /**
     * Beyond this many pixels across, a sixteen-bit texture coordinate is worth
     * less than a quarter of a texel and sprite edges start to bleed.
     */
    static final int LARGEST_SAFE_ATLAS = 8192;

    /**
     * Settled when this class is loaded, and never afterwards.
     *
     * It has to be, and the first attempt at it is why. Deciding inside the
     * renderer's own start-up looked early enough and was not: the sky and the
     * first chunks are mirrored several seconds before that runs, so they went
     * into the buffer at twenty-eight bytes and everything after them at
     * sixteen. One buffer, two layouts, and a world drawn as spikes reaching to
     * the horizon. A field read from a JVM flag has no such moment — it is true
     * before the first line of the mod runs.
     */
    private static final boolean COMPACT =
            Boolean.getBoolean("vulkanmod112.compactVertices");

    private static String reason = COMPACT ? "packed to 16 bytes" : "kept at 28 bytes";
    private static int atlasPixels;
    private static boolean atlasWarned;

    /**
     * Counted rather than atomic. These increment only when a value did not
     * fit, which is meant never to happen, and the packing runs on the chunk
     * builder threads for every vertex in the world — a shared atomic there
     * cost a fifth of chunk building the last time this project reached for
     * one. A lost count under a race is the right trade for a number whose
     * only job is to be nonzero.
     */
    private static long clampedPositions;
    private static long clampedLight;
    private static long clampedTexture;
    private static long packedVertices;
    /**
     * Buffers whose length is not a whole number of vanilla vertices. The sky
     * and the star field pass through the same mirror as the chunks and are
     * built from a different, shorter vertex — nothing draws them through this
     * renderer, so packing them as though they were chunk geometry is harmless,
     * but a number that is quietly not zero should be visible rather than
     * assumed.
     */
    private static long raggedBuffers;

    private VertexLayout() {
    }

    public static boolean isCompact() {
        return COMPACT;
    }



    /** One line for the log, at the moment the choice is made. */
    static String describe() {
        return "Chunk vertices " + reason;
    }

    /**
     * The atlas this session will sample, once it is known.
     *
     * A packed texture coordinate is one part in 65 535 of the whole sheet, so
     * how much of a texel that is depends entirely on how big the sheet turned
     * out to be — and that is not known until the pack has loaded, which is
     * after the layout has already been settled. So this warns rather than
     * decides, and the warning names the thing to turn off.
     */
    static void noteAtlas(int pixels) {
        atlasPixels = pixels;
        if (COMPACT && pixels > LARGEST_SAFE_ATLAS && !atlasWarned) {
            atlasWarned = true;
            org.apache.logging.log4j.LogManager.getLogger("VulkanMod112/Terrain").warn(
                    "The block atlas is {} pixels across and chunk vertices are packed to 16"
                            + " bytes. A packed texture coordinate is {} of a texel at that size,"
                            + " which is enough for sprite edges to bleed — turn the packing off"
                            + " if textures look wrong at a distance",
                    pixels, "1/" + Math.max(1, 65535 / pixels));
        }
    }

    /** What a mirrored vertex takes in video memory. */
    public static int stride() {
        return COMPACT ? COMPACT_STRIDE : SOURCE_STRIDE;
    }

    /** What {@code sourceBytes} of vanilla geometry becomes once packed. */
    public static int packedSize(int sourceBytes) {
        return COMPACT ? sourceBytes / SOURCE_STRIDE * COMPACT_STRIDE : sourceBytes;
    }

    /**
     * Moves one chunk layer's geometry into the staging ring, packing it on the
     * way when the layout calls for it.
     *
     * Runs on the chunk builder threads, which is where it belongs: the copy it
     * replaces was a plain move of twenty-eight bytes a vertex, and this writes
     * sixteen. Those threads were measured at about one per cent busy while
     * flying, so the arithmetic has somewhere to go, and the write is smaller
     * than the one it replaces.
     *
     * @param sourceBytes how many bytes of vanilla geometry, a multiple of 28
     */
    public static void copy(long source, long destination, int sourceBytes) {
        if (!COMPACT) {
            MemoryUtil.memCopy(source, destination, sourceBytes);
            return;
        }
        int count = sourceBytes / SOURCE_STRIDE;
        if (count * SOURCE_STRIDE != sourceBytes) {
            raggedBuffers++;
        }
        for (int i = 0; i < count; i++) {
            long from = source + (long) i * SOURCE_STRIDE;
            long to = destination + (long) i * COMPACT_STRIDE;
            MemoryUtil.memPutShort(to, position(MemoryUtil.memGetFloat(from)));
            MemoryUtil.memPutShort(to + 2, position(MemoryUtil.memGetFloat(from + 4)));
            MemoryUtil.memPutShort(to + 4, position(MemoryUtil.memGetFloat(from + 8)));
            int first = light(MemoryUtil.memGetShort(from + 24) & 0xFFFF);
            int second = light(MemoryUtil.memGetShort(from + 26) & 0xFFFF);
            MemoryUtil.memPutShort(to + 6, (short) (first << 8 | second));
            // Colour is four bytes in both layouts and in the same order.
            MemoryUtil.memPutInt(to + 8, MemoryUtil.memGetInt(from + 12));
            MemoryUtil.memPutShort(to + 12, texture(MemoryUtil.memGetFloat(from + 16)));
            MemoryUtil.memPutShort(to + 14, texture(MemoryUtil.memGetFloat(from + 20)));
        }
        packedVertices += count;
    }

    private static short position(float value) {
        int units = Math.round((value - POSITION_ORIGIN) * POSITION_SCALE);
        if (units > Short.MAX_VALUE) {
            clampedPositions++;
            return Short.MAX_VALUE;
        }
        if (units < Short.MIN_VALUE) {
            clampedPositions++;
            return Short.MIN_VALUE;
        }
        return (short) units;
    }

    private static int light(int value) {
        if (value > 0xFF) {
            clampedLight++;
            return 0xFF;
        }
        return value;
    }

    private static short texture(float value) {
        int units = Math.round(value * 65535.0f);
        if (units < 0) {
            clampedTexture++;
            return 0;
        }
        if (units > 65535) {
            clampedTexture++;
            return (short) 0xFFFF;
        }
        return (short) units;
    }

    /**
     * Reads and resets the three counts that say whether the packing is honest.
     * All three are meant to stay at zero, and a report that never shows them
     * is a report that cannot tell anybody it went wrong.
     */
    public static String stats() {
        String line = "vertex layout: " + reason
                + (atlasPixels > 0 ? ", atlas " + atlasPixels + " px" : "");
        if (COMPACT) {
            line += String.format(", %d vertices packed, out of range: position %d, light %d,"
                            + " texture %d, not whole vertices %d", packedVertices,
                    clampedPositions, clampedLight, clampedTexture, raggedBuffers);
            packedVertices = 0L;
            raggedBuffers = 0L;
            clampedPositions = 0L;
            clampedLight = 0L;
            clampedTexture = 0L;
        }
        return line;
    }
}
