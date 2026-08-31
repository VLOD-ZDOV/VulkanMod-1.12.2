package net.vulkanmodnext.client;

import net.vulkanmodnext.VulkanModNext;
import net.vulkanmodnext.vkimpl.VkContext;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Proves that a picture drawn by Vulkan can be read back through OpenGL.
 *
 * <h2>Why a readback and not a look at the screen</h2>
 *
 * The obvious check is to draw the shared texture in a corner of the game and
 * look at it. That check cannot be run by whoever is writing the port at three
 * in the morning against a machine they are not sitting at, and — worse — it
 * passes when the image is stale. A texture that imported once and has been
 * frozen ever since looks exactly like one that is being written every frame.
 *
 * <p>So the frame is read back and compared against what the shader was told
 * to draw. The interop shader draws one triangle with vertices at
 * (0,-0.8), (0.8,0.8) and (-0.8,0.8) in clip space, coloured red, green and
 * blue at the corners, on a dark blue ground. That triangle covers
 * <b>32%</b> of the image — half of 1.6 by 1.6, out of 2 by 2 — and the number
 * is worth stating in advance, because a probe that only reports what it found
 * cannot fail.
 *
 * <p>The two halves are told apart by alpha rather than by colour: the ground
 * is cleared to alpha 0.85 and the triangle is drawn at 1.0, so the split does
 * not depend on how the corner colours interpolate.
 */
public final class InteropProbe {

    private static final int SIZE = 256;

    /** Half of 1.6 x 1.6 out of 2 x 2. */
    private static final double EXPECTED_COVERAGE = 0.32;

    /**
     * Wide, because it is answering "did the triangle arrive", not "is the
     * rasteriser accurate". A driver that got this within a couple of points
     * has done the thing being asked about.
     */
    private static final double TOLERANCE = 0.03;

    private InteropProbe() {
    }

    public static void verify(VkContext context) {
        if (!context.initInterop(SIZE, SIZE)) {
            VulkanModNext.LOGGER.warn("Interop: the shared image could not be created, so nothing "
                    + "can be handed from Vulkan to OpenGL yet");
            return;
        }
        int texture = context.interopTextureId();
        if (texture <= 0) {
            VulkanModNext.LOGGER.warn("Interop: the image was created but OpenGL gave it no "
                    + "texture name ({}) — the import is what failed, not the render", texture);
            return;
        }

        ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        try {
            // Angle zero, so the triangle sits where this class says it does.
            context.renderInteropFrame(0.0f);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0,
                    GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
            int error = GL11C.glGetError();
            context.interopFrameDisplayed();
            if (error != GL11C.GL_NO_ERROR) {
                VulkanModNext.LOGGER.warn("Interop: reading the shared texture back failed with "
                        + "OpenGL error 0x{}", Integer.toHexString(error));
                return;
            }
            report(pixels);
        } catch (Throwable failed) {
            VulkanModNext.LOGGER.error("Interop: handing a frame from Vulkan to OpenGL failed",
                    failed);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static void report(ByteBuffer pixels) {
        int drawn = 0;
        int reddest = 0;
        int greenest = 0;
        int bluest = 0;
        for (int i = 0; i < SIZE * SIZE; i++) {
            int at = i * 4;
            int r = pixels.get(at) & 0xFF;
            int g = pixels.get(at + 1) & 0xFF;
            int b = pixels.get(at + 2) & 0xFF;
            int a = pixels.get(at + 3) & 0xFF;
            if (a <= 242) {
                continue; // the cleared ground, at 0.85
            }
            drawn++;
            reddest = Math.max(reddest, r);
            greenest = Math.max(greenest, g);
            bluest = Math.max(bluest, b);
        }
        double coverage = drawn / (double) (SIZE * SIZE);
        boolean shaped = Math.abs(coverage - EXPECTED_COVERAGE) <= TOLERANCE;
        // All three vertex colours have to be present. A frame that imported
        // but was never written comes back as one flat colour, and coverage
        // alone would not notice.
        boolean coloured = reddest > 200 && greenest > 200 && bluest > 200;

        if (shaped && coloured) {
            VulkanModNext.LOGGER.info("Interop works: Vulkan drew a triangle and OpenGL read it "
                            + "back — {}% of the image against {}% expected, corners r{} g{} b{}",
                    String.format("%.1f", coverage * 100),
                    String.format("%.1f", EXPECTED_COVERAGE * 100),
                    reddest, greenest, bluest);
            return;
        }
        VulkanModNext.LOGGER.warn("Interop is wired up but what came back is not what was drawn: "
                        + "{}% of the image against {}% expected, corners r{} g{} b{}. {}",
                String.format("%.1f", coverage * 100),
                String.format("%.1f", EXPECTED_COVERAGE * 100),
                reddest, greenest, bluest,
                drawn == 0 ? "Nothing was drawn at all, so the image is the one Vulkan cleared "
                        + "and never rendered into."
                        : "The image is being written, so this is about where or how.");
    }
}
