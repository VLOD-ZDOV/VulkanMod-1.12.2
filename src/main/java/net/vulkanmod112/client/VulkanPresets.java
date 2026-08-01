package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;

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

    /**
     * For a machine that cannot run this game well, on the assumption that
     * sixty frames is the goal and everything else is negotiable.
     *
     * The other presets tune; this one gives things up. It is separate from
     * Performance because the two answer different questions: Performance asks
     * what can be spared to go faster on a capable machine, this one asks what
     * has to go for the game to be playable at all. On a laptop with shared
     * memory and a sixty-hertz screen, frames above sixty are not a gain — they
     * are heat and fan noise for pictures nobody sees, which is why this is the
     * one preset that puts a ceiling on rather than removing one.
     */
    public static void potato(Minecraft mc) {
        performance(mc);
        VulkanConfig.setEntityDistance(32);
        VulkanConfig.setTileEntityDistance(16);
        // One frame a second out of focus. The game keeps running; the card
        // stops being asked to draw a menu nobody is looking at.
        VulkanConfig.setBackgroundFpsLimit(1);
        // Two, not three. Frames in flight buy the processor room when it is
        // the thing holding the frame up — on this class of machine the card
        // is, and a third frame only adds a frame of delay to the controls.
        VulkanConfig.setFramesInFlight(2);
        mc.gameSettings.limitFramerate = 60;
        mc.gameSettings.enableVsync = true;
        mc.gameSettings.particleSetting = 2;
        mc.gameSettings.entityShadows = false;
        // Through the game's own setter rather than the field: it rebinds the
        // atlas, turns off mipmap filtering and raises the flag Forge added to
        // stop the models being rebuilt once per notch of the slider. Writing
        // the field alone changes the number and nothing else.
        mc.gameSettings.setOptionFloatValue(GameSettings.Options.MIPMAP_LEVELS, 0.0f);
        mc.gameSettings.clouds = 0;
        mc.gameSettings.ambientOcclusion = 0;
        if (mc.gameSettings.renderDistanceChunks > 8) {
            mc.gameSettings.renderDistanceChunks = 8;
        }
        mc.gameSettings.saveOptions();
        // The flag raised above is only acted on when a settings screen closes,
        // and this one was applied from a button in the middle of ours. Said
        // here so the models are rebuilt at the next safe moment rather than
        // whenever the player happens to open and shut the vanilla options.
        mc.gameSettings.onGuiClosed();
        // Smooth lighting and the render distance are baked into chunk
        // geometry, so neither takes effect until the chunks are made again.
        mc.renderGlobal.loadRenderers();
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
