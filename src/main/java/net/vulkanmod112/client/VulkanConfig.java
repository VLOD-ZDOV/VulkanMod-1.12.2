package net.vulkanmod112.client;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** Small client-only configuration shared by the settings screen and hooks. */
public final class VulkanConfig {

    private static final String CATEGORY_GENERAL = "general";
    private static final String CATEGORY_OPTIMIZATION = "optimization";
    private static final String CATEGORY_ADVANCED = "advanced";
    private static Configuration config;
    private static boolean terrainEnabled = true;
    private static boolean overlayEnabled;
    /** 0 = leave vanilla's own limit alone. */
    private static int entityDistance;
    private static int tileEntityDistance;
    private static boolean animationsEnabled = true;
    private static int backgroundFpsLimit = 10;
    private static boolean ultraLogEnabled;
    private static int ultraLogSeconds = 10;
    private static boolean depthBlitEnabled = true;
    private static boolean cullingEnabled = true;

    private VulkanConfig() {
    }

    public static void load(File configDirectory) {
        config = new Configuration(new File(configDirectory, "vulkanmod112.cfg"));
        config.load();
        terrainEnabled = config.getBoolean("terrainEnabled", CATEGORY_GENERAL, true,
                "Render supported terrain layers through Vulkan. Disabling immediately returns terrain to vanilla OpenGL.");
        overlayEnabled = config.getBoolean("overlayEnabled", CATEGORY_GENERAL, false,
                "Show the legacy Vulkan diagnostic overlay.");
        entityDistance = config.getInt("entityDistance", CATEGORY_OPTIMIZATION, 0, 0, 256,
                "Stop drawing entities past this many blocks. 0 keeps vanilla's per-entity limit.");
        tileEntityDistance = config.getInt("tileEntityDistance", CATEGORY_OPTIMIZATION, 0, 0, 128,
                "Stop drawing chests, signs and other block entities past this many blocks. 0 keeps vanilla's.");
        animationsEnabled = config.getBoolean("animatedTextures", CATEGORY_OPTIMIZATION, true,
                "Update animated block textures. Off skips the per-tick frame uploads for every animated sprite.");
        backgroundFpsLimit = config.getInt("backgroundFpsLimit", CATEGORY_OPTIMIZATION, 10, 0, 60,
                "Framerate cap while the game window is not active. 0 disables the cap.");
        ultraLogEnabled = config.getBoolean("ultraLog", CATEGORY_ADVANCED, false,
                "Write a detailed diagnostics report to logs/vulkanmod112-diagnostics.log.");
        ultraLogSeconds = config.getInt("ultraLogSeconds", CATEGORY_ADVANCED, 10, 1, 120,
                "Seconds between diagnostics snapshots.");
        depthBlitEnabled = config.getBoolean("depthBlitEnabled", CATEGORY_ADVANCED, true,
                "Copy Vulkan depth into the game's depth buffer with glBlitFramebuffer instead of a shader.");
        cullingEnabled = config.getBoolean("cullingEnabled", CATEGORY_ADVANCED, true,
                "Skip triangles facing away from the camera. Off is for diagnosing geometry only.");
        applySystemProperties();
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

    public static int getEntityDistance() {
        return entityDistance;
    }

    public static void setEntityDistance(int value) {
        entityDistance = value;
        store(CATEGORY_OPTIMIZATION, "entityDistance", value);
    }

    public static int getTileEntityDistance() {
        return tileEntityDistance;
    }

    public static void setTileEntityDistance(int value) {
        tileEntityDistance = value;
        store(CATEGORY_OPTIMIZATION, "tileEntityDistance", value);
    }

    public static boolean areAnimationsEnabled() {
        return animationsEnabled;
    }

    public static void setAnimationsEnabled(boolean value) {
        animationsEnabled = value;
        store(CATEGORY_OPTIMIZATION, "animatedTextures", value);
    }

    public static int getBackgroundFpsLimit() {
        return backgroundFpsLimit;
    }

    public static void setBackgroundFpsLimit(int value) {
        backgroundFpsLimit = value;
        store(CATEGORY_OPTIMIZATION, "backgroundFpsLimit", value);
    }

    public static boolean isUltraLogEnabled() {
        return ultraLogEnabled;
    }

    public static void setUltraLogEnabled(boolean value) {
        ultraLogEnabled = value;
        store(CATEGORY_ADVANCED, "ultraLog", value);
    }

    public static int getUltraLogSeconds() {
        return ultraLogSeconds;
    }

    public static void setUltraLogSeconds(int value) {
        ultraLogSeconds = value;
        store(CATEGORY_ADVANCED, "ultraLogSeconds", value);
    }

    public static boolean isDepthBlitEnabled() {
        return depthBlitEnabled;
    }

    public static void setDepthBlitEnabled(boolean value) {
        depthBlitEnabled = value;
        store(CATEGORY_ADVANCED, "depthBlitEnabled", value);
        applySystemProperties();
    }

    public static boolean isCullingEnabled() {
        return cullingEnabled;
    }

    public static void setCullingEnabled(boolean value) {
        cullingEnabled = value;
        store(CATEGORY_ADVANCED, "cullingEnabled", value);
        applySystemProperties();
    }

    /**
     * The renderer lives behind the bridge in its own classloader and reads
     * these as system properties, which both sides share.
     */
    private static void applySystemProperties() {
        System.setProperty("vulkanmod112.depthBlit", Boolean.toString(depthBlitEnabled));
        System.setProperty("vulkanmod112.cull", Boolean.toString(cullingEnabled));
    }

    private static void store(String category, String key, int value) {
        if (config != null) {
            config.get(category, key, value).set(value);
            save();
        }
    }

    private static void store(String category, String key, boolean value) {
        if (config != null) {
            config.get(category, key, value).set(value);
            save();
        }
    }

    private static void save() {
        if (config != null && config.hasChanged()) {
            config.save();
        }
    }
}
