package net.vulkanmod112.client;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Small client-only configuration shared by the settings screen and hooks.
 *
 * Every default here is the conservative choice: the settings that trade
 * safety for speed start off, so a fresh install behaves the same on every
 * driver. The presets in the settings screen are how you opt into the rest.
 */
public final class VulkanConfig {

    private static final String CATEGORY_GENERAL = "general";
    private static final String CATEGORY_OPTIMIZATION = "optimization";
    private static final String CATEGORY_ADVANCED = "advanced";

    // Defaults, named so "reset" and "what shipped" cannot drift apart.
    static final boolean DEF_TERRAIN = true;
    static final boolean DEF_OVERLAY = false;
    static final int DEF_ENTITY_DISTANCE = 0;
    static final int DEF_TILE_ENTITY_DISTANCE = 0;
    static final boolean DEF_ANIMATIONS = true;
    static final int DEF_BACKGROUND_FPS = 10;
    static final boolean DEF_ULTRA_LOG = false;
    static final int DEF_ULTRA_LOG_SECONDS = 10;
    static final boolean DEF_DEPTH_BLIT = true;
    static final boolean DEF_CULLING = true;
    static final int DEF_GEOMETRY_BUDGET = 0;
    static final int DEF_FRAMES_IN_FLIGHT = 2;
    /**
     * Off by default. Measured at render distance 64: 330 fps without it,
     * 120-140 with. Filling the world in costs continuous chunk building, and
     * that is not a price to charge anyone who did not ask for it.
     */
    static final boolean DEF_CHUNK_PRELOAD = false;
    static final boolean DEF_ZOOM = true;
    /** Stored as an integer so it fits the config and the slider; 4 = quarter FOV. */
    static final int DEF_ZOOM_FACTOR = 4;

    private static Configuration config;
    private static boolean terrainEnabled = DEF_TERRAIN;
    private static boolean overlayEnabled = DEF_OVERLAY;
    /** 0 = leave vanilla's own limit alone. */
    private static int entityDistance = DEF_ENTITY_DISTANCE;
    private static int tileEntityDistance = DEF_TILE_ENTITY_DISTANCE;
    private static boolean animationsEnabled = DEF_ANIMATIONS;
    private static int backgroundFpsLimit = DEF_BACKGROUND_FPS;
    private static boolean ultraLogEnabled = DEF_ULTRA_LOG;
    private static int ultraLogSeconds = DEF_ULTRA_LOG_SECONDS;
    private static boolean depthBlitEnabled = DEF_DEPTH_BLIT;
    private static boolean cullingEnabled = DEF_CULLING;
    /** MiB of VRAM the chunk geometry buffer may take; 0 = derive from the GPU. */
    private static int geometryBudgetMiB = DEF_GEOMETRY_BUDGET;
    private static int framesInFlight = DEF_FRAMES_IN_FLIGHT;
    private static boolean chunkPreloadEnabled = DEF_CHUNK_PRELOAD;
    private static boolean zoomEnabled = DEF_ZOOM;
    private static int zoomFactor = DEF_ZOOM_FACTOR;

    private VulkanConfig() {
    }

    public static void load(File configDirectory) {
        config = new Configuration(new File(configDirectory, "vulkanmod112.cfg"));
        config.load();
        terrainEnabled = config.getBoolean("terrainEnabled", CATEGORY_GENERAL, DEF_TERRAIN,
                "Render supported terrain layers through Vulkan. Disabling immediately returns terrain to vanilla OpenGL.");
        overlayEnabled = config.getBoolean("overlayEnabled", CATEGORY_GENERAL, DEF_OVERLAY,
                "Show the legacy Vulkan diagnostic overlay.");
        entityDistance = config.getInt("entityDistance", CATEGORY_OPTIMIZATION, DEF_ENTITY_DISTANCE, 0, 256,
                "Stop drawing entities past this many blocks. 0 keeps vanilla's per-entity limit.");
        tileEntityDistance = config.getInt("tileEntityDistance", CATEGORY_OPTIMIZATION, DEF_TILE_ENTITY_DISTANCE, 0, 128,
                "Stop drawing chests, signs and other block entities past this many blocks. 0 keeps vanilla's.");
        animationsEnabled = config.getBoolean("animatedTextures", CATEGORY_OPTIMIZATION, DEF_ANIMATIONS,
                "Update animated block textures. Off skips the per-tick frame uploads for every animated sprite.");
        backgroundFpsLimit = config.getInt("backgroundFpsLimit", CATEGORY_OPTIMIZATION, DEF_BACKGROUND_FPS, 0, 60,
                "Framerate cap while the game window is not active. 0 disables the cap.");
        ultraLogEnabled = config.getBoolean("ultraLog", CATEGORY_ADVANCED, DEF_ULTRA_LOG,
                "Write a detailed diagnostics report to logs/vulkanmod112-diagnostics.log.");
        ultraLogSeconds = config.getInt("ultraLogSeconds", CATEGORY_ADVANCED, DEF_ULTRA_LOG_SECONDS, 1, 120,
                "Seconds between diagnostics snapshots.");
        depthBlitEnabled = config.getBoolean("depthBlitEnabled", CATEGORY_ADVANCED, DEF_DEPTH_BLIT,
                "Copy Vulkan depth into the game's depth buffer with glBlitFramebuffer instead of a shader.");
        cullingEnabled = config.getBoolean("cullingEnabled", CATEGORY_ADVANCED, DEF_CULLING,
                "Skip triangles facing away from the camera. Off is for diagnosing geometry only.");
        geometryBudgetMiB = config.getInt("geometryBudgetMiB", CATEGORY_ADVANCED, DEF_GEOMETRY_BUDGET, 0, 8192,
                "VRAM in MiB the chunk geometry buffer may take before growth becomes cautious. "
                        + "0 derives it from the amount of memory the GPU reports.");
        framesInFlight = config.getInt("framesInFlight", CATEGORY_ADVANCED, DEF_FRAMES_IN_FLIGHT, 1, 3,
                "How many terrain frames the CPU may run ahead of the GPU. Higher smooths out stalls "
                        + "at the cost of one frame of input latency and more memory.");
        chunkPreloadEnabled = config.getBoolean("chunkPreload", CATEGORY_OPTIMIZATION,
                DEF_CHUNK_PRELOAD,
                "Let chunks outside the view be rebuilt. Vanilla only ever schedules chunks that are "
                        + "currently on screen, so at high render distances the world fills in along "
                        + "whatever you are looking at.");
        zoomEnabled = config.getBoolean("zoom", CATEGORY_GENERAL, DEF_ZOOM,
                "Hold-to-zoom on the key bound in Controls.");
        zoomFactor = config.getInt("zoomFactor", CATEGORY_GENERAL, DEF_ZOOM_FACTOR, 2, 10,
                "How far the zoom key narrows the field of view. 4 means a quarter of it.");
        applySystemProperties();
        save();
    }

    /**
     * Returns every mod-owned setting to its shipped value. Minecraft's own
     * settings are left alone: they are not ours to reset, and the screen only
     * borrows them.
     */
    public static void resetToDefaults() {
        setTerrainEnabled(DEF_TERRAIN);
        setOverlayEnabled(DEF_OVERLAY);
        setEntityDistance(DEF_ENTITY_DISTANCE);
        setTileEntityDistance(DEF_TILE_ENTITY_DISTANCE);
        setAnimationsEnabled(DEF_ANIMATIONS);
        setBackgroundFpsLimit(DEF_BACKGROUND_FPS);
        setUltraLogEnabled(DEF_ULTRA_LOG);
        setUltraLogSeconds(DEF_ULTRA_LOG_SECONDS);
        setDepthBlitEnabled(DEF_DEPTH_BLIT);
        setCullingEnabled(DEF_CULLING);
        setGeometryBudgetMiB(DEF_GEOMETRY_BUDGET);
        setFramesInFlight(DEF_FRAMES_IN_FLIGHT);
        setChunkPreloadEnabled(DEF_CHUNK_PRELOAD);
        setZoomEnabled(DEF_ZOOM);
        setZoomFactor(DEF_ZOOM_FACTOR);
    }

    public static boolean isChunkPreloadEnabled() {
        return chunkPreloadEnabled;
    }

    public static void setChunkPreloadEnabled(boolean value) {
        chunkPreloadEnabled = value;
        store(CATEGORY_OPTIMIZATION, "chunkPreload", value);
    }

    public static boolean isZoomEnabled() {
        return zoomEnabled;
    }

    public static void setZoomEnabled(boolean value) {
        zoomEnabled = value;
        store(CATEGORY_GENERAL, "zoom", value);
    }

    /** Divisor applied to the field of view while the zoom key is held. */
    public static float getZoomFactor() {
        return zoomFactor;
    }

    public static void setZoomFactor(int value) {
        zoomFactor = value;
        store(CATEGORY_GENERAL, "zoomFactor", value);
    }

    public static boolean isTerrainEnabled() {
        return terrainEnabled;
    }

    public static void setTerrainEnabled(boolean value) {
        terrainEnabled = value;
        store(CATEGORY_GENERAL, "terrainEnabled", value);
    }

    public static boolean isOverlayEnabled() {
        return overlayEnabled;
    }

    public static void setOverlayEnabled(boolean value) {
        overlayEnabled = value;
        store(CATEGORY_GENERAL, "overlayEnabled", value);
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

    public static int getGeometryBudgetMiB() {
        return geometryBudgetMiB;
    }

    public static void setGeometryBudgetMiB(int value) {
        geometryBudgetMiB = value;
        store(CATEGORY_ADVANCED, "geometryBudgetMiB", value);
        applySystemProperties();
    }

    public static int getFramesInFlight() {
        return framesInFlight;
    }

    public static void setFramesInFlight(int value) {
        framesInFlight = value;
        store(CATEGORY_ADVANCED, "framesInFlight", value);
        applySystemProperties();
    }

    /**
     * The renderer lives behind the bridge in its own classloader and reads
     * these as system properties, which both sides share.
     */
    private static void applySystemProperties() {
        System.setProperty("vulkanmod112.depthBlit", Boolean.toString(depthBlitEnabled));
        System.setProperty("vulkanmod112.cull", Boolean.toString(cullingEnabled));
        System.setProperty("vulkanmod112.geometryBudget", Integer.toString(geometryBudgetMiB));
        System.setProperty("vulkanmod112.framesInFlight", Integer.toString(framesInFlight));
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
