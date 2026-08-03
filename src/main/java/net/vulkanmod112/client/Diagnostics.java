package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.vulkanmod112.VulkanBridge;
import net.vulkanmod112.VulkanLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Verbose diagnostics written to their own file, so a report can be handed over
 * whole instead of reconstructed from questions.
 *
 * The header is written once and covers the things that decide whether this mod
 * can work at all: versions, installed mods, GPU, driver paths. After that a
 * snapshot is appended on a timer with the live state of the renderer and the
 * settings that affect it.
 *
 * Off by default; nothing here runs unless it is enabled.
 */
public final class Diagnostics {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Diagnostics");
    private static final String FILE_NAME = "vulkanmod112-diagnostics.log";
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("HH:mm:ss");

    private static PrintWriter writer;
    private static boolean failed;
    private static boolean headerWritten;
    private static long lastSnapshotNanos;

    /**
     * Guards the writer and the backlog. Snapshots come from the client thread,
     * but mirrored log lines come from whichever thread wrote them — chunk
     * builders included — so the two cannot be allowed to interleave mid-line.
     */
    private static final Object LOCK = new Object();

    /**
     * Lines this mod logged before the file existed.
     *
     * Everything worth reading about a failed start happens during startup:
     * which device was chosen, what the driver reported, where initialization
     * gave up. The file is opened on the first frame, which is far too late for
     * any of it, so the lines are held until then. Bounded because a mod that
     * never gets to draw must not also fill the heap.
     */
    private static final java.util.ArrayDeque<String> BACKLOG = new java.util.ArrayDeque<String>();
    private static final int BACKLOG_LIMIT = 4000;
    private static boolean capturing;

    private Diagnostics() {
    }

    /**
     * Starts mirroring this mod's own log into the diagnostics file.
     *
     * The point of that file is to be the one thing worth asking a player for,
     * and it was not: the per-second snapshots said what the state was and
     * never what the mod had done to get there, so every report still needed
     * the game's own log beside it. Now both live in one file, in order.
     *
     * Only lines from this mod are taken. The game's log keeps everything else,
     * and a diagnostics file with the whole of Forge in it would be no easier
     * to read than what it replaced.
     */
    public static void startCapture() {
        if (capturing || failed || !enabled()) {
            return;
        }
        capturing = true;
        try {
            org.apache.logging.log4j.core.LoggerContext context =
                    (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
            org.apache.logging.log4j.core.config.Configuration config = context.getConfiguration();
            @SuppressWarnings("deprecation")
            org.apache.logging.log4j.core.Appender mirror =
                    new org.apache.logging.log4j.core.appender.AbstractAppender(
                            "VulkanMod112Diagnostics", null, null, true) {
                        @Override
                        public void append(org.apache.logging.log4j.core.LogEvent event) {
                            mirror(event);
                        }
                    };
            mirror.start();
            config.getRootLogger().addAppender(mirror, org.apache.logging.log4j.Level.ALL, null);
            context.updateLoggers();
        } catch (Throwable t) {
            // A logging convenience is never worth taking the mod down for.
            capturing = false;
            LOGGER.warn("Could not mirror the mod's log into the diagnostics file", t);
        }
    }

    private static void mirror(org.apache.logging.log4j.core.LogEvent event) {
        String name = event.getLoggerName();
        if (name == null || !name.startsWith("VulkanMod112")) {
            return;
        }
        StringBuilder line = new StringBuilder()
                .append('[').append(STAMP.format(new Date())).append("] ")
                .append(event.getLevel()).append(' ')
                .append(name).append(": ")
                .append(event.getMessage().getFormattedMessage());
        Throwable thrown = event.getThrown();
        if (thrown != null) {
            java.io.StringWriter trace = new java.io.StringWriter();
            thrown.printStackTrace(new PrintWriter(trace));
            line.append(System.lineSeparator()).append(trace);
        }
        String text = line.toString();
        synchronized (LOCK) {
            if (writer != null) {
                writer.println(text);
                writer.flush();
            } else if (BACKLOG.size() < BACKLOG_LIMIT) {
                BACKLOG.add(text);
            }
        }
    }

    public static boolean enabled() {
        return Boolean.getBoolean("vulkanmod112.ultraLog") || VulkanConfig.isUltraLogEnabled();
    }

    /** Called once per frame; writes at most one snapshot per interval. */
    public static void tick() {
        if (failed || !enabled()) {
            return;
        }
        // Ultra logging is usually switched on in the middle of a session,
        // after something has gone wrong — and startCapture() only runs at
        // startup, so it found the setting off and never attached. The file
        // then filled with snapshots and not one line of the mod's own log:
        // no startup lines, no settings changes, no warnings. A tester's
        // report arrived exactly like that, and the missing half was the half
        // that says what they did. Cheap to repeat; it returns at once once
        // the appender is attached.
        startCapture();
        long now = System.nanoTime();
        long intervalNanos = VulkanConfig.getUltraLogSeconds() * 1_000_000_000L;
        if (headerWritten && now - lastSnapshotNanos < intervalNanos) {
            return;
        }
        lastSnapshotNanos = now;
        try {
            PrintWriter out = open();
            if (out == null) {
                return;
            }
            synchronized (LOCK) {
                writeSnapshot(out);
                out.flush();
            }
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("Diagnostics logging disabled after an error", t);
        }
    }

    /** Forces a final snapshot, e.g. right after something went wrong. */
    public static void flushNow(String reason) {
        if (failed || !enabled()) {
            return;
        }
        lastSnapshotNanos = 0;
        try {
            PrintWriter out = open();
            if (out != null) {
                out.println("[" + STAMP.format(new Date()) + "] EVENT: " + reason);
                out.flush();
            }
        } catch (Throwable ignored) {
            // Diagnostics must never be the reason something breaks.
        }
    }

    private static PrintWriter open() throws IOException {
        if (writer != null) {
            return writer;
        }
        File directory = new File(Minecraft.getMinecraft().gameDir, "logs");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            failed = true;
            return null;
        }
        Writer file = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(new File(directory, FILE_NAME), false),
                Charset.forName("UTF-8"));
        synchronized (LOCK) {
            writer = new PrintWriter(file);
            // Header first so the file always opens with what machine this is,
            // then everything the mod said before the file existed, in the
            // order it said it, and only then the first snapshot.
            writeHeader(writer);
            headerWritten = true;
            while (!BACKLOG.isEmpty()) {
                writer.println(BACKLOG.poll());
            }
            writer.flush();
        }
        return writer;
    }

    private static void writeHeader(PrintWriter out) {
        Minecraft mc = Minecraft.getMinecraft();
        out.println("VulkanMod112 diagnostics");
        out.println("========================");
        out.println("started: " + new Date());
        out.println("minecraft: " + net.minecraftforge.common.ForgeVersion.mcVersion
                + ", forge: " + net.minecraftforge.common.ForgeVersion.getVersion());
        out.println("java: " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.name") + ")");
        // os.name is not to be believed on Windows past 8: the system reports an
        // old version to any program without a manifest saying it knows about
        // the new ones, and Java 8 has no such manifest. Every Windows 10 and 11
        // machine says "Windows 8.1" here. Said out loud so nobody reads a log
        // and starts explaining a bug with the wrong operating system.
        String os = System.getProperty("os.name");
        boolean lies = os != null && os.startsWith("Windows")
                && (os.contains("8") || os.contains("7"));
        out.println("os: " + os + " " + System.getProperty("os.arch")
                + (lies ? " (as Java 8 sees it — Windows hides its real version"
                        + " from programs this old, so 10 and 11 both show up here)" : "")
                + ", version " + System.getProperty("os.version"));
        out.println();

        out.println("mods:");
        try {
            for (ModContainer mod : Loader.instance().getActiveModList()) {
                out.println("  " + mod.getModId() + " " + mod.getVersion());
            }
        } catch (Throwable t) {
            out.println("  (mod list unavailable: " + t + ")");
        }
        out.println();

        out.println("opengl:");
        out.println("  renderer: " + safeGlString(org.lwjgl.opengl.GL11.GL_RENDERER));
        out.println("  vendor: " + safeGlString(org.lwjgl.opengl.GL11.GL_VENDOR));
        out.println("  version: " + safeGlString(org.lwjgl.opengl.GL11.GL_VERSION));
        out.println("  vbo enabled: " + mc.gameSettings.useVbo);
        out.println();
    }

    private static String safeGlString(int name) {
        try {
            String value = org.lwjgl.opengl.GL11.glGetString(name);
            return value == null ? "(null)" : value;
        } catch (Throwable t) {
            return "(unavailable: " + t + ")";
        }
    }

    /**
     * The counters the F3 overlay shows, taken from the same methods it calls.
     *
     * These were missing, and their absence cost a day. A frame breakdown says
     * how much of the frame this mod accounts for; when the answer came back
     * "four percent", there was nothing in the report to say what the other
     * ninety-six were doing. The entity line is the one that matters most: at
     * render distance 64 the integrated server keeps a very large number of
     * chunks loaded, and whether the client walks all of their entities every
     * frame is a question these numbers answer directly.
     *
     * Every call here is one the game itself makes each frame while F3 is held,
     * so none of it is work the game would not otherwise do.
     */
    private static void writeVanillaCounters(PrintWriter out, Minecraft mc) {
        if (mc.world == null || mc.renderGlobal == null) {
            return;
        }
        try {
            out.println("  f3 chunks: " + mc.renderGlobal.getDebugInfoRenders());
            out.println("  f3 entities: " + mc.renderGlobal.getDebugInfoEntities());
            out.println("  f3 particles/tiles: P: " + mc.effectRenderer.getStatistics()
                    + ". T: " + mc.world.getDebugLoadedEntities());
            out.println("  f3 world: " + mc.world.getProviderName());
        } catch (Throwable t) {
            out.println("  f3: unavailable (" + t + ")");
        }
    }

    /**
     * The Shift+F3 pie chart, as text.
     *
     * The chart itself is drawn in GUI coordinates, so on a large display it
     * ends up a few hundred pixels across with unreadable labels — the one time
     * it was needed, it could not be read. The numbers behind it are plain
     * objects, so they can simply be written down instead.
     *
     * Only runs when the game has the profiler on, which is exactly when the
     * chart is open; the profiler's own section calls are boolean-guarded
     * no-ops otherwise, so nothing here costs anything the rest of the time.
     */
    private static void writeProfilerTree(PrintWriter out, Minecraft mc) {
        if (!mc.profiler.profilingEnabled) {
            return;
        }
        try {
            StringBuilder tree = new StringBuilder();
            appendProfilerSection(tree, mc, "root", 0);
            if (tree.length() == 0) {
                // The game clears the profiler whenever the chart is toggled on,
                // so a snapshot can land before any section has closed. Saying so
                // beats a bare heading that reads like the profiler found nothing.
                out.println("  profiler: on, but no section has been recorded yet");
                return;
            }
            out.println("  profiler (open the chart with Shift+F3; these are its numbers):");
            out.print(tree);
        } catch (Throwable t) {
            out.println("  profiler: unavailable (" + t + ")");
        }
    }

    /**
     * Depth and share limits keep this to the few lines that carry the answer.
     *
     * {@code share} is the fraction of the whole frame this section's parent
     * accounts for, carried down and multiplied. The game's own
     * {@code totalUsePercentage} cannot be used for it: asking the profiler for
     * a nested path renormalises against that path, so it comes back equal to
     * the share of the parent and a section reads as far larger than it is. A
     * walk that is 94% of a stage that is 51% of the frame printed as 94% of
     * the frame, and that number nearly bought a wrong conclusion.
     */
    private static void appendProfilerSection(StringBuilder out, Minecraft mc, String path, int depth) {
        appendProfilerSection(out, mc, path, depth, 1.0);
    }

    private static void appendProfilerSection(StringBuilder out, Minecraft mc, String path, int depth,
                                              double share) {
        if (depth > 3) {
            return;
        }
        java.util.List<net.minecraft.profiler.Profiler.Result> results = mc.profiler.getProfilingData(path);
        if (results == null || results.size() < 2) {
            return;
        }
        // Index 0 is the synthetic "unspecified" remainder; the rest are real.
        for (int i = 1; i < results.size(); i++) {
            net.minecraft.profiler.Profiler.Result result = results.get(i);
            if (result.usePercentage < 1.0) {
                continue;
            }
            out.append("    ");
            for (int d = 0; d < depth; d++) {
                out.append("  ");
            }
            double ofFrame = share * result.usePercentage / 100.0;
            out.append(String.format("%-28s %5.1f%% of parent, %5.1f%% of frame%n",
                    result.profilerName, result.usePercentage, ofFrame * 100.0));
            appendProfilerSection(out, mc, path + "." + result.profilerName, depth + 1, ofFrame);
        }
    }

    private static void writeSnapshot(PrintWriter out) {
        Minecraft mc = Minecraft.getMinecraft();
        out.println("[" + STAMP.format(new Date()) + "] snapshot");
        out.println("  fps: " + Minecraft.getDebugFPS()
                + ", world: " + (mc.world == null ? "none" : "loaded")
                + ", gui: " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName()));
        // Before the timings, not after: every number below is about a scene,
        // and this is the only line that says which scene.
        out.println("  " + SessionLog.cameraLine());
        out.println("  " + TerrainHooks.stats());
        out.println("  " + EntityCapture.stats());
        out.println("  " + EntityGeometry.stats());
        out.println("  " + BlockLightSources.stats());
        out.println("  " + TerrainHooks.vanillaLayerStats());
        out.println("  " + VanillaFrame.stats());
        out.println("  " + VanillaFrame.walkStats());
        out.println("  " + VanillaFrame.ownWalkStats());
        out.println("  " + VanillaFrame.rebuildNearStats());
        // The two settings currently under A/B. Without them in the snapshot a
        // run has to be matched to a configuration by memory, and the last two
        // comparisons both turned on which of the two arms a number came from.
        out.println(String.format(
                "  under test: frames in flight %d, own visibility walk %s, rebuild filter %s",
                VulkanConfig.getFramesInFlight(),
                VulkanConfig.isOwnVisibilityWalk() ? "on" : "off",
                VulkanConfig.isFastRebuildNear() ? "on" : "off"));
        out.println("  " + DynamicLights.stats());
        out.println("  " + WeatherHooks.stats());
        out.println("  " + ExplosionParticles.stats());
        out.println("  " + ChunkBuildStats.stats());
        out.println("  " + MaterialRuns.stats());
        out.println("  " + FrameGraph.stats());
        writeVanillaCounters(out, mc);
        writeProfilerTree(out, mc);

        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        if (bridge == null || !bridge.isInitialized()) {
            out.println("  vulkan: not initialized");
        } else {
            try {
                out.print(bridge.diagnosticsReport());
            } catch (Throwable t) {
                out.println("  vulkan: report failed: " + t);
            }
        }

        out.println("  settings: render distance " + mc.gameSettings.renderDistanceChunks
                + ", mipmaps " + mc.gameSettings.mipmapLevels
                + ", graphics " + (mc.gameSettings.fancyGraphics ? "fancy" : "fast")
                + ", vsync " + mc.gameSettings.enableVsync
                + ", fps limit " + mc.gameSettings.limitFramerate
                + ", particles " + mc.gameSettings.particleSetting
                + ", entity shadows " + mc.gameSettings.entityShadows);
        out.println("  mod settings: terrain " + VulkanConfig.isTerrainEnabled()
                + ", entity distance " + VulkanConfig.getEntityDistance()
                + ", block entity distance " + VulkanConfig.getTileEntityDistance()
                + ", animations " + VulkanConfig.areAnimationsEnabled()
                // "asked for", not "depth blit": the driver decides whether it
                // happens, and the answer is on the targets line below. Reading
                // this one as the truth cost a morning.
                + ", depth blit asked for " + VulkanConfig.isDepthBlitEnabled()
                + ", culling " + VulkanConfig.isCullingEnabled());
        writeModSettings(out);
        // Only once the screen that causes it has been opened, so an ordinary
        // session says nothing about it at all.
        if (ResourcePackIcons.touched()) {
            out.println("  " + ResourcePackIcons.describe());
        }
        out.println("  memory: " + used() + " MiB used of " + max() + " MiB");
        out.println();
    }

    /**
     * Everything the renderer was configured with, in one block.
     *
     * Wrapped by hand rather than left to run off the side: this is a file a
     * tester opens in whatever editor they have, and forty settings on one
     * line is a line nobody reads. Sorted, so the same setting is in the same
     * place in every report and two of them can be compared by eye.
     */
    private static void writeModSettings(PrintWriter out) {
        java.util.List<String> settings = VulkanConfig.describePublished();
        out.println("  renderer settings (" + settings.size() + ", all of them, "
                + "0 or false means the effect is not running):");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < settings.size(); i++) {
            String entry = settings.get(i);
            if (line.length() > 0 && line.length() + entry.length() > 88) {
                out.println("    " + line);
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(entry);
        }
        if (line.length() > 0) {
            out.println("    " + line);
        }
        // Directly under the values, because this is the question the values
        // are read to answer and reading them without it is how a session was
        // spent concluding that an effect was broken when it was switched off.
        String inert = SettingsHealth.describeInert();
        if (inert != null) {
            out.println("    on but doing nothing: " + inert);
        }
    }

    private static long used() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    private static long max() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    public static void close() {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }
}
