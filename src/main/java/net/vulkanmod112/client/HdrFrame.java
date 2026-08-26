package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.vulkanmod112.VulkanMod112;

/**
 * Gives the game's own frame room above white, and takes it away again.
 *
 * <h2>Why the frame and not a target of our own</h2>
 *
 * Everything drawn between this mod's composite and the point where the picture
 * is finished — creatures, particles, weather, the sky, the game's own
 * post-processing — is vanilla code this mod does not intercept. A parallel
 * floating target would therefore have to be filled by copying the frame back
 * and forth around all of it, which costs more than changing what the frame is
 * made of. So the frame itself is changed.
 *
 * <h2>Why no mixin</h2>
 *
 * The obvious way is a {@code @ModifyConstant} on the format in
 * {@code Framebuffer}, and it is wrong: that targets the <em>class</em>, and
 * four places in the game and Forge build a {@code Framebuffer} — the main
 * frame, the loading screen, and every buffer of the vanilla shader groups
 * (creeper, spider, invert, entity outline) — plus any mod that builds one of
 * its own. All of them would silently change format, and the shader groups
 * would be reading and writing a format their own code never expected.
 *
 * This touches one object instead: whatever {@link Minecraft#getFramebuffer()}
 * returns, and nothing else, ever. Reallocating the colour texture of an
 * existing framebuffer keeps the attachment valid, because the attachment names
 * the texture object rather than its storage — which is the same reason the
 * game may resize its window without rebuilding anything.
 *
 * <h2>Why it can be taken away again</h2>
 *
 * A floating frame is only safe while something closes the range back down
 * before the picture is shown, and the only thing that does that is this mod's
 * tone pass, which lives on the Vulkan side. If the Vulkan side is not there —
 * it failed, it was switched off, the renderer stood aside for another mod —
 * then nothing resolves, everything above white is cut off at the moment of
 * display, and the player is left with a worse picture for having asked for a
 * better one.
 *
 * So the format is not a decision made once at startup. It is asked again every
 * frame, and the answer includes whether the resolve is going to run. Losing
 * the renderer mid-session puts the frame back to eight bits by itself.
 */
public final class HdrFrame {

    /** {@code GL_RGBA16F}, spelled out because the game's own call spells 32856. */
    private static final int RGBA16F = 34842;
    /** {@code GL_RGBA8}, the format the game asks for. */
    private static final int RGBA8 = 32856;
    private static final int RGBA = 6408;
    private static final int UNSIGNED_BYTE = 5121;
    private static final int FLOAT = 5126;
    private static final int TEXTURE_2D = 3553;
    private static final int TEXTURE_INTERNAL_FORMAT = 4099;
    /**
     * {@code GL_FRAMEBUFFER_BINDING}, spelled out on purpose.
     *
     * {@code OpenGlHelper} wraps the calls that differ between the core,
     * ARB and EXT spellings of framebuffers but carries no name for this
     * query. The number is the same in all three, which is why asking for
     * it directly is safe where calling directly would not be.
     */
    private static final int FRAMEBUFFER_BINDING = 36006;

    /** Set once, when this driver has been asked and has said no. */
    private static volatile boolean refused;
    /** What the frame is right now, as far as this class made it so. */
    private static volatile boolean active;
    /**
     * The frame this class last had an answer about.
     *
     * Asking the driver what a texture is made of is a query, and a query is
     * a place the processor can end up waiting for the card. Once a frame,
     * every frame, for an answer that changes about twice a session, is the
     * kind of cost that shows up in no single measurement and sits in the
     * frame time for ever. The game builds a fresh texture whenever the
     * window changes size, so the name and the size together are enough to
     * know when the answer might have moved.
     */
    private static int knownTexture;
    private static int knownWidth;
    private static int knownHeight;
    private static boolean knownFloating;

    private HdrFrame() {
    }

    /** Whether the game's frame really has headroom in it at this moment. */
    public static boolean isActive() {
        return active;
    }

    /**
     * Brings the frame to the format the settings and the renderer between them
     * allow, if it is not there already.
     *
     * Called at the start of a render tick, which is the one moment in the loop
     * where the frame holds nothing worth keeping: the last picture has been
     * shown and the next has not been drawn. Reallocating a texture throws its
     * contents away, and doing that anywhere else in the loop would throw away
     * a partly drawn world.
     */
    static void ensure() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        Framebuffer frame = mc.getFramebuffer();
        if (frame == null || frame.framebufferTexture == 0
                || frame.framebufferTextureWidth <= 0 || frame.framebufferTextureHeight <= 0) {
            return;
        }
        boolean want = VulkanConfig.isHdrFrame() && !refused && resolveWillRun();
        boolean is;
        if (frame.framebufferTexture == knownTexture
                && frame.framebufferTextureWidth == knownWidth
                && frame.framebufferTextureHeight == knownHeight) {
            is = knownFloating;
        } else {
            is = internalFormat(frame) == RGBA16F;
            remember(frame, is);
        }
        if (want == is) {
            active = is;
            return;
        }
        if (want) {
            active = convert(frame, RGBA16F, FLOAT);
            if (!active) {
                // Asked and refused. Said once, and said in the log rather than
                // only in the settings screen, because the person who will be
                // asked about it is reading the log.
                refused = true;
                System.setProperty("vulkanmod112.hdrFrameRefused", "true");
                VulkanMod112.LOGGER.warn("This driver would not give a floating frame; "
                        + "the picture stays eight bits a channel");
                convert(frame, RGBA8, UNSIGNED_BYTE);
            } else {
                VulkanMod112.LOGGER.info("The game's frame carries sixteen bits a channel "
                        + "this session");
            }
        } else {
            convert(frame, RGBA8, UNSIGNED_BYTE);
            active = false;
        }
        remember(frame, active);
        System.setProperty("vulkanmod112.hdrFrameActive", Boolean.toString(active));
        // The other one. See VulkanConfig.settingsMoved.
        VulkanConfig.settingsMoved();
    }

    /**
     * Whether anything is going to bring the picture back into range this frame.
     *
     * Deliberately the same question the hook itself asks, and not a copy of the
     * setting: the tone pass is on the Vulkan side, so a bridge that is gone
     * takes the resolve with it however the settings are set.
     */
    private static boolean resolveWillRun() {
        return TerrainHooks.sceneEffectsWillRun();
    }

    private static void remember(Framebuffer frame, boolean floating) {
        knownTexture = frame.framebufferTexture;
        knownWidth = frame.framebufferTextureWidth;
        knownHeight = frame.framebufferTextureHeight;
        knownFloating = floating;
    }

    /** What the frame's colour texture is made of, asked of the driver. */
    private static int internalFormat(Framebuffer frame) {
        int previous = GlStateManager.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager.bindTexture(frame.framebufferTexture);
        int format = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                TEXTURE_2D, 0, TEXTURE_INTERNAL_FORMAT);
        GlStateManager.bindTexture(previous);
        return format;
    }

    /**
     * Reallocates the frame's colour texture and says whether it took.
     *
     * Both answers are asked for, because a driver may accept the allocation
     * and then hand back an incomplete framebuffer — the format is legal to
     * store and not legal to render into, which is a real distinction and one
     * that only {@code glCheckFramebufferStatus} can tell.
     */
    private static boolean convert(Framebuffer frame, int internal, int type) {
        int previousTexture =
                GlStateManager.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        int previousFbo = GlStateManager.glGetInteger(FRAMEBUFFER_BINDING);
        try {
            // Cleared first, so a driver that fails halfway is not diagnosed by
            // an error left over from somebody else's call.
            while (org.lwjgl.opengl.GL11.glGetError() != 0) {
                // draining
            }
            GlStateManager.bindTexture(frame.framebufferTexture);
            GlStateManager.glTexImage2D(TEXTURE_2D, 0, internal,
                    frame.framebufferTextureWidth, frame.framebufferTextureHeight,
                    0, RGBA, type, (java.nio.IntBuffer) null);
            if (org.lwjgl.opengl.GL11.glGetError() != 0) {
                return false;
            }
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, frame.framebufferObject);
            int status = OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER);
            return status == OpenGlHelper.GL_FRAMEBUFFER_COMPLETE;
        } catch (Throwable t) {
            VulkanMod112.LOGGER.error("Could not change the frame's format", t);
            return false;
        } finally {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFbo);
            GlStateManager.bindTexture(previousTexture);
        }
    }

    /** Registered whatever happens to Vulkan: it is what undoes the format too. */
    public static final class Handler {
        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                ensure();
            }
        }
    }
}
