package net.vulkanmod112.core;

import net.minecraft.launchwrapper.Launch;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Which patch groups are allowed to be applied, kept in a file of its own.
 *
 * <h2>Why not in the settings file</h2>
 *
 * This is read during class transformation, before the game exists — before
 * Forge's configuration classes can be loaded at all, and long before this
 * mod's own {@code VulkanConfig}. Anything read that early has to be a plain
 * file read with plain {@code java.util}, so it is one, and it is separate so
 * that nothing here can drag the settings machinery into the coremod.
 *
 * <h2>The two things it remembers</h2>
 *
 * <ul>
 * <li><b>off</b> — the user turned this group off in the settings screen.</li>
 * <li><b>quarantine</b> — a patch in this group failed to apply on an earlier
 *     launch, so the group stands down until somebody asks for it again. The
 *     reason is kept beside it, because "Sky and Weather is off" is not worth
 *     much and "Sky and Weather stood down: SceneBloomMixin could not find
 *     renderWorldPass" is.</li>
 * </ul>
 *
 * Both take effect at the next launch and only then. A class is patched once,
 * as it loads, and by the time there is a screen to change this on, every class
 * this mod touches has been loaded for minutes. There is no honest way to make
 * the switch immediate, so the screen says so rather than pretending.
 */
public final class VulkanPatchState {

    private static final String FILE_NAME = "vulkanmod112-patches.cfg";
    private static final String OFF_PREFIX = "off.";
    private static final String QUARANTINE_PREFIX = "quarantine.";

    /**
     * Where the gate leaves a note of what it did, for the settings screen.
     *
     * Through the launch blackboard rather than a static field: the coremod and
     * the mod are not guaranteed to be the same class on the same loader, and a
     * static that silently reads empty on the other side would make the screen
     * quietly lie about what happened. The blackboard is the one channel both
     * halves are promised to share.
     */
    private static final String BLACKBOARD_KEY = "vulkanmod112.patches.skipped";

    private static Properties cache;

    private VulkanPatchState() {
    }

    private static File file() {
        File home = Launch.minecraftHome;
        File config = home != null ? new File(home, "config") : new File("config");
        return new File(config, FILE_NAME);
    }

    private static synchronized Properties read() {
        if (cache != null) {
            return cache;
        }
        Properties loaded = new Properties();
        File file = file();
        if (file.isFile()) {
            InputStream in = null;
            try {
                in = new FileInputStream(file);
                loaded.load(in);
            } catch (IOException e) {
                // An unreadable file must not stop the game: every group simply
                // stays on, which is what a fresh install does anyway.
                System.out.println("[VulkanMod112] Could not read " + FILE_NAME
                        + "; every patch group stays enabled. " + e);
            } finally {
                close(in);
            }
        }
        cache = loaded;
        return cache;
    }

    private static synchronized void write() {
        Properties properties = read();
        File file = file();
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            System.out.println("[VulkanMod112] Could not create " + parent + "; "
                    + "patch group changes will not survive this session.");
            return;
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            properties.store(out, "Which of VulkanMod112's class patches may be applied. "
                    + "Changes take effect the next time the game starts.");
        } catch (IOException e) {
            System.out.println("[VulkanMod112] Could not write " + FILE_NAME + ". " + e);
        } finally {
            close(out);
        }
    }

    private static void close(java.io.Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a failed close on a settings file.
            }
        }
    }

    /** Whether the user has switched this group off by hand. */
    public static boolean isEnabled(String group) {
        return !"true".equals(read().getProperty(OFF_PREFIX + group));
    }

    public static void setEnabled(String group, boolean enabled) {
        Properties properties = read();
        if (enabled) {
            properties.remove(OFF_PREFIX + group);
        } else {
            properties.setProperty(OFF_PREFIX + group, "true");
        }
        write();
    }

    /** Why this group stood down on an earlier launch, or null if it did not. */
    public static String quarantineReason(String group) {
        String reason = read().getProperty(QUARANTINE_PREFIX + group);
        return reason == null || reason.isEmpty() ? null : reason;
    }

    /**
     * Writes down that a patch in this group could not be applied.
     *
     * Called from the error handler while the game is already on its way down.
     * The point is not to save this launch — by then the class is half built
     * and the only safe thing to do with it is to stop — but to make the next
     * one start.
     */
    public static void quarantine(String group, String reason) {
        read().setProperty(QUARANTINE_PREFIX + group, reason);
        write();
    }

    public static void clearQuarantine(String group) {
        read().remove(QUARANTINE_PREFIX + group);
        write();
    }

    /** Notes that a group was not applied this launch, and why. */
    @SuppressWarnings("unchecked")
    static void recordSkipped(String group, String why) {
        Map<String, Object> blackboard = Launch.blackboard;
        if (blackboard == null) {
            return;
        }
        Object existing = blackboard.get(BLACKBOARD_KEY);
        Map<String, String> skipped = existing instanceof Map
                ? (Map<String, String>) existing
                : new LinkedHashMap<String, String>();
        skipped.put(group, why);
        blackboard.put(BLACKBOARD_KEY, skipped);
    }

    /**
     * The groups that were not applied this launch, as "group: why".
     *
     * Read by the settings screen. Empty is the normal answer and means every
     * group the user left on was applied.
     */
    @SuppressWarnings("unchecked")
    public static List<String> skippedThisLaunch() {
        List<String> lines = new ArrayList<String>();
        Map<String, Object> blackboard = Launch.blackboard;
        Object existing = blackboard == null ? null : blackboard.get(BLACKBOARD_KEY);
        if (existing instanceof Map) {
            for (Map.Entry<String, String> entry : ((Map<String, String>) existing).entrySet()) {
                lines.add(entry.getKey() + ": " + entry.getValue());
            }
        }
        return lines;
    }

    /** Whether this group was applied this launch. */
    @SuppressWarnings("unchecked")
    public static boolean wasApplied(String group) {
        Map<String, Object> blackboard = Launch.blackboard;
        Object existing = blackboard == null ? null : blackboard.get(BLACKBOARD_KEY);
        return !(existing instanceof Map) || !((Map<String, String>) existing).containsKey(group);
    }
}
