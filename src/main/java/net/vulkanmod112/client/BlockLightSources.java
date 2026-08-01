package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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
 * every block near the camera, every frame — is fifteen thousand lookups a
 * frame for an answer that changes when somebody places a torch. So it is
 * scanned on a slow clock and when the camera has moved far enough to be
 * looking at different blocks. Placing a torch lights the room within a
 * fraction of a second rather than within a frame, which nobody can see and
 * which costs a hundredth of what the honest version would.
 */
public final class BlockLightSources {

    /** Four numbers a source: position relative to the camera, then its level. */
    private static float[] sources = new float[256 * 4];
    private static int count;

    private static double lastX = Double.NaN;
    private static double lastY;
    private static double lastZ;
    private static long lastScanNanos;
    private static long scans;
    private static long blocksRead;

    /** Far enough that the set of blocks in range has meaningfully changed. */
    private static final double MOVED = 2.0;
    /** How often to look again while standing still, in nanoseconds. */
    private static final long INTERVAL_NANOS = 250_000_000L;

    private BlockLightSources() {
    }

    public static int count() {
        return count;
    }

    public static float[] values() {
        return sources;
    }

    /**
     * Refreshes the list if it is stale, and leaves it alone otherwise.
     *
     * Positions are stored relative to the camera at the moment of the scan and
     * corrected for where the camera is now, so a list a quarter of a second old
     * is still in the right place — it is only missing blocks that were placed
     * or broken since.
     */
    public static void update(double viewX, double viewY, double viewZ, int radius) {
        if (radius <= 0) {
            count = 0;
            return;
        }
        long now = System.nanoTime();
        boolean moved = Double.isNaN(lastX)
                || Math.abs(viewX - lastX) > MOVED
                || Math.abs(viewY - lastY) > MOVED
                || Math.abs(viewZ - lastZ) > MOVED;
        if (!moved && now - lastScanNanos < INTERVAL_NANOS) {
            shift(viewX, viewY, viewZ);
            return;
        }
        scan(viewX, viewY, viewZ, radius);
        lastX = viewX;
        lastY = viewY;
        lastZ = viewZ;
        lastScanNanos = now;
    }

    /**
     * Moves the stored positions to follow the camera between scans.
     *
     * They are kept relative to the camera because that is what the shader
     * wants, and the camera moves every frame while this list is refreshed four
     * times a second.
     */
    private static void shift(double viewX, double viewY, double viewZ) {
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

    private static void scan(double viewX, double viewY, double viewZ, int radius) {
        count = 0;
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null) {
            return;
        }
        scans++;
        int centreX = (int) Math.floor(viewX);
        int centreY = (int) Math.floor(viewY);
        int centreZ = (int) Math.floor(viewZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int capacity = sources.length / 4;
        for (int y = centreY - radius; y <= centreY + radius; y++) {
            if (y < 0 || y > 255) {
                continue;
            }
            for (int x = centreX - radius; x <= centreX + radius; x++) {
                for (int z = centreZ - radius; z <= centreZ + radius; z++) {
                    blocksRead++;
                    pos.setPos(x, y, z);
                    int level;
                    try {
                        level = world.getBlockState(pos).getLightValue();
                    } catch (Throwable ignored) {
                        // A chunk that went away underneath the scan.
                        continue;
                    }
                    if (level <= 0) {
                        continue;
                    }
                    if (count >= capacity) {
                        return;
                    }
                    int base = count++ * 4;
                    // The middle of the block, which is where a torch's flame
                    // is close enough to and where a lava surface averages out.
                    sources[base] = (float) (x + 0.5 - viewX);
                    sources[base + 1] = (float) (y + 0.5 - viewY);
                    sources[base + 2] = (float) (z + 0.5 - viewZ);
                    sources[base + 3] = level;
                }
            }
        }
    }

    public static String stats() {
        return "block light sources: " + count + " in range, " + scans + " scans, "
                + blocksRead + " blocks read";
    }
}
