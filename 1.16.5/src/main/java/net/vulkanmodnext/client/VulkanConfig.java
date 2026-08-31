package net.vulkanmodnext.client;

import net.vulkanmodnext.VulkanModNext;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * What the settings currently say, and where they are kept.
 *
 * <h2>Everything is an int</h2>
 *
 * Including the switches, which are 0 or 1. One store instead of two, and the
 * screen does not have to ask which kind a row is before reading it — the
 * {@link Settings} table already knows. What is lost is nothing: no setting in
 * this mod has ever been anything but a switch or a whole number.
 *
 * <h2>Values are kept even for settings that do nothing</h2>
 *
 * Most of these steer a part of the mod that has not been ported yet. They are
 * still stored and still written to the file, so that when the part arrives the
 * player's choice is already there — and so that a config file carried from one
 * build to the next does not quietly lose half its lines.
 */
public final class VulkanConfig {

    private static final Map<String, Integer> VALUES = new ConcurrentHashMap<>();
    private static File file;

    private VulkanConfig() {
    }

    public static synchronized void load(File configDirectory) {
        file = new File(configDirectory, "vulkanmodnext.properties");
        for (Settings.Setting setting : Settings.all()) {
            VALUES.put(setting.key, setting.fallback);
        }
        if (!file.exists()) {
            save();
            publish();
            return;
        }
        Properties stored = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            stored.load(in);
        } catch (IOException failed) {
            VulkanModNext.LOGGER.warn("Could not read {}, using defaults: {}",
                    file.getName(), failed.toString());
            return;
        }
        int read = 0;
        for (Settings.Setting setting : Settings.all()) {
            String value = stored.getProperty(setting.key);
            if (value == null) {
                continue;
            }
            try {
                VALUES.put(setting.key, clamp(setting, Integer.parseInt(value.trim())));
                read++;
            } catch (NumberFormatException ignored) {
                // A line somebody edited by hand into nonsense is not worth
                // refusing to start over; the default stands and the count says
                // one fewer was read.
            }
        }
        VulkanModNext.LOGGER.info("Settings: {} of {} read from {}",
                read, Settings.all().size(), file.getName());
        publish();
    }

    public static synchronized void save() {
        if (file == null) {
            return;
        }
        Properties out = new Properties();
        for (Settings.Setting setting : Settings.all()) {
            out.setProperty(setting.key, Integer.toString(get(setting.key)));
        }
        try (FileOutputStream stream = new FileOutputStream(file)) {
            out.store(stream, "VulkanMod 1.16.5 — see the in-game settings screen");
        } catch (IOException failed) {
            VulkanModNext.LOGGER.warn("Could not write {}: {}", file.getName(), failed.toString());
        }
    }

    private static int clamp(Settings.Setting setting, int value) {
        return Math.max(setting.min, Math.min(setting.max, value));
    }

    /**
     * Names the Vulkan half reads, and the setting each one comes from.
     *
     * <h2>Why through system properties</h2>
     *
     * The Vulkan half was written on 1.12.2, where it lives on a class loader
     * of its own and cannot see the game's classes at all — so the only thing
     * both halves could agree on was a system property. Here they are in one
     * loader and could talk directly, but keeping the same channel means those
     * eighteen thousand lines arrive needing no edits, and this table is the
     * whole cost of that.
     *
     * <p>Only the ones it actually reads are here. A property nothing reads is
     * a check with no consumer, and this project has a rule about those.
     */
    private static final String[][] PUBLISHED = {
            {"compactVertices", "vulkanmodnext.compactVertices"},
            {"groupQuadFacings", "vulkanmodnext.groupFacings"},
            {"cullingEnabled", "vulkanmodnext.cull"},
            {"depthBlitEnabled", "vulkanmodnext.depthBlit"},
            {"flatBlockColours", "vulkanmodnext.flatBlockColours"},
            {"motionOverWorld", "vulkanmodnext.motionOverWorld"},
            {"hdrFrame", "vulkanmodnext.hdrFrame"},
            {"sceneOcclusion", "vulkanmodnext.sceneOcclusion"},
            {"showAccumulation", "vulkanmodnext.showAccumulation"},
            {"showCreatureLight", "vulkanmodnext.showCreatureLight"},
            {"showMaterials", "vulkanmodnext.showMaterials"},
            {"showMotion", "vulkanmodnext.showMotion"},
            {"showOcclusion", "vulkanmodnext.showOcclusion"},
            {"showReflections", "vulkanmodnext.showReflections"},
            {"framesInFlight", "vulkanmodnext.framesInFlight"},
            {"geometryBudgetMiB", "vulkanmodnext.geometryBudget"},
            {"vulkanDevice", "vulkanmodnext.vulkanDevice"},
            {"atlasPixelsSeen", "vulkanmodnext.atlasPixelsSeen"},
    };

    /**
     * Hands the current values to the Vulkan half.
     *
     * Several of these are read once, when a resource is created — the vertex
     * layout is settled before the first chunk and the frame count before the
     * first frame — so this has to run before Vulkan starts, not merely before
     * it draws.
     */
    private static boolean published;

    public static void publish() {
        for (String[] pair : PUBLISHED) {
            Settings.Setting setting = find(pair[0]);
            if (setting == null) {
                continue;
            }
            if (System.getProperty(pair[1]) != null && !published) {
                // Set on the command line before we ever ran. A launcher flag
                // is a deliberate act and outranks the config file — the same
                // rule the scratch stack follows, and the reason a diagnostic
                // run can be made without editing somebody's settings.
                continue;
            }
            int value = get(pair[0]);
            System.setProperty(pair[1], setting.bool
                    ? Boolean.toString(value != 0) : Integer.toString(value));
        }
        published = true;
    }

    static {
    }

    private static Settings.Setting find(String key) {
        for (Settings.Setting setting : Settings.all()) {
            if (setting.key.equals(key)) {
                return setting;
            }
        }
        return null;
    }

    public static int get(String key) {
        Integer value = VALUES.get(key);
        return value == null ? 0 : value;
    }

    public static boolean on(String key) {
        return get(key) != 0;
    }

    public static void set(Settings.Setting setting, int value) {
        VALUES.put(setting.key, clamp(setting, value));
    }

    public static void reset() {
        for (Settings.Setting setting : Settings.all()) {
            VALUES.put(setting.key, setting.fallback);
        }
    }

    /**
     * The one setting the port acts on today, named here rather than reached
     * for by string in the middle of a frame.
     */
    public static boolean isTerrainEnabled() {
        // The dev client's own override, so a run can draw through Vulkan
        // without writing anything into a player's settings.
        String forced = System.getProperty("vulkanmodnext.terrain");
        if (forced != null) {
            return "on".equalsIgnoreCase(forced) || "true".equalsIgnoreCase(forced);
        }
        return on("terrainEnabled");
    }
}
