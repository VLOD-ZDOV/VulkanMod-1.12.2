package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;

/**
 * Named starting points for the settings screen.
 *
 * A preset is a one-shot write, not a mode: it sets a group of options and
 * then stops existing, so anything changed afterwards simply stays changed.
 * That is why the rows are buttons rather than a selector — a selector would
 * keep claiming a preset is active after it stopped being true.
 *
 * Only the vanilla settings that cost real frames are touched, and each one is
 * saved through GameSettings so the game reacts the way it does on its own
 * options screen.
 */
public final class VulkanPresets {

    private VulkanPresets() {
    }

    /** Everything back to the shipped values; vanilla settings untouched. */
    public static void stable() {
        VulkanConfig.resetToDefaults();
    }

    /** Caps the draw distances that vanilla leaves far wider than anyone can see. */
    public static void balanced(Minecraft mc) {
        VulkanConfig.setTerrainEnabled(true);
        VulkanConfig.setEntityDistance(128);
        VulkanConfig.setTileEntityDistance(64);
        VulkanConfig.setBackgroundFpsLimit(10);
        VulkanConfig.setAnimationsEnabled(true);
        VulkanConfig.setDepthBlitEnabled(true);
        VulkanConfig.setCullingEnabled(true);
        VulkanConfig.setFramesInFlight(2);
        VulkanConfig.setGeometryBudgetMiB(0);
        mc.gameSettings.particleSetting = 1;
        mc.gameSettings.saveOptions();
    }

    /** Trades looks for frames: shorter distances, no animation, fewer particles. */
    public static void performance(Minecraft mc) {
        VulkanConfig.setTerrainEnabled(true);
        VulkanConfig.setEntityDistance(64);
        VulkanConfig.setTileEntityDistance(32);
        VulkanConfig.setBackgroundFpsLimit(5);
        VulkanConfig.setAnimationsEnabled(false);
        VulkanConfig.setDepthBlitEnabled(true);
        VulkanConfig.setCullingEnabled(true);
        // A third frame in flight gives the CPU more room when it is the
        // bottleneck, which is what this preset assumes.
        VulkanConfig.setFramesInFlight(3);
        VulkanConfig.setGeometryBudgetMiB(0);
        // Chunk building is what the frame waits for at long render distances,
        // and vanilla sizes that thread pool from the heap rather than from the
        // CPU. A preset named for performance is the right place to take the
        // core count seriously; the shipped default still leaves it alone.
        VulkanConfig.setChunkBuildThreads(VulkanConfig.coresForChunkBuilding());
        mc.gameSettings.particleSetting = 2;
        mc.gameSettings.entityShadows = false;
        boolean wasFancy = mc.gameSettings.fancyGraphics;
        mc.gameSettings.fancyGraphics = false;
        mc.gameSettings.saveOptions();
        if (wasFancy) {
            // Graphics quality is baked into chunk geometry, so it only takes
            // effect once the chunks are rebuilt.
            mc.renderGlobal.loadRenderers();
        }
    }
}
