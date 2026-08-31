package net.vulkanmodnext.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the world pass actually spends its time, in the game's own words.
 *
 * <h2>Why this exists</h2>
 *
 * After the entity passes were shortened, the frame at thirty-two chunks came to
 * about 1.9 ms and everything this mod had ever timed accounted for half of it.
 * The other half was not slow, unknown or suspected — it was simply never
 * measured, and a plan built on the measured half would keep shaving the part
 * that is already small.
 *
 * <h2>What it found</h2>
 *
 * At thirty-two chunks the world pass is 0.83 ms of a 2.0 ms frame, and more
 * than half of the rest is one thing: the game asking the driver for errors,
 * twice a frame, which cannot answer until the card has caught up. That figure
 * tracks the number of pixels in the window and almost nothing else — 0.33 ms
 * at 1280x720, 0.45 at 1920x1080, 1.05 at 3673x2066 — so this renderer's frame
 * is roughly 0.7 ms of processor work plus a tenth of a millisecond per
 * megapixel. The game's own renderer, measured the same way, barely moves with
 * the window at all: it is held back by how many separate draws it makes.
 *
 * That is the whole trade this mod makes, in one pair of measurements, and none
 * of it was visible before these clocks existed.
 *
 * <h2>Why it needs no marks of its own</h2>
 *
 * The game already divides that method into named phases for its own profiler —
 * clear, camera, culling, sky, terrain, entities, particles, weather,
 * translucent, hand, clouds — and it names them whether or not anybody is
 * profiling, because the calls that do the naming are unconditional and only
 * their bodies are switched off. So the boundaries exist; all that was missing
 * was a clock at them. That is twenty-odd readings a frame, against the eighty
 * thousand a previous attempt at this cost, and it labels itself.
 */
public final class FramePhases {

    private static final Map<String, long[]> TOTALS = new LinkedHashMap<String, long[]>();

    private static String open;
    private static long since;
    private static long passes;
    private static long total;

    private FramePhases() {
    }

    /**
     * A phase boundary: closes the one that was running and opens the next.
     *
     * @param name what the game calls the phase that starts here
     */
    public static void mark(String name) {
        long now = System.nanoTime();
        if (open != null) {
            add(open, now - since);
        }
        open = name;
        since = now;
    }

    /** The end of one pass over the world. */
    public static void endPass() {
        if (open != null) {
            add(open, System.nanoTime() - since);
            open = null;
        }
        passes++;
    }

    private static void add(String name, long nanos) {
        long[] slot = TOTALS.get(name);
        if (slot == null) {
            slot = new long[1];
            TOTALS.put(name, slot);
        }
        slot[0] += nanos;
        total += nanos;
    }

    private static final Map<String, long[]> LOOP = new LinkedHashMap<String, long[]>();
    private static long loopFrames;
    private static long loopNanos;
    private static long lastLoopEntry;

    /**
     * The top of the game loop, which is where a frame is measured from.
     *
     * The gap between two of these is the whole frame — everything the game
     * does on its own thread and everything the driver makes it wait for — so
     * it is the number the named parts below have to add up to. What they do
     * not add up to is the answer this was built to get at.
     */
    public static void loopFrame() {
        long now = System.nanoTime();
        if (lastLoopEntry != 0L) {
            loopNanos += now - lastLoopEntry;
            loopFrames++;
        }
        lastLoopEntry = now;
    }

    /** One named part of the game loop, timed by whoever called it. */
    public static void addLoop(String name, long nanos) {
        long[] slot = LOOP.get(name);
        if (slot == null) {
            slot = new long[1];
            LOOP.put(name, slot);
        }
        slot[0] += nanos;
    }

    /**
     * Reads and resets. The last figure is the one that matters: what the frame
     * cost minus everything named, which is where to look next.
     */
    public static String loopStats() {
        if (loopFrames == 0) {
            return "game loop: not timed";
        }
        StringBuilder line = new StringBuilder(String.format(
                "game loop: %.2f ms per frame over %d frames —",
                loopNanos / 1_000_000.0 / loopFrames, loopFrames));
        long named = 0L;
        boolean first = true;
        for (Map.Entry<String, long[]> entry : sorted(LOOP)) {
            named += entry.getValue()[0];
            line.append(first ? " " : ", ");
            line.append(String.format("%s %.2f", entry.getKey(),
                    entry.getValue()[0] / 1_000_000.0 / loopFrames));
            first = false;
        }
        line.append(String.format(", everything else %.2f",
                (loopNanos - named) / 1_000_000.0 / loopFrames));
        LOOP.clear();
        loopFrames = 0L;
        loopNanos = 0L;
        return line.toString();
    }

    private static List<Map.Entry<String, long[]>> sorted(Map<String, long[]> from) {
        List<Map.Entry<String, long[]>> entries =
                new ArrayList<Map.Entry<String, long[]>>(from.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, long[]>>() {
            @Override
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[0], a.getValue()[0]);
            }
        });
        return entries;
    }

    /**
     * Reads and resets. Ordered by cost rather than by the order the phases run
     * in, because the question this answers is which of them to look at.
     */
    public static String stats() {
        if (passes == 0) {
            return "world pass: not timed";
        }
        List<Map.Entry<String, long[]>> entries = sorted(TOTALS);
        StringBuilder line = new StringBuilder(String.format(
                "world pass: %.2f ms per pass over %d passes —", total / 1_000_000.0 / passes,
                passes));
        int shown = 0;
        for (Map.Entry<String, long[]> entry : entries) {
            double millis = entry.getValue()[0] / 1_000_000.0 / passes;
            if (shown == 12 || millis < 0.005) {
                break;
            }
            line.append(shown == 0 ? " " : ", ");
            line.append(String.format("%s %.2f", entry.getKey(), millis));
            shown++;
        }
        if (shown == 0) {
            line.append(" nothing over 0.005 ms");
        }
        TOTALS.clear();
        passes = 0L;
        total = 0L;
        return line.toString();
    }
}
