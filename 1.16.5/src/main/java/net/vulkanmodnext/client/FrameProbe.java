package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.vulkanmodnext.VulkanModNext;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Reads the finished frame back and says what is in it.
 *
 * <h2>Why this and not a look at the screen</h2>
 *
 * "The renderer ran without errors" and "the world is on the screen" are
 * different claims, and the first one is easy to mistake for the second. Every
 * stage of the Vulkan path can report success while drawing nothing: the frame
 * is recorded, submitted, composited, and what lands is the colour it was
 * cleared to.
 *
 * <p>So the frame is read back and described by two numbers that a blank one
 * cannot fake: how many distinct colours are in it, and how much of it is not
 * the single most common colour. A sky-and-nothing frame is two or three
 * colours and almost entirely one of them. A drawn world is thousands.
 *
 * <p>The point of printing them is comparison: the same route with the terrain
 * switch on and off should give two similar readings. One number alone proves
 * nothing, which is why the line says which run it came from.
 */
public final class FrameProbe {

    /** Late enough that the world has filled in and the camera has settled. */
    private static final int AT_FRAME = 1500;

    /** Every pixel is far more than needed; a grid is enough and costs nothing. */
    private static final int STEP = 4;

    private static int frames;
    private static boolean done;

    private FrameProbe() {
    }

    public static void endFrame() {
        if (done || ++frames < AT_FRAME) {
            return;
        }
        done = true;
        // The game's own counter, so both sides of the comparison are measured
        // by the same thing rather than by the renderer measuring itself.
        VulkanModNext.LOGGER.info("Frame probe ({} terrain): {} fps, render distance {}",
                VulkanConfig.isTerrainEnabled() ? "Vulkan" : "vanilla",
                Minecraft.getInstance().fpsString,
                Minecraft.getInstance().options.renderDistance);
        int width = Minecraft.getInstance().getWindow().getWidth();
        int height = Minecraft.getInstance().getWindow().getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) {
                VulkanModNext.LOGGER.warn("Frame probe: could not read the frame back, "
                        + "OpenGL error 0x{}", Integer.toHexString(error));
                return;
            }
            describe(pixels, width, height, "on the screen");
            // The same reading, taken from the image Vulkan writes into rather
            // than from the finished frame. Two numbers side by side split the
            // problem exactly in half: terrain in the shared image and not on
            // the screen means the composite; nothing in either means the
            // Vulkan draw.
            if (VulkanConfig.isTerrainEnabled() && VulkanStartup.context() != null) {
                int shared = VulkanStartup.context().sharedColourTexture();
                if (shared > 0) {
                    ByteBuffer inside = MemoryUtil.memAlloc(width * height * 4);
                    try {
                        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, shared);
                        GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA,
                                GL11C.GL_UNSIGNED_BYTE, inside);
                        int failed = GL11C.glGetError();
                        if (failed == GL11C.GL_NO_ERROR) {
                            describe(inside, width, height, "in the image Vulkan writes");
                        } else {
                            VulkanModNext.LOGGER.warn("Frame probe: could not read the shared "
                                    + "image, OpenGL error 0x{}", Integer.toHexString(failed));
                        }
                    } finally {
                        MemoryUtil.memFree(inside);
                    }
                }
            }
        } catch (Throwable failed) {
            VulkanModNext.LOGGER.warn("Frame probe failed", failed);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static void describe(ByteBuffer pixels, int width, int height, String where) {
        // Colours are coarsened to five bits a channel before counting: two
        // pixels of sky a shade apart are the same answer to the question being
        // asked, and counting them apart would let a gradient look like detail.
        java.util.HashMap<Integer, Integer> seen = new java.util.HashMap<>();
        int sampled = 0;
        for (int y = 0; y < height; y += STEP) {
            for (int x = 0; x < width; x += STEP) {
                int at = (y * width + x) * 4;
                int key = ((pixels.get(at) & 0xF8) << 8)
                        | ((pixels.get(at + 1) & 0xF8) << 3)
                        | ((pixels.get(at + 2) & 0xF8) >> 2);
                seen.merge(key, 1, Integer::sum);
                sampled++;
            }
        }
        int commonest = 0;
        int commonestColour = 0;
        for (java.util.Map.Entry<Integer, Integer> entry : seen.entrySet()) {
            if (entry.getValue() > commonest) {
                commonest = entry.getValue();
                commonestColour = entry.getKey();
            }
        }
        // Which colour it is, not just how much of it there is. Sky blue means
        // the terrain is missing; a dirt colour means it is drawn without its
        // texture. One number tells the two apart and no amount of staring at
        // percentages does.
        int r = (commonestColour >> 8) & 0xF8;
        int g = (commonestColour >> 3) & 0xF8;
        int b = (commonestColour << 2) & 0xF8;
        double varied = sampled == 0 ? 0.0 : 1.0 - commonest / (double) sampled;
        VulkanModNext.LOGGER.info("Frame probe ({} terrain, {}): {} distinct colours in {} "
                        + "samples, {}% is not the commonest colour",
                VulkanConfig.isTerrainEnabled() ? "Vulkan" : "vanilla", where,
                seen.size(), sampled, String.format("%.1f", varied * 100));
        VulkanModNext.LOGGER.info("  the commonest colour is #{}{}{} and covers {}% of it",
                String.format("%02X", r), String.format("%02X", g), String.format("%02X", b),
                String.format("%.1f", (1.0 - varied) * 100));
        if (VulkanConfig.isTerrainEnabled()) {
            VulkanModNext.LOGGER.info("Frame probe: {}", TerrainFrame.layerReport());
            // The renderer's own account of the frame it just drew. It exists
            // already and says more than anything written from outside could.
            if (VulkanStartup.context() != null) {
                for (String line : VulkanStartup.context().terrainDiagnostics().split("\n")) {
                    if (!line.trim().isEmpty()) {
                        VulkanModNext.LOGGER.info("  {}", line);
                    }
                }
            }
        }
    }
}
