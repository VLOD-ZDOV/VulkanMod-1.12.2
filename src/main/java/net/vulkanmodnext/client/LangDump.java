package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.vulkanmodnext.client.gui.Lang;

/**
 * Writes the English language file from the settings screen as it is actually
 * built.
 *
 * The keys are derived from the English text in the source, so the file cannot
 * be maintained by hand without drifting from the code that asks for it — a
 * renamed option silently orphans its translations, and a hand-added key with
 * one word different is a key nothing will ever look up. Generating it is the
 * only version that stays true, and this is the rule the project already
 * wrote down before it was broken again.
 *
 * <p>Runs from a client tick rather than from the screen's own construction,
 * which is where it used to live. The screen builds this file correctly, but
 * only once somebody opens it, so the documented procedure was "start the game
 * with the flag, then remember to go into the settings" — and the step that has
 * to be remembered is the step that gets skipped.
 */
final class LangDump {

    private static boolean done;

    private LangDump() {
    }

    static void onceIfAsked() {
        if (done || !Boolean.getBoolean("vulkanmodnext.dumpLang")) {
            return;
        }
        done = true;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            java.io.File file = new java.io.File(mc.gameDir, "logs/vulkanmodnext-en_us.lang");
            java.io.Writer writer = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file), "UTF-8");
            try {
                writer.write(Lang.dump(VulkanOptions.buildPages(mc)));
            } finally {
                writer.close();
            }
            net.vulkanmodnext.VulkanModNext.LOGGER.info("Language keys written to {}", file);
        } catch (Throwable e) {
            // Never let a development aid take a tick handler down.
            net.vulkanmodnext.VulkanModNext.LOGGER.warn("Could not write language keys", e);
        }
    }
}
