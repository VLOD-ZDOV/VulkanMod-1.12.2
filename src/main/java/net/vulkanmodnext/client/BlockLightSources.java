package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;

/**
 * The light-emitting blocks near the camera — torches, lava, glowstone, a fire.
 *
 * <h2>Why these are wanted when the game already lights them</h2>
 *
 * Vanilla's own block light is a flood fill through air: it reaches around
 * corners correctly and it is completely flat, because it is a number per block
 * with no idea where the light came from. A torch on a wall and a torch on the
 * floor light a room identically. What this renderer can now do — trace a ray
 * from a surface to a source — turns that number back into a direction and a
 * shadow, and doing it to a carried torch was the first thing anybody noticed.
 * Doing it to the torches that are already there is the same trick applied to
 * the ninety-nine percent of light in a world that is not being carried.
 *
 * <h2>Why this is a scan and not a subscription</h2>
 *
 * The game tells nobody when a light-emitting block is placed in a way this
 * could listen to cheaply, and the alternative — asking the world what is in
 * every block near the camera, every frame — is a lookup per block per frame
 * for an answer that changes when somebody places a torch. So it is scanned on
 * a slow clock and when the camera has moved far enough to be looking at
 * different blocks. Placing a torch lights the room within a fraction of a
 * second rather than within a frame, which nobody can see.
 *
 * <h2>Three things that make a fifty-block radius affordable</h2>
 *
 * A hundred and one blocks across is a million blocks, and asking the world
 * about a million blocks four times a second is not a search, it is a stall.
 * The volume is cut down before it is ever looked at:
 *
 * <ol>
 * <li><b>Sections, not blocks.</b> The world stores blocks in sixteen-cubed
 *     sections and knows which of them contain nothing but air. Above ground
 *     that is most of them, and each one skipped is four thousand blocks not
 *     read.</li>
 * <li><b>The game's own block-light array is the filter.</b> A block that emits
 *     light necessarily has block light at its own position — that is what
 *     emitting means. The nibble array holding it is two thousand bytes a
 *     section, read straight rather than a nibble at a time, and outdoors in
 *     daylight it is all zeroes: sunlight is stored separately. So a section is
 *     dismissed by two thousand byte comparisons instead of four thousand block
 *     lookups, and only the handful of positions that already have light are
 *     asked what block is there.</li>
 * <li><b>Spread over frames.</b> Even reduced, a full sweep is work, and doing
 *     it in one frame is a hitch four times a second. A fixed number of
 *     sections is done per frame, nearest first, and the result is published
 *     only when the sweep finishes — so the list never contains half a world.
 *     Nearest first also means running out of room stops the sweep instead of
 *     filling it with whatever came first.</li>
 * </ol>
 */
public final class BlockLightSources {

    /**
     * How many sources fit. Wider searches find more, and the shader only ever
     * takes the nearest thirty-two, but the ones dropped here are dropped
     * before distance has been compared — so this is kept well above what the
     * shader wants rather than at it.
     */
    private static final int CAPACITY = 512;

    /** Four numbers a source: position relative to the camera, then its level. */
    private static float[] sources = new float[CAPACITY * 4];
    private static int count;

    private static double lastX = Double.NaN;
    private static double lastY;
    private static double lastZ;
    private static long lastScanNanos;

    /** Far enough that the set of blocks in range has meaningfully changed. */
    private static final double MOVED = 2.0;
    /** How often to look again while standing still, in nanoseconds. */
    private static final long INTERVAL_NANOS = 250_000_000L;

    /**
     * Sections visited per frame while a sweep is running.
     *
     * At fifty blocks a sweep is a few hundred sections, so this finishes one
     * in something like a fifth of a second — the same order as the interval
     * between sweeps, and slower than that would mean the list never settles.
     */
    private static final int SECTIONS_PER_FRAME = 24;

    /** The sweep in progress: sections still to visit, nearest first. */
    private static long[] queue = new long[512];
    private static int queueLength;
    private static int queueAt;
    private static boolean sweeping;

    /** Where the camera was when this sweep started; its results are relative to that. */
    private static double buildX;
    private static double buildY;
    private static double buildZ;
    private static int buildRadius;
    private static float[] build = new float[CAPACITY * 4];
    private static int buildCount;

    private static long sweepsFinished;
    private static long sectionsVisited;
    private static long sectionsSkipped;
    private static long blocksRead;

    private BlockLightSources() {
    }

    public static int count() {
        return count;
    }

    public static float[] values() {
        return sources;
    }

    /**
     * Advances the search, and leaves the published list alone until a sweep
     * finishes.
     *
     * Positions are stored relative to the camera at the moment the sweep
     * started and corrected for where the camera is now, so a list a fraction
     * of a second old is still in the right place — it is only missing blocks
     * that were placed or broken since.
     */
    public static void update(double viewX, double viewY, double viewZ, int radius) {
        if (radius <= 0) {
            count = 0;
            sweeping = false;
            return;
        }
        shift(viewX, viewY, viewZ);
        long now = System.nanoTime();
        if (!sweeping) {
            boolean moved = Double.isNaN(lastX)
                    || Math.abs(viewX - lastX) > MOVED
                    || Math.abs(viewY - lastY) > MOVED
                    || Math.abs(viewZ - lastZ) > MOVED;
            if (!moved && now - lastScanNanos < INTERVAL_NANOS) {
                return;
            }
            if (!beginSweep(viewX, viewY, viewZ, radius)) {
                return;
            }
            lastScanNanos = now;
        } else if (radius != buildRadius) {
            // The setting moved under a running sweep. Its queue covers the old
            // volume, and finishing it would publish a list that answers the
            // question nobody is asking any more.
            if (!beginSweep(viewX, viewY, viewZ, radius)) {
                sweeping = false;
                return;
            }
            lastScanNanos = now;
        }
        stepSweep(viewX, viewY, viewZ);
    }

    /**
     * Moves the stored positions to follow the camera between sweeps.
     *
     * They are kept relative to the camera because that is what the shader
     * wants, and the camera moves every frame while this list is refreshed a
     * few times a second.
     */
    private static void shift(double viewX, double viewY, double viewZ) {
        if (Double.isNaN(lastX)) {
            return;
        }
        float dx = (float) (lastX - viewX);
        float dy = (float) (lastY - viewY);
        float dz = (float) (lastZ - viewZ);
        if (dx == 0.0f && dy == 0.0f && dz == 0.0f) {
            return;
        }
        for (int i = 0; i < count; i++) {
            int base = i * 4;
            sources[base] += dx;
            sources[base + 1] += dy;
            sources[base + 2] += dz;
        }
        lastX = viewX;
        lastY = viewY;
        lastZ = viewZ;
    }

    /**
     * Lists the sections the sweep will visit, nearest first.
     *
     * Ordering is not decoration. The sweep stops when the list fills up, and
     * without an order that would mean keeping whichever corner of the volume
     * the loop happened to reach first while the torch two blocks away was
     * never looked at.
     */
    private static boolean beginSweep(double viewX, double viewY, double viewZ, int radius) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null) {
            return false;
        }
        int centreX = (int) Math.floor(viewX);
        int centreY = (int) Math.floor(viewY);
        int centreZ = (int) Math.floor(viewZ);
        int minSectionX = (centreX - radius) >> 4;
        int maxSectionX = (centreX + radius) >> 4;
        int minSectionZ = (centreZ - radius) >> 4;
        int maxSectionZ = (centreZ + radius) >> 4;
        int minSectionY = Math.max(0, (centreY - radius) >> 4);
        int maxSectionY = Math.min(15, (centreY + radius) >> 4);

        int needed = (maxSectionX - minSectionX + 1)
                * (maxSectionZ - minSectionZ + 1)
                * (maxSectionY - minSectionY + 1);
        if (needed <= 0) {
            return false;
        }
        if (queue.length < needed) {
            queue = new long[needed];
        }
        int centreSectionX = centreX >> 4;
        int centreSectionZ = centreZ >> 4;
        queueLength = 0;
        for (int sx = minSectionX; sx <= maxSectionX; sx++) {
            for (int sz = minSectionZ; sz <= maxSectionZ; sz++) {
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    double dx = (sx << 4) + 8 - viewX;
                    double dy = (sy << 4) + 8 - viewY;
                    double dz = (sz << 4) + 8 - viewZ;
                    long distance = (long) (dx * dx + dy * dy + dz * dz);
                    // Section coordinates relative to the middle one, so the
                    // whole entry fits beneath the distance that sorts it.
                    long payload = ((sx - centreSectionX + 64) & 0x7FL) << 11
                            | ((sz - centreSectionZ + 64) & 0x7FL) << 4
                            | (sy & 0xFL);
                    queue[queueLength++] = (distance << 32) | payload;
                }
            }
        }
        Arrays.sort(queue, 0, queueLength);
        queueAt = 0;
        buildCount = 0;
        buildX = viewX;
        buildY = viewY;
        buildZ = viewZ;
        buildRadius = radius;
        sweeping = true;
        return true;
    }

    /** Visits this frame's share of the queue, and publishes when it runs out. */
    private static void stepSweep(double viewX, double viewY, double viewZ) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null) {
            sweeping = false;
            return;
        }
        int centreSectionX = ((int) Math.floor(buildX)) >> 4;
        int centreSectionZ = ((int) Math.floor(buildZ)) >> 4;
        double radiusSq = (double) buildRadius * buildRadius;
        int budget = SECTIONS_PER_FRAME;
        while (budget-- > 0 && queueAt < queueLength) {
            long entry = queue[queueAt++];
            int sx = (int) ((entry >>> 11) & 0x7FL) - 64 + centreSectionX;
            int sz = (int) ((entry >>> 4) & 0x7FL) - 64 + centreSectionZ;
            int sy = (int) (entry & 0xFL);
            if (!scanSection(world, sx, sy, sz, radiusSq)) {
                // Out of room, and everything left is farther away.
                queueAt = queueLength;
                break;
            }
        }
        if (queueAt >= queueLength) {
            publish(viewX, viewY, viewZ);
        }
    }

    /** @return false when the list is full and the sweep should stop */
    private static boolean scanSection(World world, int sectionX, int sectionY, int sectionZ,
            double radiusSq) {
        Chunk chunk;
        try {
            // The loaded one only. Asking for a chunk that is not there would
            // have the client generate or request it, and a lighting search is
            // not a reason to load the world.
            chunk = world.getChunkProvider().getLoadedChunk(sectionX, sectionZ);
        } catch (Throwable ignored) {
            return true;
        }
        if (chunk == null) {
            sectionsSkipped++;
            return true;
        }
        ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
        if (sectionY < 0 || sectionY >= storages.length) {
            return true;
        }
        // Vanilla's own name for the absent section is a null constant, so this
        // is the emptiness check and not defensiveness.
        ExtendedBlockStorage storage = storages[sectionY];
        if (storage == null || storage.isEmpty()) {
            sectionsSkipped++;
            return true;
        }
        NibbleArray blockLight = storage.getBlockLight();
        if (blockLight == null) {
            sectionsSkipped++;
            return true;
        }
        byte[] packed = blockLight.getData();
        sectionsVisited++;
        int baseX = sectionX << 4;
        int baseY = sectionY << 4;
        int baseZ = sectionZ << 4;
        // Two positions to a byte, and the overwhelming majority of bytes are
        // zero — in daylight above ground, all of them. This loop is the search.
        for (int i = 0; i < packed.length; i++) {
            byte both = packed[i];
            if (both == 0) {
                continue;
            }
            int index = i << 1;
            if ((both & 0x0F) != 0 && !offerBlock(storage, baseX, baseY, baseZ, index, radiusSq)) {
                return false;
            }
            if ((both & 0xF0) != 0
                    && !offerBlock(storage, baseX, baseY, baseZ, index + 1, radiusSq)) {
                return false;
            }
        }
        return true;
    }

    /** @return false when the list is full */
    private static boolean offerBlock(ExtendedBlockStorage storage, int baseX, int baseY,
            int baseZ, int index, double radiusSq) {
        int localX = index & 15;
        int localZ = (index >> 4) & 15;
        int localY = (index >> 8) & 15;
        // The middle of the block, which is where a torch's flame is close
        // enough to and where a lava surface averages out.
        double dx = baseX + localX + 0.5 - buildX;
        double dy = baseY + localY + 0.5 - buildY;
        double dz = baseZ + localZ + 0.5 - buildZ;
        if (dx * dx + dy * dy + dz * dz > radiusSq) {
            return true;
        }
        blocksRead++;
        int level;
        try {
            level = storage.get(localX, localY, localZ).getLightValue();
        } catch (Throwable ignored) {
            // A section that changed underneath the sweep.
            return true;
        }
        if (level <= 0) {
            return true;
        }
        if (buildCount >= CAPACITY) {
            return false;
        }
        int base = buildCount++ * 4;
        build[base] = (float) dx;
        build[base + 1] = (float) dy;
        build[base + 2] = (float) dz;
        build[base + 3] = level;
        return true;
    }

    /** Swaps the finished sweep in and brings it up to where the camera is now. */
    private static void publish(double viewX, double viewY, double viewZ) {
        float[] swap = sources;
        sources = build;
        build = swap;
        count = buildCount;
        lastX = buildX;
        lastY = buildY;
        lastZ = buildZ;
        sweeping = false;
        sweepsFinished++;
        shift(viewX, viewY, viewZ);
    }

    public static String stats() {
        return "block light sources: " + count + " in range, " + sweepsFinished + " sweeps, "
                + sectionsVisited + " sections read, " + sectionsSkipped + " skipped, "
                + blocksRead + " blocks read";
    }
}
