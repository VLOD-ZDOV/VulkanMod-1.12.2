package net.vulkanmod112.client;

/**
 * How many particles one tick's explosions may ask for.
 *
 * <h2>What this is about</h2>
 *
 * A tonne of TNT is not slow because of the explosion. The server works out
 * what breaks and sends the client a list of positions; the client then walks
 * that list and asks for <b>two</b> particles per destroyed block — a puff and
 * a smoke — and every one of them is an object that is then ticked, sorted and
 * drawn for its whole life. Two hundred charges of a hundred blocks each is
 * forty thousand particles born in one tick, and the frame they are born in is
 * the one that stops.
 *
 * <h2>Why a budget and not a switch</h2>
 *
 * Turning explosion particles off makes a blast look like a block edit. What
 * costs the frame is the count, and the count is what carries almost none of
 * the impression: the first few hundred puffs are the explosion, and the rest
 * are inside them.
 *
 * So the first {@code limit} of a tick are kept whole, and past that one in
 * eight — thinned rather than cut off, because a hard stop puts the missing
 * particles all in one place and leaves a hole where the blast was biggest.
 *
 * <h2>Client only</h2>
 *
 * Nothing here changes what breaks, what drops, or what the server thinks
 * happened. The blocks are already gone before this is asked.
 */
public final class ExplosionParticles {

    /** Asked for and kept this tick, and the same totals for the report. */
    private static int asked;
    private static int kept;
    private static long askedTotal;
    private static long keptTotal;
    private static int worstTick;

    private ExplosionParticles() {
    }

    /**
     * @return whether this particle should be spawned at all
     */
    public static boolean allow() {
        asked++;
        boolean allowed = keep(asked, VulkanConfig.getExplosionParticles());
        if (allowed) {
            kept++;
        }
        return allowed;
    }

    /**
     * The rule itself, with the counting and the settings taken away.
     *
     * Separate so it can be checked against its own description without a
     * client: that a limit of zero keeps everything, that the first
     * {@code limit} of a tick are kept whole, and that past the limit exactly
     * one in eight survives. The last of those is the part that decides
     * whether a blast looks thinned or looks holed, and it is one bitwise and
     * away from being wrong in a way nobody would see until a screenshot.
     *
     * @param askedSoFar which request this is within the tick, counting from 1
     * @param limit      how many are kept whole; 0 or less is no limit at all
     */
    static boolean keep(int askedSoFar, int limit) {
        if (limit <= 0) {
            return true;
        }
        return askedSoFar <= limit || (askedSoFar & 7) == 0;
    }

    /** Called once per client tick, before anything can explode in it. */
    public static void newTick() {
        if (asked > worstTick) {
            worstTick = asked;
        }
        askedTotal += asked;
        keptTotal += kept;
        asked = 0;
        kept = 0;
    }

    /** Read and reset, for the diagnostics report. */
    public static String stats() {
        if (askedTotal == 0) {
            return "explosion particles: nothing exploded since the last report";
        }
        String line = String.format(
                "explosion particles: %d asked for, %d spawned, worst single tick %d (limit %d)",
                askedTotal, keptTotal, worstTick,
                VulkanConfig.getExplosionParticles());
        askedTotal = 0;
        keptTotal = 0;
        worstTick = 0;
        return line;
    }
}
