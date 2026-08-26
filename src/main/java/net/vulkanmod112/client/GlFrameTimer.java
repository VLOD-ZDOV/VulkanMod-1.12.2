package net.vulkanmod112.client;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;

/**
 * What the card spends on each part of a frame, as opposed to what the thread
 * spends waiting to find out.
 *
 * <h2>Why a second timer exists at all</h2>
 *
 * The renderer half of this mod already has one, and it cannot be borrowed for
 * two separate reasons. The halves are loaded by different classloaders and
 * share nothing but system properties; and they are not even written against
 * the same library — the renderer is on LWJGL 3 and the game is on LWJGL 2,
 * where the same calls live in differently named classes and some of them take
 * different arguments. So this is a small copy on the game's side of that line,
 * written against the library that side actually has.
 *
 * <h2>Why timestamps and not elapsed-time queries</h2>
 *
 * The renderer's version brackets a section with {@code GL_TIME_ELAPSED}, which
 * is exact and cannot nest — only one such query may be running at a time, and
 * the composite already has one open inside the very frame this needs to
 * measure. Timestamps have no such rule: each is a single point written into
 * the command stream, and the parts of the frame are the gaps between them. Five
 * a frame, read back three frames later so nothing ever waits for the card.
 */
public final class GlFrameTimer {

    /** The points a frame is divided at. The gaps between them are what is reported. */
    public static final int START = 0;
    public static final int AFTER_CLEAR = 1;
    public static final int AFTER_WORLD = 2;
    public static final int AFTER_BLIT = 3;
    public static final int AFTER_PRESENT = 4;

    private static final int MARKS = 5;
    private static final int DEPTH = 4;

    private static final String[] GAPS = {
        "clearing the screen", "world and interface", "framebuffer to window", "presenting",
    };

    private static final int[] QUERIES = new int[MARKS * DEPTH];
    private static final boolean[] PENDING = new boolean[DEPTH];
    private static final long[] TOTALS = new long[GAPS.length];

    private static boolean unavailable;
    private static int writing;
    private static int marked;
    private static long frames;

    private GlFrameTimer() {
    }

    /**
     * Writes one boundary into the command stream.
     *
     * Marks have to arrive in order and all of them or none: a frame that
     * marked three of five would report gaps that are not the ones named. A
     * frame arriving out of order is dropped rather than reported wrongly.
     */
    public static void mark(int which) {
        if (unavailable || !VulkanConfig.isUltraLogEnabled()) {
            return;
        }
        if (!ready()) {
            return;
        }
        if (which == START) {
            collect();
            if (PENDING[writing]) {
                // The oldest frame in the ring has still not answered. Timing
                // this one would mean throwing that answer away.
                marked = -1;
                return;
            }
            marked = 0;
        } else if (marked != which - 1) {
            marked = -1;
            return;
        } else {
            marked = which;
        }
        GL33.glQueryCounter(QUERIES[writing * MARKS + which], GL33.GL_TIMESTAMP);
        if (which == AFTER_PRESENT) {
            PENDING[writing] = true;
            writing = (writing + 1) % DEPTH;
            marked = -1;
        }
    }

    private static boolean ready() {
        if (QUERIES[0] != 0) {
            return true;
        }
        if (!GLContext.getCapabilities().OpenGL33) {
            unavailable = true;
            return false;
        }
        for (int i = 0; i < QUERIES.length; i++) {
            QUERIES[i] = GL15.glGenQueries();
        }
        return true;
    }

    private static void collect() {
        for (int frame = 0; frame < DEPTH; frame++) {
            if (!PENDING[frame]) {
                continue;
            }
            int last = frame * MARKS + AFTER_PRESENT;
            if (GL15.glGetQueryObjecti(QUERIES[last], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                continue;
            }
            long previous = GL33.glGetQueryObjecti64(QUERIES[frame * MARKS], GL15.GL_QUERY_RESULT);
            for (int gap = 0; gap < GAPS.length; gap++) {
                long now = GL33.glGetQueryObjecti64(QUERIES[frame * MARKS + gap + 1],
                        GL15.GL_QUERY_RESULT);
                TOTALS[gap] += now - previous;
                previous = now;
            }
            PENDING[frame] = false;
            frames++;
        }
    }

    /** Reads and resets. Milliseconds of card time per frame, by part. */
    public static String stats() {
        if (unavailable) {
            return "card by part: the driver will not count for us";
        }
        if (frames == 0) {
            return "card by part: no frame has answered yet";
        }
        StringBuilder line = new StringBuilder(String.format("card by part over %d frames:",
                frames));
        long total = 0L;
        for (int gap = 0; gap < GAPS.length; gap++) {
            total += TOTALS[gap];
            line.append(gap == 0 ? " " : ", ");
            line.append(String.format("%s %.3f", GAPS[gap], TOTALS[gap] / 1_000_000.0 / frames));
            TOTALS[gap] = 0L;
        }
        line.append(String.format(" — %.3f ms in all", total / 1_000_000.0 / frames));
        frames = 0L;
        return line.toString();
    }
}
