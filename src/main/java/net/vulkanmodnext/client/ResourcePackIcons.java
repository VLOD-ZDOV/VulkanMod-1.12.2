package net.vulkanmodnext.client;

/**
 * How much work the resource pack screen is not doing any more.
 *
 * The count that matters is the one on the left: every redraw served from the
 * cache is a {@code pack.png} vanilla would have inflated and decoded again for
 * a texture it already had. Reading it beside the cost of the decodes that did
 * happen turns "the tab lags" into a number, in the one session where the
 * screen is open — there is nothing to compare against afterwards, because the
 * work simply stops existing.
 *
 * Touched only from the client thread while a GUI is drawing, so plain fields
 * are enough; a shared counter would cost more than what it measures.
 */
public final class ResourcePackIcons {

    private static long served;
    private static long decodes;
    private static long decodeNanos;

    private ResourcePackIcons() {
    }

    /** A redraw that found the icon already uploaded. */
    public static void served() {
        served++;
    }

    /** A redraw that had to read and decode the image. */
    public static void decoded(long nanos) {
        decodes++;
        decodeNanos += nanos;
    }

    public static boolean touched() {
        return served != 0L || decodes != 0L;
    }

    /**
     * The saving is the cache hits priced at what a decode actually costs on
     * this machine, rather than at a number from somewhere else.
     */
    public static String describe() {
        if (decodes == 0L) {
            return "resource pack icons: " + served + " redraws served from cache, none decoded yet";
        }
        double avgMs = decodeNanos / 1_000_000.0 / decodes;
        return String.format(
                "resource pack icons: %d decoded (%.1f ms each), %d redraws served from cache "
                        + "— about %.0f ms of decoding vanilla would have repeated",
                decodes, avgMs, served, served * avgMs);
    }
}
