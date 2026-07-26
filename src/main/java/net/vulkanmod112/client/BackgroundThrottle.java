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

    /** Called at the end of every rendered frame. */
    public static void afterFrame() {
        int limit = VulkanConfig.getBackgroundFpsLimit();
        if (limit <= 0 || Display.isActive()) {
            return;
        }
        // Display.sync sleeps with far better accuracy than Thread.sleep and is
        // what the game itself uses for its own frame cap.
        Display.sync(limit);
    }
}
