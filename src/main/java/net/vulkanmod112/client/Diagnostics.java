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

    private Diagnostics() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean("vulkanmod112.ultraLog") || VulkanConfig.isUltraLogEnabled();
    }

    /** Called once per frame; writes at most one snapshot per interval. */
    public static void tick() {
        if (failed || !enabled()) {
            return;
        }
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
            if (!headerWritten) {
                writeHeader(out);
                headerWritten = true;
            }
            writeSnapshot(out);
            out.flush();
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
        writer = new PrintWriter(file);
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
        out.println("os: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
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

    private static void writeSnapshot(PrintWriter out) {
        Minecraft mc = Minecraft.getMinecraft();
        out.println("[" + STAMP.format(new Date()) + "] snapshot");
        out.println("  fps: " + Minecraft.getDebugFPS()
                + ", world: " + (mc.world == null ? "none" : "loaded")
                + ", gui: " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName()));
        out.println("  " + TerrainHooks.stats());
        out.println("  " + TerrainHooks.vanillaLayerStats());

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
                + ", depth blit " + VulkanConfig.isDepthBlitEnabled()
                + ", culling " + VulkanConfig.isCullingEnabled());
        out.println("  memory: " + used() + " MiB used of " + max() + " MiB");
        out.println();
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
