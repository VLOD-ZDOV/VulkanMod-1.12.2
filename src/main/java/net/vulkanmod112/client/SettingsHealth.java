package net.vulkanmod112.client;

/**
 * Says out loud when a setting is switched on and cannot do anything.
 *
 * <h2>What went wrong without it</h2>
 *
 * A tester turned dynamic lights on, watched for ninety seconds, and reported
 * that dynamic lights do not work. They were right about everything they saw.
 * The Vulkan terrain renderer was off, every effect in this mod is code inside
 * it, and the setting was therefore doing exactly nothing — while the settings
 * screen showed the switch on, the log said the Vulkan foundation was active,
 * and the only place the truth appeared was a line in a diagnostics file that
 * has to be enabled first and then read carefully.
 *
 * Three separate sources of information, all of them true, adding up to the
 * wrong conclusion. That is not a tester's mistake to make.
 *
 * <h2>Where it is said</h2>
 *
 * In the ordinary log, which is what a report comes with, and again in the
 * diagnostics snapshot right under the values it explains. Once per change of
 * state rather than once a frame: this is a note about a configuration, and a
 * configuration is only worth mentioning when it moves.
 *
 * <h2>Why the list is written out</h2>
 *
 * Every effect could be found by walking the published settings and treating
 * anything non-zero as on, which would never need touching again. It is spelt
 * out instead because this text is read by someone who is stuck, and
 * "foliageSway=40" is not what they called it when they turned it on. The
 * cost of that choice is that an effect added later has to be added here too,
 * and the check below is what makes forgetting visible.
 */
public final class SettingsHealth {

    private SettingsHealth() {
    }

    /** What was last said, so that the same sentence is not repeated. */
    private static String lastReported = "";

    /**
     * Logs a change in what is switched on but inert. Cheap when nothing moved.
     */
    public static void check() {
        String now = describeInert();
        String key = now == null ? "" : now;
        if (key.equals(lastReported)) {
            return;
        }
        boolean somethingWas = !lastReported.isEmpty();
        lastReported = key;
        if (now == null) {
            if (somethingWas) {
                net.vulkanmod112.VulkanMod112.LOGGER.info(
                        "Every effect that is switched on can now act");
            }
            return;
        }
        net.vulkanmod112.VulkanMod112.LOGGER.warn("Switched on but doing nothing: {}", now);
    }

    /**
     * The settings that are on and cannot act, and why, or null when none are.
     *
     * @return a sentence meant to be read by whoever is looking at the screen
     */
    public static String describeInert() {
        StringBuilder out = new StringBuilder();
        String why = TerrainHooks.whyNotDrawing();
        if (why != null) {
            StringBuilder names = new StringBuilder();
            appendTerrainEffects(names);
            appendTracedEffects(names);
            if (names.length() > 0) {
                out.append(names).append(" — ").append(why);
            }
        } else if (tracingActive()) {
            // The other way round from everything else here: these two are
            // switched on, the renderer is drawing, and they still do nothing
            // — because tracing is on. They are compiled out of the tracing
            // variant of the terrain shader deliberately, after a specular
            // term of the same shape in the same branch lost the graphics
            // device outright. The reasoning is written at WHY_NOT_WITH_RAY_QUERY
            // in terrain.frag; what matters here is that a player who turns
            // ice shine on and sees nothing is told why by the game rather
            // than by a document.
            StringBuilder names = new StringBuilder();
            add(names, "sun and moon glint", VulkanConfig.getCelestialGlint());
            add(names, "ice shine", VulkanConfig.getIceShine());
            add(names, "water caustics", VulkanConfig.getWaterCaustics());
            add(names, "water depth and shore foam", VulkanConfig.getWaterRefraction());
            if (names.length() > 0) {
                out.append(names).append(" — these are left out of the traced "
                        + "terrain shader on purpose, and ray tracing is on");
            }
        } else {
            // Said in the chat as well, once, and only for the case the player
            // can actually do something about: they asked for tracing, the
            // device has not got it, and one restart is the whole of the fix.
            if (VulkanConfig.isRayTracing()) {
                RenderNotice.restartNeededForRayTracing();
            }
            // Only worth saying once the renderer itself is out of the way:
            // with it off these are already named above, and naming them twice
            // in one sentence reads as two separate problems.
            StringBuilder names = new StringBuilder();
            appendTracedEffects(names);
            if (names.length() > 0) {
                out.append(names).append(" — ").append(tracingReason());
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    /**
     * Everything that lives in the terrain shaders, which means everything that
     * needs the Vulkan renderer to be the one drawing the world.
     */
    private static void appendTerrainEffects(StringBuilder out) {
        add(out, "water waves", VulkanConfig.getWaterWaves());
        add(out, "water reflections", VulkanConfig.getWaterReflection());
        add(out, "screen reflections", VulkanConfig.getScreenReflections());
        add(out, "water refraction", VulkanConfig.getWaterRefraction());
        add(out, "swaying foliage", VulkanConfig.getFoliageSway());
        add(out, "sun and moon glint", VulkanConfig.getCelestialGlint());
        add(out, "ice shine", VulkanConfig.getIceShine());
        add(out, "water caustics", VulkanConfig.getWaterCaustics());
        add(out, "wet surfaces", VulkanConfig.getWetSurfaces());
        add(out, "sun haze", VulkanConfig.getSunHaze());
        add(out, "bloom", VulkanConfig.getBloom());
        add(out, "scene tone", VulkanConfig.getSceneTone());
        add(out, "ambient occlusion", VulkanConfig.getAmbientOcclusion());
        add(out, "directional block light", VulkanConfig.getDirectionalLight());
        add(out, "height fog", VulkanConfig.getHeightFog());
        add(out, "frame accumulation", VulkanConfig.getTemporalAccumulation());
        // Not a shader effect, and it belongs here all the same: the sources
        // are collected inside the Vulkan draw and nowhere else, so with the
        // renderer off nothing is ever gathered and the entity, particle and
        // held-item hooks all find an empty list and return what they were
        // given. This is the one that cost the ninety seconds.
        add(out, "dynamic lights", VulkanConfig.isDynamicLights());
    }

    /**
     * Whether rays are actually being traced, asked of the device.
     *
     * Not {@code VulkanConfig.isRayTracing()}, which is what the player asked
     * for. Acceleration structures have to be requested when the Vulkan device
     * is created, so the switch only takes effect at the next start — and in
     * between, the setting says on and nothing traces. A tester turned on sun
     * shadows, traced light and traced block light, got no shadow from a torch,
     * and the check here stayed silent because it believed the setting.
     *
     * With no renderer at all the question does not arise; the caller has
     * already established there is one.
     */
    private static boolean tracingActive() {
        net.vulkanmod112.VulkanBridge bridge = net.vulkanmod112.VulkanLoader.bridgeIfReady();
        if (bridge == null || !bridge.isInitialized()) {
            return VulkanConfig.isRayTracing();
        }
        try {
            return bridge.isRayTracingActive();
        } catch (Throwable t) {
            // A bridge that cannot answer is not worth a wrong warning.
            return true;
        }
    }

    private static String tracingReason() {
        net.vulkanmod112.VulkanBridge bridge = net.vulkanmod112.VulkanLoader.bridgeIfReady();
        if (bridge != null && bridge.isInitialized()) {
            try {
                return "these are traced against the world, and ray tracing is "
                        + bridge.rayTracingStatus();
            } catch (Throwable t) {
                // Fall through to the plain sentence below.
            }
        }
        return "these are traced against the world, and ray tracing is off";
    }

    /** The three that need rays, whatever else is true. */
    private static void appendTracedEffects(StringBuilder out) {
        add(out, "sun shadows", VulkanConfig.getSunShadows());
        add(out, "traced light shadows", VulkanConfig.getTracedLights());
        add(out, "traced block light", VulkanConfig.getTracedBlockLight());
    }

    private static void add(StringBuilder out, String name, int strength) {
        add(out, name, strength > 0);
    }

    private static void add(StringBuilder out, String name, boolean on) {
        if (!on) {
            return;
        }
        if (out.length() > 0) {
            out.append(", ");
        }
        out.append(name);
    }
}
