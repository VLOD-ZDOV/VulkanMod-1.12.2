package net.vulkanmod112.client;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** Small client-only configuration shared by the settings screen and hooks. */
public final class VulkanConfig {

    private static final String CATEGORY_GENERAL = "general";
    private static Configuration config;
    private static boolean terrainEnabled = true;
    private static boolean overlayEnabled;

    private VulkanConfig() {
    }

    public static void load(File configDirectory) {
        config = new Configuration(new File(configDirectory, "vulkanmod112.cfg"));
        config.load();
        terrainEnabled = config.getBoolean("terrainEnabled", CATEGORY_GENERAL, true,
                "Render supported terrain layers through Vulkan. Disabling immediately returns terrain to vanilla OpenGL.");
        overlayEnabled = config.getBoolean("overlayEnabled", CATEGORY_GENERAL, false,
                "Show the legacy Vulkan diagnostic overlay.");
        save();
    }

    public static boolean isTerrainEnabled() {
        return terrainEnabled;
    }

    public static void setTerrainEnabled(boolean value) {
        terrainEnabled = value;
        if (config != null) {
            config.get(CATEGORY_GENERAL, "terrainEnabled", true).set(value);
            save();
        }
    }

    public static boolean isOverlayEnabled() {
        return overlayEnabled;
    }

    public static void setOverlayEnabled(boolean value) {
        overlayEnabled = value;
        if (config != null) {
            config.get(CATEGORY_GENERAL, "overlayEnabled", false).set(value);
            save();
        }
    }

    private static void save() {
        if (config != null && config.hasChanged()) {
            config.save();
        }
    }
}
