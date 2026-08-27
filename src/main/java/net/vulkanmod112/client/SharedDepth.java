package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.vulkanmod112.VulkanBridge;
import net.vulkanmod112.VulkanMod112;

/**
 * Makes the game's depth buffer <em>be</em> this renderer's depth image.
 *
 * <h2>What it removes</h2>
 *
 * The frame used to move a full screen of depth twice. Out, after the opaque
 * pass, so the creatures the game draws would be hidden behind hills; and back
 * again, before the translucent pass, so water would be hidden behind the
 * creatures. Both are hardware copies and neither is cheap at eight megapixels
 * — measured together with the colour composite they are 0.13 ms of a 1.88 ms
 * frame, which is the largest thing left in this renderer that is not drawing.
 *
 * Nothing has to be copied between two buffers that are one buffer. The
 * composite quad stops exporting depth as well, which gives it early-Z back.
 *
 * <h2>Why the game does not notice</h2>
 *
 * A framebuffer attachment names an object, not storage — the same reason the
 * game may resize its window without rebuilding anything. So the game's own
 * framebuffer keeps its identity, its colour texture and its size, and only the
 * thing hanging off its depth attachment changes: a renderbuffer it made for
 * itself becomes a texture backed by memory Vulkan also sees. Everything that
 * clears it, tests against it or writes into it goes on working unchanged.
 *
 * <h2>Why it can be given back</h2>
 *
 * The renderbuffer the game made is not deleted, only unhooked, so putting it
 * back is one call. That matters because the offer can be withdrawn mid-session
 * — the renderer fails, the terrain is switched off, the window is resized and
 * the images are rebuilt — and a framebuffer left pointing at a texture that no
 * longer exists is a black world, not an error message.
 *
 * <h2>What it costs</h2>
 *
 * An ordering that used to be implicit. Two APIs now write one image inside one
 * frame, and the moment the game has finished with it has to be stated rather
 * than assumed; that half lives on the Vulkan side, in
 * {@code beginFrameDepthHandover}.
 */
public final class SharedDepth {

    private static final int TEXTURE_2D = 3553;

    /** Set once, when this driver has been asked and would not have it. */
    private static boolean refused;

    /** The framebuffer this class last did something to, and what it did. */
    private static int knownFramebuffer;
    private static int knownTexture;
    private static int knownWidth;
    private static int knownHeight;
    /** The game's own depth renderbuffer, kept so it can be hung back up. */
    private static int replacedRenderbuffer;
    private static boolean attached;

    private SharedDepth() {
    }

    /** Whether the game's frame is looking at this renderer's depth right now. */
    public static boolean isAttached() {
        return attached;
    }

    /**
     * Brings the attachment to whatever the renderer is offering this frame.
     *
     * Called at the top of the world pass, which is the one point in the loop
     * where the answer can change without anything half-drawn depending on it.
     */
    static void ensure(VulkanBridge bridge, Minecraft mc) {
        if (refused || bridge == null || mc == null) {
            return;
        }
        Framebuffer frame = mc.getFramebuffer();
        if (frame == null || !frame.useDepth || frame.framebufferObject <= 0) {
            return;
        }
        int offered = bridge.sharedDepthTexture(frame.framebufferTextureWidth,
                frame.framebufferTextureHeight);
        if (offered == knownTexture
                && frame.framebufferObject == knownFramebuffer
                && frame.framebufferTextureWidth == knownWidth
                && frame.framebufferTextureHeight == knownHeight) {
            // Nothing has moved since the last answer, and asking the driver
            // what a framebuffer is made of is a place the processor waits for
            // the card. Once a frame for an answer that changes twice a session
            // is the kind of cost that shows up in no single measurement.
            return;
        }
        // The framebuffer was rebuilt under us: whatever was recorded about the
        // old one describes an object that no longer exists.
        if (frame.framebufferObject != knownFramebuffer) {
            attached = false;
            replacedRenderbuffer = 0;
        }
        if (offered == 0) {
            if (attached) {
                restore(frame);
            }
            remember(frame, 0);
            bridge.depthSharingAccepted(false);
            return;
        }
        if (attach(frame, offered)) {
            attached = true;
            remember(frame, offered);
            bridge.depthSharingAccepted(true);
            VulkanMod112.LOGGER.info("The game's frame now holds this renderer's depth image; "
                    + "the two per-frame depth copies are gone");
        } else {
            // Asked and refused. Said once, in the log, because the person who
            // will be asked about it is reading the log.
            refused = true;
            restore(frame);
            remember(frame, 0);
            bridge.depthSharingAccepted(false);
            VulkanMod112.LOGGER.warn("This driver would not take the shared depth image as the "
                    + "game's depth attachment; depth is copied across as before");
        }
    }

    /** Puts the game's own renderbuffer back, if this class ever took it down. */
    public static void release() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !attached) {
            return;
        }
        Framebuffer frame = mc.getFramebuffer();
        if (frame != null && frame.framebufferObject == knownFramebuffer) {
            restore(frame);
        }
        attached = false;
        knownTexture = 0;
    }

    private static boolean attach(Framebuffer frame, int texture) {
        int previousFbo = GlStateManager.glGetInteger(FRAMEBUFFER_BINDING);
        try {
            while (org.lwjgl.opengl.GL11.glGetError() != 0) {
                // Drained first, so a driver that fails halfway is not diagnosed
                // by an error somebody else left behind.
            }
            if (replacedRenderbuffer == 0) {
                replacedRenderbuffer = frame.depthBuffer;
            }
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, frame.framebufferObject);
            OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER,
                    OpenGlHelper.GL_DEPTH_ATTACHMENT, TEXTURE_2D, texture, 0);
            if (org.lwjgl.opengl.GL11.glGetError() != 0) {
                return false;
            }
            // Both answers are asked for. A driver may accept the attachment
            // and then hand back an incomplete framebuffer, which is a real
            // distinction and one only the status query can tell.
            return OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER)
                    == OpenGlHelper.GL_FRAMEBUFFER_COMPLETE;
        } catch (Throwable t) {
            VulkanMod112.LOGGER.error("Could not hand the depth image to the game's frame", t);
            return false;
        } finally {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFbo);
        }
    }

    private static void restore(Framebuffer frame) {
        if (replacedRenderbuffer == 0) {
            return;
        }
        int previousFbo = GlStateManager.glGetInteger(FRAMEBUFFER_BINDING);
        try {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, frame.framebufferObject);
            OpenGlHelper.glFramebufferRenderbuffer(OpenGlHelper.GL_FRAMEBUFFER,
                    OpenGlHelper.GL_DEPTH_ATTACHMENT, OpenGlHelper.GL_RENDERBUFFER,
                    replacedRenderbuffer);
        } catch (Throwable t) {
            VulkanMod112.LOGGER.error("Could not give the game's depth buffer back", t);
        } finally {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFbo);
            replacedRenderbuffer = 0;
            attached = false;
        }
    }

    private static void remember(Framebuffer frame, int texture) {
        knownFramebuffer = frame.framebufferObject;
        knownTexture = texture;
        knownWidth = frame.framebufferTextureWidth;
        knownHeight = frame.framebufferTextureHeight;
    }

    /**
     * {@code GL_FRAMEBUFFER_BINDING}, spelled out on purpose.
     *
     * {@code OpenGlHelper} wraps the calls that differ between the core, ARB and
     * EXT spellings of framebuffers but carries no name for this query. The
     * number is the same in all three, which is why asking for it directly is
     * safe where calling directly would not be.
     */
    private static final int FRAMEBUFFER_BINDING = 36006;
}
