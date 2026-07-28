package net.vulkanmod112.client;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * Finds the things in the world that ought to be giving off light, and hands
 * them to the terrain shader.
 *
 * The obvious way to do this is vanilla's own: raise the light level stored in
 * the world and let the chunk be rebuilt. That is what makes dynamic lighting
 * expensive, and this renderer has the measurement to say how expensive —
 * chunk rebuilding is what the frame is waiting on while the player moves, 330
 * fps against 120 at render distance 64. A torch carried at walking pace would
 * rebuild chunks continuously, which is the one thing not to spend a frame on.
 *
 * So nothing is rebuilt. The light sources are collected here each frame,
 * handed over as positions relative to the camera, and the terrain shader adds
 * their contribution while it is already shading the pixel. What it costs is
 * arithmetic on fragments that were going to be shaded anyway.
 *
 * The cost of collecting them is the loop below, and it is bounded twice: by a
 * radius, and by a hard cap on how many are passed on. Entities are scanned
 * straight off the world's own list rather than through a query that would
 * allocate a list of its own every frame.
 */
public final class DynamicLights {

    /** Positions and levels; four floats each, matching the shader's vec4. */
    public static final int MAX_LIGHTS = 32;
    private static final float[] LIGHTS = new float[MAX_LIGHTS * 4];
    private static int count;

    /**
     * How far from the camera a source is still worth carrying.
     *
     * This was 24 blocks, on the reasoning that a level 15 light reaches 15 and
     * anything further contributes nothing. That reasoning is wrong, and the
     * symptom was plain: fly away from a torch on the ground and the pool of
     * light around it goes out, while the torch is still in plain sight. A
     * light illuminates the ground around *itself*, and that ground stays
     * visible from as far away as the render distance allows. What the radius
     * has to cover is not how far the light reaches but how far the lit ground
     * can be seen from.
     */


    /** Where the camera was when the list was filled; the stored positions are relative to it. */
    private static double originX;
    private static double originY;
    private static double originZ;

    /**
     * What the first-person light map hook actually did, recorded where it
     * happens rather than inferred from somewhere else.
     *
     * The held torch model stays dark, and the number reached for as evidence
     * was "level at camera", which is computed here by a separate call and says
     * only that a source was found nearby. It cannot say whether the hook ran
     * or what it returned, and treating it as if it could is how a wrong
     * conclusion gets built on a real number.
     */
    private static long heldRaised;
    private static long heldUnchanged;
    private static int heldLastBefore = -1;
    private static int heldLastAfter = -1;

    /**
     * Times {@code ItemRenderer.setLightmap} was entered at all.
     *
     * The redirect inside it reported never having run — not "ran and changed
     * nothing", never. That leaves two possibilities which need opposite
     * fixes: the method is not being called, or the redirect did not attach to
     * the call inside it. Counting the method separately tells them apart.
     */
    private static long heldMethodCalls;

    public static void recordHeldItemLightmapCall() {
        heldMethodCalls++;
    }

    public static void recordHeldItemLight(int before, int after) {
        heldLastBefore = before;
        heldLastAfter = after;
        if (after != before) {
            heldRaised++;
        } else {
            heldUnchanged++;
        }
    }

    private static long gathered;
    private static long scanned;
    private static long frames;

    private DynamicLights() {
    }

    public static float[] lights() {
        return LIGHTS;
    }

    public static int count() {
        return count;
    }

    /**
     * Collects the light sources near the camera, in camera-relative
     * coordinates — the same space the terrain shader works in, so nothing has
     * to be transformed on the way through.
     */
    public static void gather(double viewX, double viewY, double viewZ) {
        count = 0;
        originX = viewX;
        originY = viewY;
        originZ = viewZ;
        if (!VulkanConfig.isDynamicLights()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            return;
        }
        frames++;
        // Read once per frame: a setting change takes effect on the next frame
        // rather than partway through a list.
        double radius = VulkanConfig.getDynamicLightDistance();
        double radiusSq = radius * radius;
        List<Entity> entities = mc.world.loadedEntityList;
        int size = entities.size();
        scanned += size;
        // With a radius this wide the cap can be reached, and taking whichever
        // sources happen to come first in the world's list would make lights
        // wink in and out as entities are added and removed. The farthest is
        // dropped instead, so what survives is the nearest.
        double farthestSq = -1.0;
        int farthestIndex = -1;
        for (int i = 0; i < size; i++) {
            Entity entity = entities.get(i);
            if (entity == null) {
                continue;
            }
            double dx = entity.posX - viewX;
            double dy = entity.posY - viewY;
            double dz = entity.posZ - viewZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > radiusSq) {
                continue;
            }
            int level = lightLevel(entity);
            if (level <= 0) {
                continue;
            }
            int slot;
            if (count < MAX_LIGHTS) {
                slot = count++;
            } else {
                if (farthestIndex < 0) {
                    for (int j = 0; j < count; j++) {
                        double d = distanceSqOf(j);
                        if (d > farthestSq) {
                            farthestSq = d;
                            farthestIndex = j;
                        }
                    }
                }
                if (distSq >= farthestSq) {
                    continue;
                }
                slot = farthestIndex;
                farthestIndex = -1;
                farthestSq = -1.0;
            }
            int base = slot * 4;
            LIGHTS[base] = (float) dx;
            // Entity positions are at the feet; a carried light belongs at
            // roughly eye height, and a dropped one just above the ground.
            LIGHTS[base + 1] = (float) (dy + (entity instanceof EntityItem ? 0.2 : entity.getEyeHeight() * 0.75));
            LIGHTS[base + 2] = (float) dz;
            LIGHTS[base + 3] = level;
        }
        gathered += count;
    }

    /** Squared distance from the camera of a source already in the list. */
    private static double distanceSqOf(int index) {
        int base = index * 4;
        double x = LIGHTS[base];
        double y = LIGHTS[base + 1];
        double z = LIGHTS[base + 2];
        return x * x + y * y + z * z;
    }

    /**
     * The block light level a point receives from the collected sources, 0-15.
     *
     * The same falloff the shader applies, so a mob standing in the pool of
     * light from a dropped torch is lit to match the ground it stands on.
     */
    public static int levelAt(double x, double y, double z) {
        if (count == 0) {
            return 0;
        }
        float best = 0.0f;
        double dx0 = x - originX;
        double dy0 = y - originY;
        double dz0 = z - originZ;
        for (int i = 0; i < count; i++) {
            int base = i * 4;
            double dx = LIGHTS[base] - dx0;
            double dy = LIGHTS[base + 1] - dy0;
            double dz = LIGHTS[base + 2] - dz0;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            float level = (float) (LIGHTS[base + 3] - distance);
            if (level > best) {
                best = level;
            }
        }
        return best <= 0.0f ? 0 : (int) Math.min(15.0f, best);
    }

    /**
     * Raises the block-light half of a packed light map coordinate to account
     * for the collected sources.
     *
     * The game packs these as {@code sky << 20 | block << 4}, and hands the
     * same shape out from several places that have nothing else in common:
     * entities ask for their own, particles ask for theirs, and the first
     * person item renderer asks the world directly without going through the
     * player at all. Each of those is a separate hook, and this is what they
     * share, so the bit arithmetic lives here once rather than three times.
     *
     * @return the coordinate unchanged when nothing here is brighter
     */
    public static int applyTo(int packed, double x, double y, double z) {
        if (count == 0 || !VulkanConfig.isDynamicLights()) {
            return packed;
        }
        int dynamic = levelAt(x, y, z);
        if (dynamic <= 0) {
            return packed;
        }
        int block = (packed >> 4) & 0xF;
        return dynamic <= block ? packed : (packed & ~0xF0) | (dynamic << 4);
    }

    /** 0 when the entity emits nothing. */
    private static int lightLevel(Entity entity) {
        if (entity.isBurning()) {
            return 15;
        }
        // A creeper lit with flint and steel is not on fire — it is primed, and
        // isBurning() is false for it. It flashes white and is about to
        // explode, which reads as a light source to anyone looking at it.
        //
        // hasIgnited(), not getCreeperState(). The latter is the swell, which
        // vanilla switches on and off as the player moves in and out of range,
        // so a light keyed to it blinks. Ignition is set once, by the flint and
        // steel, and stays set.
        if (entity instanceof net.minecraft.entity.monster.EntityCreeper
                && ((net.minecraft.entity.monster.EntityCreeper) entity).hasIgnited()) {
            return 10;
        }
        if (entity instanceof EntityItem) {
            return stackLight(((EntityItem) entity).getItem());
        }
        if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            return Math.max(stackLight(living.getHeldItemMainhand()),
                    stackLight(living.getHeldItemOffhand()));
        }
        return 0;
    }

    /**
     * How much light the block form of an item gives off.
     *
     * Deliberately the block's own value rather than a table of special cases:
     * a modded glowing block carried in the hand then lights the way without
     * this having to know it exists.
     */
    private static int stackLight(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        Item item = stack.getItem();
        Block block = Block.getBlockFromItem(item);
        if (block == null || block == net.minecraft.init.Blocks.AIR) {
            return 0;
        }
        try {
            return block.getDefaultState().getLightValue();
        } catch (Throwable t) {
            // A block that cannot describe its own default state is not worth
            // failing a frame over.
            return 0;
        }
    }

    /** Read and reset, for the diagnostics report. */
    public static String stats() {
        if (!VulkanConfig.isDynamicLights()) {
            return "dynamic lights: off";
        }
        if (frames == 0) {
            return "dynamic lights: on, no frames yet";
        }
        // The level at the camera itself is what a carried torch produces, and
        // it is the number to look at when the held item or the hand is not
        // being lit: if it is high here and the item is still dark, the light
        // is being found and something downstream is dropping it.
        String line = String.format(
                "dynamic lights: %.1f sources per frame from %.0f entities scanned over %d frames, "
                        + "level at camera %d",
                gathered / (double) frames, scanned / (double) frames, frames,
                levelAt(originX, originY, originZ))
                + String.format("; setLightmap entered %d, hook raised %d, unchanged %d, "
                        + "last %d -> %d (block light %d -> %d)",
                        heldMethodCalls, heldRaised, heldUnchanged, heldLastBefore, heldLastAfter,
                        heldLastBefore < 0 ? -1 : (heldLastBefore >> 4) & 0xF,
                        heldLastAfter < 0 ? -1 : (heldLastAfter >> 4) & 0xF);
        gathered = 0L;
        scanned = 0L;
        frames = 0L;
        heldRaised = 0L;
        heldUnchanged = 0L;
        heldMethodCalls = 0L;
        return line;
    }
}
