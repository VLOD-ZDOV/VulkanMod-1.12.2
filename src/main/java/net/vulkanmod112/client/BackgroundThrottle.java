package net.vulkanmod112.client;

import org.lwjgl.opengl.Display;

/**
 * Caps the framerate while the window is not the active one.
 *
 * With the frame limit at its maximum ("unlimited") vanilla never sleeps, and
 * a minimised window keeps the GPU at full load rendering frames nobody can
 * see. The compositor does not throttle it either, because nothing is being
 * presented. Sleeping here costs nothing while the game is in front and gives
 * the whole GPU back the moment it is not.
 */
public final class BackgroundThrottle {

    private BackgroundThrottle() {
    }

    /**
     * Whether frames are being held back, told to the renderer.
     *
     * Without this the pacing report calls the cap a stall: at ten frames a
     * second every frame is a hundred milliseconds, so the worst frame, the
     * one percent low and the count of frames over three times the median all
     * describe a sleep this code asked for. That is the same trap as the drop
     * to five frames a second that cost a whole investigation once — a setting
     * working exactly as told, read as a fault.
     *
     * Published on the change rather than every frame: a property write per
     * frame to say nothing changed is a cost with no reader.
     */
    private static boolean published;

    /** Called at the end of every rendered frame. */
    public static void afterFrame() {
        int limit = VulkanConfig.getBackgroundFpsLimit();
        boolean throttling = limit > 0 && !Display.isActive();
        if (throttling != published) {
            published = throttling;
            System.setProperty("vulkanmod112.frameThrottled", Boolean.toString(throttling));
        }
        if (!throttling) {
            return;
        }
        // Display.sync sleeps with far better accuracy than Thread.sleep and is
        // what the game itself uses for its own frame cap.
        Display.sync(limit);
    }
}
