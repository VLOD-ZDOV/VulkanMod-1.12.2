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
        // Everything the mod owns, enumerated from the settings file rather
        // than named here. The version that named them left out every shader
        // percentage — a dozen of the most-used values in the mod — and said
        // nothing, because a list of settings kept beside the settings is a
        // list that drifts.
        VulkanConfig.snapshotInto(values);

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

        int restored = VulkanConfig.restoreFrom(values);
        if (restored == 0) {
            // A profile saved before this format existed. Those hold ten mod
            // settings under bare names and nothing else — read them so an old
            // profile still does what it used to, rather than doing nothing.
            restoreLegacy(values);
        }

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

    /** The ten settings profiles used to hold, for files written before 01.08.2026. */
    private static void restoreLegacy(Properties values) {
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
    }

    public static boolean delete(String name) {
        File target = file(name);
        return target != null && target.isFile() && target.delete();
    }

    /** True when the name is one this mod will accept as a file. */
    public static boolean nameIsUsable(String name) {
        return file(name) != null;
    }

    /** Renames a profile, refusing to overwrite an existing one. */
    public static boolean rename(String from, String to) {
        File source = file(from);
        File target = file(to);
        if (source == null || target == null || !source.isFile() || target.exists()) {
            return false;
        }
        return source.renameTo(target);
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
