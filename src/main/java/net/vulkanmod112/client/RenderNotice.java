package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * Tells the player, in the chat they are already looking at, when this mod has
 * stopped drawing the world.
 *
 * Everything this renderer does happens silently until someone opens a log, and
 * the failure it can have is the quietest of all: the world keeps being drawn,
 * by OpenGL, and looks almost the same. Without a word on the screen the only
 * symptom is that the settings in this mod stop meaning anything — which reads
 * as "the mod is broken" rather than "the mod stood aside", and the two want
 * very different things from the player.
 *
 * Printed locally. This never reaches the server or anybody else's chat: it is
 * put straight into this client's chat window, which is the only place a
 * message about this client's graphics belongs.
 */
public final class RenderNotice {

    /** Said once per session, however many frames go on to fail. */
    private static boolean told;
    private static String pending;

    private RenderNotice() {
    }

    /**
     * Queues the notice. Not printed here on purpose — this is called from
     * inside a failed frame, where the chat overlay may be halfway through
     * being drawn and the world may be unloading.
     */
    public static void fellBackToOpenGL(String reason) {
        if (told) {
            return;
        }
        told = true;
        pending = reason;
    }

    /** Called from a safe point in the frame; prints at most one message. */
    public static void flushToChat() {
        if (pending == null) {
            return;
        }
        String reason = pending;
        pending = null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.ingameGUI == null || mc.player == null) {
            // No chat to print into yet. Put it back and try on a later frame:
            // dropping it would leave the player with the one situation this
            // exists to prevent.
            pending = reason;
            return;
        }
        say(mc, TextFormatting.YELLOW + "[VulkanMod112] " + TextFormatting.WHITE
                + "The world is being drawn by OpenGL — this mod's renderer stood aside.");
        say(mc, TextFormatting.GRAY + "Reason: " + reason);
        say(mc, TextFormatting.GRAY + "The game is fine and nothing was lost. The Vulkan settings "
                + "in this mod will not do anything until it starts again. Details are in "
                + "vulkanmod112-diagnostics.log next to the game's own log.");
    }

    private static void say(Minecraft mc, String line) {
        mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentString(line));
    }

    /** A fresh world may well succeed where the last one failed. */
    public static void reset() {
        told = false;
        pending = null;
    }
}
