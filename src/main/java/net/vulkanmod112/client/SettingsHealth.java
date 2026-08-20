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
     * Why the frame is still eight bits although it was asked to be more.
     *
     * Three states rather than two, and the difference matters to whoever is
     * reading it: got it, could have it but nothing would close it back down,
     * and will never get it on this driver. The middle one is the one that can
     * be acted on, and saying that the driver refused to somebody whose
     * renderer is simply switched off is advice that costs an evening.
     */
    private static String describeHdrFrame() {
        if (!VulkanConfig.isHdrFrame()) {
            return null;
        }
        if (Boolean.getBoolean("vulkanmod112.hdrFrameActive")) {
            return null;
        }
        if (Boolean.getBoolean("vulkanmod112.hdrFrameRefused")) {
            return "high dynamic range — this driver would not give a floating frame, "
                    + "so the picture stays eight bits a channel";
        }
        // The only other way to be on and inactive: the frame is put back to
        // eight bits on purpose whenever the pass that would close its range
        // back down is not going to run. Naming the cause rather than the
        // effect, because the cause is the thing that can be fixed.
        return "high dynamic range — the Vulkan renderer is not drawing, and the pass "
                + "that brings the picture back into range lives there";
    }

    /**
     * Why the sun and moon are still the game's own.
     *
     * The picture is put on them by redirecting the texture bind inside
     * vanilla's own sky method, which is the narrowest change available — and
     * that narrowness is also the whole of the exposure. Forge lets a world
     * provider replace the sky outright, and vanilla's method returns on its
     * first line when one has, so the bind never happens and there is nothing
     * to redirect. Nothing breaks and nothing happens, which is the worse of
     * the two ways to fail: the switch is on, the preset that set it says it
     * set it, and the sky overhead is somebody else's.
     *
     * Asked of the world rather than of a mod list. Which mod took the sky
     * does not change the answer, and a list of the ones that might would be
     * wrong the first time a new one appeared.
     */
    private static String describeSkyTaken() {
        StringBuilder names = new StringBuilder();
        add(names, "the round sun", VulkanConfig.isRoundSun());
        add(names, "the round moon", VulkanConfig.isRoundMoon());
        add(names, "borrowed sun and moon pictures", !VulkanConfig.getSkinPack().isEmpty());
        if (names.length() == 0 || !skyBelongsToSomebodyElse()) {
            return null;
        }
        return names + " — another mod draws the sky in this world, and the sun "
                + "and moon come with it";
    }

    /** Whether this world's provider has had its sky renderer replaced. */
    private static boolean skyBelongsToSomebodyElse() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            return mc != null && mc.world != null
                    && mc.world.provider.getSkyRenderer() != null;
        } catch (Throwable t) {
            // No world, or a provider that will not answer: not a case worth
            // a warning about, and certainly not one worth an exception.
            return false;
        }
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
        // Asked separately from everything above, because this one is not about
        // our renderer at all: it is the format of the game's own frame, and it
        // stays wrong in exactly the sessions where the renderer is perfectly
        // healthy. The two answers are worth telling apart — one of them the
        // player can act on and the other one they cannot.
        String hdr = describeHdrFrame();
        if (hdr != null) {
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(hdr);
        }
        // Separate again, and for the same reason: this one has nothing to do
        // with our renderer either. It is true with the renderer drawing
        // perfectly and false with it switched off, so folding it in with the
        // rest would put a cause in front of the reader that is not theirs.
        // And again separate, with a cause of its own: this one is on, the
        // renderer is drawing, tracing is irrelevant — and it still does
        // nothing, because the creatures it shades are being drawn by the game.
        // Exactly the shape of the ninety seconds this file was written for.
        String creatures = describeCreatureLight();
        if (creatures != null) {
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(creatures);
        }
        String sky = describeSkyTaken();
        if (sky != null) {
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(sky);
        }
        return out.length() == 0 ? null : out.toString();
    }

    /**
     * Everything that lives in the terrain shaders, which means everything that
     * needs the Vulkan renderer to be the one drawing the world.
     */
    /**
     * Why creatures are lit exactly as they always were.
     *
     * Two ways to be switched on and inert, and they read very differently to
     * whoever is stuck. Turning the shading up while the game is still drawing
     * the creatures changes nothing at all; turning the diagnostic on while
     * the shading is off paints every creature flat white, which looks like a
     * fault in the diagnostic rather than an empty question.
     */
    private static String describeCreatureLight() {
        boolean lit = VulkanConfig.getCreatureLight() > 0;
        if (lit && !VulkanConfig.isVulkanEntities()) {
            return "creature light — the game is still drawing the creatures, and this "
                    + "shades them in the pass that draws them here; switch on Draw "
                    + "Creatures in Vulkan";
        }
        if (VulkanConfig.isShowCreatureLight() && !lit) {
            return "show creature light — with the shading itself at zero there is nothing "
                    + "for it to show, so every creature comes out the same flat white";
        }
        return null;
    }

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
        // Belongs here for the same reason dynamic lights do: creatures are
        // shaded in the pass that draws them here, and with the renderer off
        // there is no such pass.
        add(out, "creature light", VulkanConfig.getCreatureLight());
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
