package net.vulkanmod112.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

import net.minecraft.client.Minecraft;

/**
 * Named sets of settings the player saves and switches between.
 *
 * Not the same thing as the presets, and the difference is who wrote them. A
 * preset is a starting point this mod ships: it writes a group of options once
 * and stops existing. A profile is the player's own configuration, captured
 * whole and restored whole — including the vanilla settings that decide the
 * frame rate, because a set of renderer options with the render distance left
 * out is not a configuration anybody wants back.
 *
 * The point is the switching. Trying a setting today means changing six things,
 * playing, and then remembering what the six were; with a profile it is two
 * clicks each way, and nothing has to be remembered at all.
 *
 * Stored one file per profile next to the config, in the plainest format there
 * is, so a profile can be sent to someone else or edited in a text editor when
 * something goes wrong with it.
 */
public final class VulkanProfiles {

    private static final String SUFFIX = ".profile";
    private static File directory;

    /**
     * Four numbered places rather than names the player types.
     *
     * The settings screen has no text field and gains nothing worth the code
     * from having one here: what was asked for is switching between
     * configurations quickly, and a number does that as well as a name. The
     * files are still plain text with a readable name, so anything more
     * elaborate can be done to them outside the game.
     */
    public static final int SLOTS = 4;
    private static int selected;

    public static int selectedSlot() {
        return selected;
    }

    public static void selectSlot(int index) {
        selected = index < 0 ? 0 : index % SLOTS;
    }

    private static String slotName(int index) {
        return "slot-" + (index + 1);
    }

    /** Whether the chosen slot has anything in it, for the row's own label. */
    public static boolean selectedSlotUsed() {
        return exists(slotName(selected));
    }

    public static boolean saveSelected(Minecraft mc) {
        return save(slotName(selected), mc);
    }

    public static boolean loadSelected(Minecraft mc) {
        return load(slotName(selected), mc);
    }

    public static boolean deleteSelected() {
        return delete(slotName(selected));
    }

    private VulkanProfiles() {
    }

    /** Where profiles live; created on demand. */
    public static void setDirectory(File configDirectory) {
        directory = new File(configDirectory, "vulkanmod112-profiles");
    }

    /** Names in a stable order, so the list does not reshuffle between openings. */
    public static List<String> names() {
        List<String> found = new ArrayList<String>();
        if (directory == null || !directory.isDirectory()) {
            return found;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return found;
        }
        TreeSet<String> sorted = new TreeSet<String>();
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(SUFFIX)) {
                sorted.add(name.substring(0, name.length() - SUFFIX.length()));
            }
        }
        found.addAll(sorted);
        return found;
    }

    public static boolean exists(String name) {
        return file(name) != null && file(name).isFile();
    }

    /**
     * Captures everything that decides how the game looks and how fast it runs.
     *
     * The vanilla half is here on purpose. Render distance, graphics quality and
     * particles cost more frames than anything this mod owns, and a profile that
     * restored the renderer's settings while leaving those alone would restore
     * the half that matters least.
     */
    public static boolean save(String name, Minecraft mc) {
        File target = file(name);
        if (target == null) {
            return false;
        }
        Properties values = new Properties();
        values.setProperty("terrain", Boolean.toString(VulkanConfig.isTerrainEnabled()));
        values.setProperty("entityDistance", Integer.toString(VulkanConfig.getEntityDistance()));
        values.setProperty("tileEntityDistance", Integer.toString(VulkanConfig.getTileEntityDistance()));
        values.setProperty("backgroundFps", Integer.toString(VulkanConfig.getBackgroundFpsLimit()));
        values.setProperty("animations", Boolean.toString(VulkanConfig.areAnimationsEnabled()));
        values.setProperty("depthBlit", Boolean.toString(VulkanConfig.isDepthBlitEnabled()));
        values.setProperty("culling", Boolean.toString(VulkanConfig.isCullingEnabled()));
        values.setProperty("flatBlockColours", Boolean.toString(VulkanConfig.isFlatBlockColours()));
        values.setProperty("framesInFlight", Integer.toString(VulkanConfig.getFramesInFlight()));
        values.setProperty("geometryBudget", Integer.toString(VulkanConfig.getGeometryBudgetMiB()));

        values.setProperty("mc.renderDistance", Integer.toString(mc.gameSettings.renderDistanceChunks));
        values.setProperty("mc.mipmap", Integer.toString(mc.gameSettings.mipmapLevels));
        values.setProperty("mc.particles", Integer.toString(mc.gameSettings.particleSetting));
        values.setProperty("mc.fancy", Boolean.toString(mc.gameSettings.fancyGraphics));
        values.setProperty("mc.ao", Integer.toString(mc.gameSettings.ambientOcclusion));
        values.setProperty("mc.clouds", Integer.toString(mc.gameSettings.clouds));
        values.setProperty("mc.shadows", Boolean.toString(mc.gameSettings.entityShadows));
        values.setProperty("mc.fpsLimit", Integer.toString(mc.gameSettings.limitFramerate));
        values.setProperty("mc.vsync", Boolean.toString(mc.gameSettings.enableVsync));

        FileOutputStream out = null;
        try {
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) {
                return false;
            }
            out = new FileOutputStream(target);
            values.store(out, "VulkanMod112 profile: " + name);
            return true;
        } catch (IOException e) {
            net.vulkanmod112.VulkanMod112.LOGGER.warn("Could not save the profile " + name, e);
            return false;
        } finally {
            close(out);
        }
    }

    /**
     * Puts a saved profile back, and rebuilds only what has to be rebuilt.
     *
     * Graphics quality, smooth lighting, the render distance and the mipmap
     * level are all baked into chunk geometry or into the atlas, so changing
     * them means nothing until those are made again — and making them again is
     * the expensive part, which is why it is done once at the end rather than
     * after each value that happens to need it.
     */
    public static boolean load(String name, Minecraft mc) {
        File source = file(name);
        if (source == null || !source.isFile()) {
            return false;
        }
        Properties values = new Properties();
        FileInputStream in = null;
        try {
            in = new FileInputStream(source);
            values.load(in);
        } catch (IOException e) {
            net.vulkanmod112.VulkanMod112.LOGGER.warn("Could not read the profile " + name, e);
            return false;
        } finally {
            close(in);
        }

        VulkanConfig.setTerrainEnabled(bool(values, "terrain", VulkanConfig.isTerrainEnabled()));
        VulkanConfig.setEntityDistance(number(values, "entityDistance", VulkanConfig.getEntityDistance()));
        VulkanConfig.setTileEntityDistance(number(values, "tileEntityDistance", VulkanConfig.getTileEntityDistance()));
        VulkanConfig.setBackgroundFpsLimit(number(values, "backgroundFps", VulkanConfig.getBackgroundFpsLimit()));
        VulkanConfig.setAnimationsEnabled(bool(values, "animations", VulkanConfig.areAnimationsEnabled()));
        VulkanConfig.setDepthBlitEnabled(bool(values, "depthBlit", VulkanConfig.isDepthBlitEnabled()));
        VulkanConfig.setCullingEnabled(bool(values, "culling", VulkanConfig.isCullingEnabled()));
        VulkanConfig.setFlatBlockColours(bool(values, "flatBlockColours", VulkanConfig.isFlatBlockColours()));
        VulkanConfig.setFramesInFlight(number(values, "framesInFlight", VulkanConfig.getFramesInFlight()));
        VulkanConfig.setGeometryBudgetMiB(number(values, "geometryBudget", VulkanConfig.getGeometryBudgetMiB()));

        int wasDistance = mc.gameSettings.renderDistanceChunks;
        int wasMipmap = mc.gameSettings.mipmapLevels;
        boolean wasFancy = mc.gameSettings.fancyGraphics;
        int wasAo = mc.gameSettings.ambientOcclusion;

        mc.gameSettings.renderDistanceChunks = number(values, "mc.renderDistance", wasDistance);
        mc.gameSettings.particleSetting = number(values, "mc.particles", mc.gameSettings.particleSetting);
        mc.gameSettings.fancyGraphics = bool(values, "mc.fancy", wasFancy);
        mc.gameSettings.ambientOcclusion = number(values, "mc.ao", wasAo);
        mc.gameSettings.clouds = number(values, "mc.clouds", mc.gameSettings.clouds);
        mc.gameSettings.entityShadows = bool(values, "mc.shadows", mc.gameSettings.entityShadows);
        mc.gameSettings.limitFramerate = number(values, "mc.fpsLimit", mc.gameSettings.limitFramerate);
        mc.gameSettings.enableVsync = bool(values, "mc.vsync", mc.gameSettings.enableVsync);

        int mipmap = number(values, "mc.mipmap", wasMipmap);
        if (mipmap != wasMipmap) {
            // The setter, not the field: it rebinds the atlas and asks for the
            // models to be rebuilt. The flag it raises is only acted on when a
            // settings screen closes, which is why that is said here too.
            mc.gameSettings.setOptionFloatValue(
                    net.minecraft.client.settings.GameSettings.Options.MIPMAP_LEVELS, mipmap);
            mc.gameSettings.onGuiClosed();
        }
        mc.gameSettings.saveOptions();

        if (mc.renderGlobal != null
                && (mc.gameSettings.renderDistanceChunks != wasDistance
                    || mc.gameSettings.fancyGraphics != wasFancy
                    || mc.gameSettings.ambientOcclusion != wasAo)) {
            mc.renderGlobal.loadRenderers();
        }
        return true;
    }

    public static boolean delete(String name) {
        File target = file(name);
        return target != null && target.isFile() && target.delete();
    }

    /**
     * Rejects anything that is not a plain name.
     *
     * A profile name reaches this as free text the player typed and leaves it as
     * part of a path, which is the shape of problem where a name containing a
     * separator or a pair of dots stops meaning a file in this directory.
     */
    private static File file(String name) {
        if (directory == null || name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 40) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == ' ' || c == '-' || c == '_';
            if (!plain) {
                return null;
            }
        }
        return new File(directory, trimmed + SUFFIX);
    }

    private static boolean bool(Properties values, String key, boolean fallback) {
        String raw = values.getProperty(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw);
    }

    private static int number(Properties values, String key, int fallback) {
        String raw = values.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void close(java.io.Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a file that will not close.
            }
        }
    }
}
