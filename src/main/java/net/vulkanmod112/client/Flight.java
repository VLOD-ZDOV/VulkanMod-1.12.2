package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.vulkanmod112.VulkanMod112;

/**
 * A flight the game makes by itself, so that a measurement does not need
 * somebody to sit and fly it.
 *
 * <h2>Why this exists</h2>
 *
 * Everything this renderer is judged by happens while the camera moves. Half
 * the terrain code does nothing at all while you stand still, an effect keyed
 * to the screen only misbehaves while you turn, and the numbers that matter —
 * the worst frame in a hundred, the chunk that took a tenth of a second to
 * build — appear in flight and nowhere else. Which meant every open question
 * here ended in "load a world, fly this way, and read the line back to me",
 * and a question asked that way is answered once and never re-asked. A route
 * flown by the game itself is answered as often as it is worth answering, and
 * two runs of the same route are comparable in a way that two hands on a
 * keyboard are not.
 *
 * <h2>What it does</h2>
 *
 * With {@code -Dvulkanmod112.flight=<route>} the client waits for the main
 * menu, makes a world of its own from a fixed seed, waits for the chunks
 * around the spawn to be built, flies the named route, takes screenshots along
 * it, marks the diagnostics file at each one and quits. Nothing is pressed and
 * nothing is clicked; every call here is one the game makes for itself.
 *
 * <h2>Routes</h2>
 *
 * <ul>
 *   <li>{@code hover} — stand still, look one way. The shape of the
 *       measurement that has been used against OptiFine so far.</li>
 *   <li>{@code spin} — stand still and turn once all the way round, ending at
 *       the angle it started at. The route that catches anything keyed to the
 *       screen rather than to the world: the first and last frames are of the
 *       same view, so a difference between them is the fault itself rather
 *       than a matter of opinion.</li>
 *   <li>{@code line} — fly in a straight line at a constant speed, which is
 *       what makes chunks arrive and is therefore where the stutter lives.</li>
 * </ul>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * It never decides whether a picture is good. It puts frames and numbers on
 * disk, in a state something else can compare; the judgement stays where it
 * was.
 */
public final class Flight {

    /** Which route, or empty when nothing was asked for. */
    private static final String ROUTE = System.getProperty("vulkanmod112.flight", "").trim();

    /** A name for this run, so two of them can sit in the same folder. */
    private static final String TAG = System.getProperty("vulkanmod112.flightTag", "run").trim();

    /**
     * The world's seed. Fixed by default and settable, because a comparison
     * between two builds is only a comparison if both flew over the same
     * ground.
     */
    private static final long SEED = Long.getLong("vulkanmod112.flightSeed", 8815751L);

    /** Chunks of render distance, pinned rather than taken from the options. */
    private static final int DISTANCE = Integer.getInteger("vulkanmod112.flightDistance", 12);

    /** How long the flying part lasts. */
    private static final int SECONDS = Integer.getInteger("vulkanmod112.flightSeconds", 30);

    /**
     * How long to wait after the world appears before the route starts.
     *
     * The first seconds in a world are chunk building and nothing else, and a
     * frame time from them describes the builder rather than the renderer.
     */
    private static final int SETTLE = Integer.getInteger("vulkanmod112.flightSettle", 20);

    /** How many frames to keep. */
    private static final int SHOTS = Integer.getInteger("vulkanmod112.flightShots", 8);

    /**
     * Where to fly from, as {@code x,y,z}, when the world's own spawn is not
     * the ground the route wants. Empty means twelve blocks over the spawn.
     */
    private static final String POSITION = System.getProperty("vulkanmod112.flightPos", "").trim();

    /**
     * Whether to leave the game's debug screen up.
     *
     * Off by default, and that is not tidiness: the debug screen prints a
     * frame rate, and a frame rate is different in every frame — two pictures
     * of the same view would differ in the one place that has nothing to do
     * with what is being compared. On when the game's own profiler is what the
     * run is after, because that fills in only while the screen is open.
     */
    private static final boolean PROFILER =
            Boolean.parseBoolean(System.getProperty("vulkanmod112.flightProfiler", "false"));

    /**
     * A preset to load before the route starts, or empty to fly whatever the
     * config file happens to hold.
     *
     * Two runs are only comparable if both were flown with the same effects
     * switched on, and the config file is a thing that changes between them —
     * every session that opens the settings screen writes it.
     */
    private static final String PRESET = System.getProperty("vulkanmod112.flightPreset", "").trim();

    /**
     * Which hour to hold the sky at, or -1 to let the world keep its own time.
     *
     * A world saves its clock, so the second run of a route is flown minutes
     * later in the day than the first: the sun has moved, every shadow with
     * it, and a comparison between the two frames is mostly a comparison of
     * the hour. Held by default at mid-morning, which has a sun high enough to
     * light the ground and low enough to cast something.
     */
    private static final int HOUR = Integer.getInteger("vulkanmod112.flightHour", 9);

    /**
     * Weather to hold: 0 the world's own, 1 clear, 2 rain, 3 storm. Clear by
     * default, for the same reason as the hour.
     */
    private static final int WEATHER = Integer.getInteger("vulkanmod112.flightWeather", 1);

    /**
     * Give up after this long without reaching the world.
     *
     * A run nobody is watching must end. Without this, a world that fails to
     * load leaves a client sitting on a menu until somebody notices, which on
     * an unattended machine is the next morning.
     */
    private static final int PATIENCE = Integer.getInteger("vulkanmod112.flightPatience", 180);

    /**
     * Clouds: 0 off, 1 fast, 2 fancy, or -1 to leave the option alone.
     *
     * Off by default. A cloud drifts whatever else is held still, and between
     * two frames of the same view it is the largest difference in the picture
     * — measured, more than a quarter of the pixels — while having nothing to
     * do with anything a route is flown to compare.
     */
    private static final int CLOUDS = Integer.getInteger("vulkanmod112.flightClouds", 0);

    /** How far down to look, in degrees. Water wants a steeper angle than terrain. */
    private static final int PITCH = Integer.getInteger("vulkanmod112.flightPitch", 12);

    /** Whether to keep each frame twice, on two consecutive frames. */
    private static final boolean PAIRS =
            Boolean.parseBoolean(System.getProperty("vulkanmod112.flightPairs", "false"));

    private static final int TICKS_PER_SECOND = 20;

    private enum Stage {
        /** Nothing was asked for. */
        OFF,
        /** Waiting for the main menu to be up. */
        MENU,
        /** The world was asked for; waiting for it to arrive. */
        LOADING,
        /** In the world, letting the chunks around the spawn be built. */
        SETTLING,
        /** Flying the route. */
        FLYING,
        /** Finished; the client is on its way down. */
        DONE
    }

    private static Stage stage = ROUTE.isEmpty() ? Stage.OFF : Stage.MENU;

    /** Ticks spent in the current stage. */
    private static int ticks;

    /** Ticks spent since the mod started, for the patience limit. */
    private static int total;

    /** Where the route is flown from, taken from the world's own spawn. */
    private static double baseX;
    private static double baseY;
    private static double baseZ;

    /** Set by the tick, acted on by the frame: the name to save the next frame under. */
    private static String pendingShot;

    /** How many frames have been kept. */
    private static int shotsTaken;

    /** Where the camera was last tick, so that the frames between can be drawn between. */
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static float lastYaw;
    private static float lastPitch;
    private static boolean havePrevious;

    private Flight() {
    }

    /** Whether a flight was asked for at all. */
    public static boolean asked() {
        return !ROUTE.isEmpty();
    }

    /**
     * The state machine, one step per client tick.
     *
     * Every step is guarded and every failure ends the run rather than
     * retrying: this is a thing that runs while nobody is looking, and a loop
     * that keeps trying is a loop that reports nothing at all.
     */
    public static void tick() {
        if (stage == Stage.OFF || stage == Stage.DONE) {
            return;
        }
        try {
            step();
        } catch (Throwable t) {
            VulkanMod112.LOGGER.error("Flight failed and the run is being ended", t);
            finish("failed");
        }
    }

    private static void step() {
        Minecraft mc = Minecraft.getMinecraft();
        ticks++;
        total++;
        if (stage != Stage.FLYING && total > PATIENCE * TICKS_PER_SECOND) {
            VulkanMod112.LOGGER.error("Flight gave up after {} seconds at stage {}",
                    PATIENCE, stage);
            finish("gave up at " + stage);
            return;
        }
        switch (stage) {
            case MENU:
                // The menu is the one moment where the game is certainly idle
                // and certainly has no world. Asking earlier means asking
                // while the resources are still being loaded.
                if (mc.currentScreen instanceof GuiMainMenu) {
                    launchWorld(mc);
                }
                break;
            case LOADING:
                if (mc.world != null && mc.player != null && mc.currentScreen == null) {
                    enterWorld(mc);
                }
                break;
            case SETTLING:
                // Held in place for the whole wait rather than left alone,
                // because the player falls otherwise and the route would start
                // from somewhere it did not choose.
                place(mc, baseX, baseY, baseZ, yawAt(0), pitchAt(0), true);
                if (ticks >= SETTLE * TICKS_PER_SECOND) {
                    stage = Stage.FLYING;
                    ticks = 0;
                    Diagnostics.flushNow("flight " + TAG + " route " + ROUTE + " begins, seed "
                            + SEED + ", distance " + DISTANCE);
                }
                break;
            case FLYING:
                fly(mc);
                break;
            default:
                break;
        }
    }

    private static void launchWorld(Minecraft mc) {
        // Commands on, structures on, spectator: a spectator has no collision
        // and no physics, so a route is exactly the line it was written as
        // rather than the line the terrain allowed.
        WorldSettings settings = new WorldSettings(
                SEED, GameType.SPECTATOR, true, false, WorldType.DEFAULT);
        settings.enableCommands();
        String folder = "flight-" + SEED;
        VulkanMod112.LOGGER.info("Flight {} making world {} from seed {}", TAG, folder, SEED);
        mc.launchIntegratedServer(folder, "VulkanMod112 flight", settings);
        stage = Stage.LOADING;
        ticks = 0;
    }

    private static void enterWorld(Minecraft mc) {
        // Focus is not a thing an unattended run can promise, and a game that
        // pauses when the window loses it would flat-line halfway through.
        mc.gameSettings.pauseOnLostFocus = false;
        // The debug screen is where the game keeps its own profiler, and it
        // fills in only while it is open — this is the one measurement that
        // came back empty for want of a key nobody was there to press. It is
        // also printed over the top third of every frame, so it is asked for
        // rather than assumed.
        mc.gameSettings.showDebugInfo = PROFILER;
        applyPreset(mc);
        if (CLOUDS >= 0) {
            mc.gameSettings.clouds = CLOUDS;
        }
        // Pinned after the preset, because a preset does not write these and
        // a route that is not flown at a fixed hour compares two skies rather
        // than two builds.
        if (HOUR >= 0) {
            VulkanConfig.setTimeControl(2);
            VulkanConfig.setTimeOfDay(HOUR);
        }
        if (WEATHER >= 0) {
            VulkanConfig.setWeatherControl(WEATHER);
        }
        if (mc.gameSettings.renderDistanceChunks != DISTANCE) {
            mc.gameSettings.renderDistanceChunks = DISTANCE;
            mc.renderGlobal.loadRenderers();
        }
        // Twelve blocks over the spawn: high enough to be out of a tree and
        // low enough to still have the ground fill the view, and derived from
        // the world rather than written down, so the same route works on any
        // seed it is pointed at.
        // The world's spawn point, not the player's position: a player is put
        // down at a random spot inside the spawn area, so two runs of the same
        // seed start tens of blocks apart and the frames cannot be compared.
        // The spawn point itself is a property of the seed and does not move.
        net.minecraft.util.math.BlockPos spawn = mc.world.getSpawnPoint();
        baseX = spawn.getX() + 0.5;
        baseY = spawn.getY() + 12.0;
        baseZ = spawn.getZ() + 0.5;
        String[] given = POSITION.isEmpty() ? null : POSITION.split(",");
        if (given != null && given.length == 3) {
            baseX = Double.parseDouble(given[0].trim());
            baseY = Double.parseDouble(given[1].trim());
            baseZ = Double.parseDouble(given[2].trim());
        }
        // The biome goes in the log because choosing where to fly is the part
        // of setting up a route that cannot be done from the source: a seed
        // says nothing about whether its spawn has water, grass or a snowfield
        // in front of it, and those are what the routes are for.
        String biome = mc.world.getBiome(new net.minecraft.util.math.BlockPos(
                baseX, baseY, baseZ)).getBiomeName();
        VulkanMod112.LOGGER.info("Flight {} in the world at {} {} {}, biome {}, settling {}s",
                TAG, (int) baseX, (int) baseY, (int) baseZ, biome, SETTLE);
        stage = Stage.SETTLING;
        ticks = 0;
    }

    /**
     * Loads a named preset, if one was asked for.
     *
     * Applied after the world is there rather than at startup, because two of
     * these presets read the machine they are on — how many cores there are to
     * build chunks with, how much memory the card admits to — and one of them
     * moves the game's own video settings.
     */
    private static void applyPreset(Minecraft mc) {
        if (PRESET.isEmpty()) {
            return;
        }
        String name = PRESET.toLowerCase(java.util.Locale.ROOT);
        if ("beautiful".equals(name)) {
            VulkanPresets.beautiful(mc);
        } else if ("stable".equals(name)) {
            VulkanPresets.stable();
        } else if ("balanced".equals(name)) {
            VulkanPresets.balanced(mc);
        } else if ("performance".equals(name)) {
            VulkanPresets.performance(mc);
        } else if ("potato".equals(name)) {
            VulkanPresets.potato(mc);
        } else {
            VulkanMod112.LOGGER.warn("Flight was given an unknown preset {}", PRESET);
            return;
        }
        VulkanMod112.LOGGER.info("Flight {} loaded preset {}", TAG, PRESET);
    }

    private static void fly(Minecraft mc) {
        int length = SECONDS * TICKS_PER_SECOND;
        // Measured from the first tick of the route rather than from zero, so
        // that the first frame is at nothing and the last is at exactly one
        // whole turn. Off by a single tick, the two ends of a spin are two
        // slightly different views and the one comparison this route exists
        // for compares a fault with a rounding error.
        float t = length <= 1 ? 1.0f
                : Math.min(1.0f, (float) (ticks - 1) / (float) (length - 1));
        double x = baseX;
        double z = baseZ;
        if ("line".equals(ROUTE)) {
            // Eight blocks a second, which is a little over sprint speed and
            // is what makes chunks arrive at the rate a player would meet.
            x += ticks * 0.4;
        }
        // Decided before the camera is placed, because a tick that is about
        // to be photographed is placed differently: pinned rather than
        // interpolated, so the picture is of the angle the route names.
        //
        // Evenly spaced, ends included: the first and last frames of a spin
        // are the same view, and that pair is the whole point of the route.
        boolean shooting = shotsTaken < SHOTS && ticks >= shotTick(shotsTaken, length);
        if (shooting) {
            pendingShot = "flight-" + TAG + "-" + ROUTE + "-"
                    + String.format("%02d", shotsTaken);
            shotsTaken++;
        }
        place(mc, x, baseY, z, yawAt(t), pitchAt(t), shooting);
        // Five ticks of grace after the last frame is asked for. The frame
        // that saves it has not run yet at this point — a picture is taken at
        // the end of a frame and this is the tick before one — so closing the
        // game here loses exactly the frame the route was flown to reach.
        if (ticks >= length + 5) {
            finish("finished");
        }
    }

    /**
     * Puts the camera exactly where it is asked, this tick and last tick.
     *
     * The previous values matter as much as the current ones: everything drawn
     * is interpolated between the two, so setting only the current pair leaves
     * the camera sliding towards it across the frame and a screenshot taken
     * from a named angle is not of that angle.
     */
    private static void place(Minecraft mc, double x, double y, double z, float yaw, float pitch,
                              boolean pinned) {
        if (mc.player == null) {
            return;
        }
        // Wrapped before it is handed over, and this is not tidiness.
        //
        // The game normalises a player's yaw itself, once a tick. Hand it 405
        // and next tick the current angle is 45 while the previous one is
        // still 405 — and every frame between the two ticks is drawn at a
        // mixture of the two, which is a camera whipping backwards through the
        // entire circle. The route reported 45 and the picture was of 71, and
        // a fault of that shape is indistinguishable from the renderer drawing
        // the wrong thing, which is what this harness exists to look for.
        yaw = net.minecraft.util.math.MathHelper.wrapDegrees(yaw);
        // Where the camera was a tick ago, so the frames in between are drawn
        // in between.
        //
        // Pinning both ends of that pair is what a photograph needs and what
        // ordinary motion must not have: the route moves the camera twenty
        // times a second, and with nothing to interpolate towards, five
        // hundred frames a second look exactly like twenty. Worse than looking
        // wrong, it measures wrong — everything here that skips work when the
        // camera has not moved gets an easier ride than it would in a game
        // somebody is playing.
        //
        // The previous angle is carried as a difference rather than as the
        // last value, so that a route crossing the half-turn does not hand the
        // interpolation a pair three hundred and sixty degrees apart and spin
        // the camera backwards through a whole circle for one tick.
        double fromX = pinned || !havePrevious ? x : lastX;
        double fromY = pinned || !havePrevious ? y : lastY;
        double fromZ = pinned || !havePrevious ? z : lastZ;
        float fromYaw = pinned || !havePrevious ? yaw
                : yaw - net.minecraft.util.math.MathHelper.wrapDegrees(yaw - lastYaw);
        float fromPitch = pinned || !havePrevious ? pitch : lastPitch;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastYaw = yaw;
        lastPitch = pitch;
        havePrevious = true;
        mc.player.setPositionAndRotation(x, y, z, yaw, pitch);
        mc.player.prevPosX = fromX;
        mc.player.prevPosY = fromY;
        mc.player.prevPosZ = fromZ;
        mc.player.lastTickPosX = fromX;
        mc.player.lastTickPosY = fromY;
        mc.player.lastTickPosZ = fromZ;
        mc.player.prevRotationYaw = fromYaw;
        mc.player.prevRotationPitch = fromPitch;
        mc.player.rotationYawHead = yaw;
        mc.player.prevRotationYawHead = fromYaw;
        mc.player.motionX = 0.0;
        mc.player.motionY = 0.0;
        mc.player.motionZ = 0.0;
    }

    /**
     * Which tick frame {@code index} is taken on, ends included.
     *
     * Spacing by a step and testing the remainder misses both ends — the first
     * tick is one rather than zero, and the last lands wherever the division
     * left it. Both ends are the interesting ones here.
     */
    private static int shotTick(int index, int length) {
        if (SHOTS <= 1 || length <= 1) {
            return 1;
        }
        return 1 + (int) ((long) index * (length - 1) / (SHOTS - 1));
    }

    private static float yawAt(float t) {
        if ("spin".equals(ROUTE)) {
            // A whole turn, so the last frame is the first one again.
            return 45.0f + 360.0f * t;
        }
        if ("line".equals(ROUTE)) {
            // Along the line of travel, which is what makes the chunks that
            // are arriving the ones being looked at.
            return -90.0f;
        }
        return 45.0f;
    }

    private static float pitchAt(float t) {
        // Slightly down at every angle by default: level puts half the screen
        // in sky, and sky is the one thing here that costs nothing to draw.
        return PITCH;
    }

    /**
     * The frame hook, which is where a screenshot has to be taken.
     *
     * A tick knows which frame it wants and cannot save one — at tick time the
     * picture belongs to the frame before it, and half of it may not have been
     * drawn. The tick leaves a name here and the frame acts on it.
     */
    public static void onFrameEnd() {
        if (stage != Stage.FLYING || pendingShot == null) {
            return;
        }
        String name = pendingShot;
        // The same frame again on the very next one, when asked. A camera
        // turning at eighteen degrees a second moves a twentieth of a degree
        // between two frames, so a pair that differs by more than a rounding
        // error says the picture being read is not the picture just drawn —
        // which is the one thing that cannot be told apart from a fault in the
        // renderer by looking at a single frame.
        if (PAIRS && !name.endsWith("b")) {
            pendingShot = name + "b";
        } else {
            pendingShot = null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        try {
            ScreenShotHelper.saveScreenshot(mc.gameDir, name + ".png",
                    mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            // The camera goes in the line with the frame. Two frames the route
            // says are of the same view have to be checkable against something
            // other than the route saying so.
            String where = mc.player == null ? "no player"
                    : String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f yaw %.3f pitch %.3f",
                            mc.player.posX, mc.player.posY, mc.player.posZ,
                            mc.player.rotationYaw, mc.player.rotationPitch);
            VulkanMod112.LOGGER.info("Flight {} frame {} at {}", TAG, name, where);
            Diagnostics.flushNow("flight " + TAG + " frame " + name + " at " + where);
        } catch (Throwable t) {
            VulkanMod112.LOGGER.warn("Flight could not keep frame {}", name, t);
        }
    }

    private static void finish(String why) {
        if (stage == Stage.DONE) {
            return;
        }
        stage = Stage.DONE;
        VulkanMod112.LOGGER.info("Flight {} {} after {} frames kept", TAG, why, shotsTaken);
        Diagnostics.flushNow("flight " + TAG + " " + why);
        try {
            Minecraft.getMinecraft().shutdown();
        } catch (Throwable t) {
            VulkanMod112.LOGGER.warn("Flight could not close the game", t);
        }
    }
}
