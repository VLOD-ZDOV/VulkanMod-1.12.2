package net.vulkanmodnext.client;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;

/**
 * How much of a slow moment was the virtual machine rather than this mod.
 *
 * <h2>Why this exists</h2>
 *
 * Every stall this renderer has chased was assumed to be its own until proven
 * otherwise, and the proving took days each time. The game runs on a virtual
 * machine with a garbage collector that stops every thread when it feels like
 * it, which is the one explanation that fits a stall appearing once in a
 * thousand frames on an unchanged scene — and it is the one nothing here ever
 * measured. A frame or a chunk build that took a hundred milliseconds while the
 * collector was running is not a defect in this mod, and telling those apart
 * costs two counter reads.
 *
 * <h2>Two questions, not one</h2>
 *
 * The collector answers "was the machine stopped". Thread processor time
 * answers the broader one: was this thread <em>running</em> at all. Wall time
 * far above processor time means the thread was waiting — on a lock, on the
 * operating system's scheduler, on a page of memory being fetched back — and
 * which of those it was matters far less than the fact that it was not
 * computing. That distinction is what separates "the meshing is slow" from
 * "the meshing was waiting", and no amount of reading the meshing code answers
 * it.
 *
 * <h2>Cost</h2>
 *
 * Both readings are counters the machine already maintains. Reading them is a
 * native call of well under a microsecond, taken twice per frame and twice per
 * chunk build — a few milliseconds across a whole session, against the tens of
 * milliseconds a single unexplained stall costs to look into.
 */
public final class JvmPauses {

    private static final List<GarbageCollectorMXBean> COLLECTORS =
            ManagementFactory.getGarbageCollectorMXBeans();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    /**
     * Whether this machine will say how long a thread actually ran.
     *
     * Optional in the specification and present on every ordinary desktop
     * virtual machine, but asked rather than assumed: where it is missing the
     * measurement simply reports nothing instead of reporting a zero that would
     * read as "the thread never ran".
     */
    private static final boolean CPU_TIME_READABLE = readable();

    private JvmPauses() {
    }

    private static boolean readable() {
        try {
            return THREADS != null && THREADS.isCurrentThreadCpuTimeSupported()
                    && THREADS.isThreadCpuTimeEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Milliseconds the collectors have spent stopping the world, since start. */
    public static long collectionMillis() {
        long total = 0L;
        for (int i = 0; i < COLLECTORS.size(); i++) {
            long time = COLLECTORS.get(i).getCollectionTime();
            if (time > 0L) {
                total += time;
            }
        }
        return total;
    }

    /** How many times they have done it, which catches a pause too short to round to a millisecond. */
    public static long collections() {
        long total = 0L;
        for (int i = 0; i < COLLECTORS.size(); i++) {
            long count = COLLECTORS.get(i).getCollectionCount();
            if (count > 0L) {
                total += count;
            }
        }
        return total;
    }

    public static boolean cpuTimeReadable() {
        return CPU_TIME_READABLE;
    }

    /** Nanoseconds this thread has spent on a processor, or -1 where unknown. */
    public static long threadCpuNanos() {
        if (!CPU_TIME_READABLE) {
            return -1L;
        }
        try {
            return THREADS.getCurrentThreadCpuTime();
        } catch (Throwable ignored) {
            return -1L;
        }
    }
}
