package net.vulkanmodnext.client;

import net.vulkanmodnext.VulkanModNext;
import net.vulkanmodnext.vkimpl.VkContext;

/**
 * Starts the Vulkan side once, and says plainly what happened.
 *
 * <h2>What it reports, and why in this shape</h2>
 *
 * Three things have to be told apart by somebody reading a log from a machine
 * nobody here owns:
 *
 * <ul>
 * <li><b>No Vulkan at all.</b> Nothing to do; the game renders as it always
 *     did.</li>
 * <li><b>Vulkan, but on the wrong card.</b> The one case that looks like
 *     success and is not: everything comes up and nothing can be shared. The
 *     card chosen and whether OpenGL agrees are both printed.</li>
 * <li><b>Vulkan on the right card, but the driver will not share memory.</b>
 *     A different fix entirely — a driver, not a launcher setting.</li>
 * </ul>
 *
 * <p>A single "Vulkan failed" line covers all three and points at none of them.
 * That distinction has already cost this project a week of somebody else's
 * time on the 1.12.2 side.
 */
public final class VulkanStartup {

    private static VkContext context;
    private static boolean attempted;

    private VulkanStartup() {
    }

    /** The context, or null when Vulkan is not available on this machine. */
    public static synchronized VkContext context() {
        return context;
    }

    public static synchronized void start() {
        if (attempted) {
            return;
        }
        attempted = true;
        VkContext starting = new VkContext();
        try {
            starting.init();
        } catch (Throwable failed) {
            VulkanModNext.LOGGER.warn("Vulkan did not start, the game will render as usual: {}",
                    failed.toString());
            return;
        }
        context = starting;
        VulkanModNext.LOGGER.info("Vulkan is up on {} with {} MiB of device-local memory",
                starting.gpuSummary(), starting.vramMegabytes());

        // Asked here rather than at the first shared image, because at this
        // point it is still a sentence about which card to launch the game on,
        // and forty lines later it is a crash.
        try {
            starting.ensureGlCapabilities();
            VulkanModNext.LOGGER.info("OpenGL and Vulkan agree on the card, and the driver will "
                    + "share images: interop is available");
            // Runs on every start while the port has nothing else to show. It
            // moves behind a flag the moment terrain is drawn through the same
            // path, because then the terrain is the check.
            InteropProbe.verify(starting);
        } catch (Throwable refused) {
            VulkanModNext.LOGGER.warn("Vulkan is up but cannot share images with OpenGL, so the "
                    + "terrain will stay where it is: {}", refused.toString());
        }
    }
}
