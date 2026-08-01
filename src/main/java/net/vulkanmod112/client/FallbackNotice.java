package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Stands in for {@link VulkanDemoOverlay} when the Vulkan side never came up.
 *
 * That case used to be the quietest one in the mod, and it is the one where a
 * player has the least to go on: the settings screen is there, every switch in
 * it moves, and nothing any of them promises happens. The mod looks installed
 * and inert. The failure is written to the game log, but the F3 screen — the
 * one place a player checks first — said nothing at all, because the line that
 * would have said it is added by the overlay, and the overlay is registered
 * only once a bridge exists.
 *
 * So this says the renderer is off and why, and keeps the two per-frame jobs
 * that never needed Vulkan: the diagnostics snapshot (wanted most exactly
 * here) and the background frame cap.
 */
public final class FallbackNotice {

    /** Longest reason shown on the F3 line; the log has the whole thing. */
    private static final int REASON_LIMIT = 80;

    private final String reason;

    public FallbackNotice(Throwable failure) {
        this.reason = describe(failure, REASON_LIMIT);
        // Also in the chat, once. F3 is where a player looks second; the first
        // place is the screen in front of them, and this failure has no other
        // symptom than every setting in the mod quietly meaning nothing.
        RenderNotice.fellBackToOpenGL(describe(failure, Integer.MAX_VALUE));
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Diagnostics.tick();
            BackgroundThrottle.afterFrame();
            // Said from here as well as from the session handler: when the
            // renderer never started, this object may be the only one of the
            // two that got registered.
            RenderNotice.flushToChat();
        }
    }

    @SubscribeEvent
    public void onDebugText(RenderGameOverlayEvent.Text event) {
        if (Minecraft.getMinecraft().gameSettings.showDebugInfo) {
            event.getRight().add("");
            event.getRight().add("VulkanMod112: renderer off, vanilla OpenGL");
            event.getRight().add("VulkanMod112: " + reason);
        }
    }

    /**
     * The innermost cause, because the outer one is almost always this mod's
     * own wrapper ("Cannot bootstrap the Vulkan classloader") and the answer is
     * underneath it — a missing driver, a native that would not load, a Java
     * the bundled LWJGL does not know.
     *
     * Unless somewhere in the chain we already knew what to say. A stack budget
     * too small for the machine's drivers surfaces as "Out of stack space",
     * which is true, useless, and reads as though the player should give the
     * game more memory — a change that cannot help. Those throws say so
     * themselves, and are taken at their word.
     */
    private static String describe(Throwable failure, int limit) {
        if (failure == null) {
            return "reason unknown, see the log";
        }
        Throwable root = failure;
        for (Throwable link = failure; link != null && link.getCause() != link; link = link.getCause()) {
            if (link instanceof net.vulkanmod112.VulkanUnavailableException) {
                root = link;
                break;
            }
            root = link;
        }
        String message = root.getMessage();
        if (message == null || message.isEmpty()) {
            message = root.getClass().getSimpleName();
        }
        message = message.replace('\n', ' ').trim();
        if (message.length() > limit) {
            message = message.substring(0, limit - 1) + "…";
        }
        return message;
    }

}
