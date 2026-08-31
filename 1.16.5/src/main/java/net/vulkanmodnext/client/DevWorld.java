package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.datafix.codec.DatapackCodec;
import net.minecraft.util.registry.DynamicRegistries;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.gen.settings.DimensionGeneratorSettings;
import net.minecraft.world.DimensionType;
import net.vulkanmodnext.VulkanModNext;

/**
 * Opens a world by itself, so that the port can be checked without a person
 * clicking through the menus.
 *
 * <h2>Why it is worth the code</h2>
 *
 * Nothing interesting happens in this mod until chunks are being built, and
 * chunks are only built in a world. Every check from here on — the shape of a
 * vertex, the mirror, the terrain draw, and every measurement after that —
 * needs one open. A person opening it by hand also opens a different one each
 * time, and this project has the scar for that: two builds of the same code
 * measured forty percent apart because the world was not the same world.
 *
 * <p>So the seed is fixed and named, and the save is reused once it exists.
 * The first run of a seed generates terrain and is warm-up, not a measurement —
 * that rule is inherited from the 1.12.2 side and it cost a day to learn.
 *
 * <p>Switched on with {@code -Pworld=<name>} at the dev client, and does
 * nothing at all otherwise. It is not shipped behaviour: a mod that opens a
 * world on its own would be a bug in anybody's hands but ours.
 */
public final class DevWorld {

    /** Fixed, so two runs are two runs of the same world. */
    private static final long SEED = 777L;

    private static boolean done;

    private DevWorld() {
    }

    public static void openIfAsked() {
        String name = System.getProperty("vulkanmodnext.world");
        if (done || name == null || name.isEmpty()) {
            return;
        }
        done = true;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.getLevelSource().levelExists(name)) {
                VulkanModNext.LOGGER.info("Dev world: opening the existing '{}'", name);
                mc.loadLevel(name);
                return;
            }
            VulkanModNext.LOGGER.info("Dev world: creating '{}' with seed {} — this run generates "
                    + "terrain and is warm-up, not a measurement", name, SEED);
            DynamicRegistries.Impl registries = DynamicRegistries.builtin();
            DimensionGeneratorSettings generator = new DimensionGeneratorSettings(
                    SEED, true, false,
                    DimensionGeneratorSettings.withOverworld(
                            registries.registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY),
                            DimensionType.defaultDimensions(
                                    registries.registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY),
                                    registries.registryOrThrow(Registry.BIOME_REGISTRY),
                                    registries.registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY),
                                    SEED),
                            DimensionGeneratorSettings.makeDefaultOverworld(
                                    registries.registryOrThrow(Registry.BIOME_REGISTRY),
                                    registries.registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY),
                                    SEED)));
            // Creative and peaceful: nothing should be able to kill the camera
            // in the middle of a measurement.
            WorldSettings settings = new WorldSettings(name, GameType.CREATIVE, false,
                    Difficulty.PEACEFUL, true, new GameRules(), DatapackCodec.DEFAULT);
            mc.createLevel(name, settings, registries, generator);
        } catch (Throwable failed) {
            VulkanModNext.LOGGER.error("Dev world: could not open '{}'", name, failed);
        }
    }
}
