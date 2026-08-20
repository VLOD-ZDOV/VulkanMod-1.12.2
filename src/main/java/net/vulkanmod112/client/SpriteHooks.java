package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;
import net.vulkanmod112.VulkanBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Everything the game draws as camera-facing textured quads, routed to Vulkan.
 *
 * Particles and weather are built by the game exactly as they always were — a
 * loop that appends four vertices per quad into a {@link BufferBuilder}. What
 * changes is only the last step. Vanilla hands that buffer to OpenGL as a
 * <i>client-side vertex array</i>: pointers into ordinary process memory,
 * followed by a draw. It is the slowest way OpenGL has of being given geometry,
 * because the driver has to copy the whole thing at draw time before it can
 * begin, and it is how every particle in this game has always been drawn. At
 * the cap the game sets itself that is nearly two megabytes a frame.
 *
 * Here the same bytes go into a buffer the card owns and are drawn inside the
 * translucent Vulkan pass, which exists already, has the game's depth loaded
 * into it already, and ends in a composite over the game's frame already. So
 * this costs no extra hand-off between the two APIs at all — the geometry joins
 * a submission that was going to happen regardless.
 *
 * <p>What it does not do is change what a particle looks like or where it goes.
 * Every number in the vertices is the game's own.
 */
public final class SpriteHooks {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Sprites");

    /** Shared with the terrain, not copied: see the bridge's own note. */
    public static final int SLOT_BLOCK_ATLAS = 0;
    public static final int SLOT_PARTICLES = 1;
    public static final int SLOT_RAIN = 2;
    public static final int SLOT_SNOW = 3;

    private static final ResourceLocation PARTICLE_TEXTURES =
            new ResourceLocation("textures/particle/particles.png");
    private static final ResourceLocation RAIN_TEXTURES =
            new ResourceLocation("textures/environment/rain.png");
    private static final ResourceLocation SNOW_TEXTURES =
            new ResourceLocation("textures/environment/snow.png");

    /** Vanilla's own alpha tests, and they are not the same number. */
    public static final float PARTICLE_CUTOFF = 0.003921569f;
    public static final float WEATHER_CUTOFF = 0.1f;

    private static boolean sheetsSent;
    private static boolean broken;

    private SpriteHooks() {
    }

    /**
     * Whether this frame's sprites should go to Vulkan.
     *
     * Asked once per batch rather than cached for the frame, because the answer
     * has to be able to turn to "no" between one draw and the next — a failure
     * anywhere in the renderer takes the whole terrain path down, and a
     * particle drawn into a pass that will never be submitted is a particle
     * nobody ever sees.
     */
    public static VulkanBridge target(boolean enabledInSettings) {
        if (broken || !enabledInSettings || !sheetsSent) {
            return null;
        }
        VulkanBridge bridge = TerrainHooks.liveBridge();
        if (bridge == null || !bridge.isInitialized() || !TerrainHooks.vulkanOwnsTranslucent()) {
            return null;
        }
        return bridge.drawsSprites() ? bridge : null;
    }

    /**
     * Draws a batch the renderer would not take, exactly as the game would.
     *
     * The batch cannot be handed back to {@code Tessellator.draw()} — that
     * calls {@code finishDrawing()} again and the builder is no longer
     * building, so it throws. The uploader underneath it takes a finished
     * buffer, which is what this is.
     */
    private static final net.minecraft.client.renderer.WorldVertexBufferUploader UPLOADER =
            new net.minecraft.client.renderer.WorldVertexBufferUploader();

    /**
     * Hands one finished batch over and empties the builder.
     *
     * The builder arrives still building; this closes it, which is what fixes
     * its position and limit to exactly the vertices in it, and resets it so the
     * caller may begin the next batch. Callers must not also call
     * {@code Tessellator.draw()} — that would close it a second time and throw.
     */
    public static void submit(VulkanBridge bridge, BufferBuilder builder, int slot, float cutoff) {
        builder.finishDrawing();
        int vertices = builder.getVertexCount();
        if (vertices > 0
                && !bridge.submitSprites(builder.getByteBuffer(), vertices, slot, cutoff)) {
            // Refused, not lost. Whatever will not fit is drawn here rather
            // than counted and dropped: a rain field is one batch, it does not
            // fit, and the frame that dropped it had no rain in it at all.
            UPLOADER.draw(builder);
            return;
        }
        builder.reset();
    }

    /** Marks the whole path dead after a failure; the game keeps its own. */
    public static void fail(String what, Throwable t) {
        if (broken) {
            return;
        }
        broken = true;
        LOGGER.error("{} through Vulkan failed — particles and weather go back to OpenGL "
                + "for the rest of this session", what, t);
    }

    public static boolean isBroken() {
        return broken;
    }

    /**
     * Copies the sheets these quads are textured from into Vulkan.
     *
     * Done once, beside the block atlas, and deliberately not on demand at the
     * moment a batch first needs one: uploading an image waits on the frame's
     * own fence, and doing that a third of the way through a frame would stall
     * the processor until the card had finished the work already submitted.
     * Here nothing has been submitted yet.
     */
    static void sendSheets(VulkanBridge bridge) {
        if (sheetsSent || broken) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        TextureManager textures = mc.getTextureManager();
        try {
            bridge.updateSpriteTexture(SLOT_PARTICLES, glTexture(textures, PARTICLE_TEXTURES));
            bridge.updateSpriteTexture(SLOT_RAIN, glTexture(textures, RAIN_TEXTURES));
            bridge.updateSpriteTexture(SLOT_SNOW, glTexture(textures, SNOW_TEXTURES));
            // Every one of the three had to be bound to be read, and the caller
            // is inside a block layer that expects the atlas to still be there.
            textures.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            sheetsSent = true;
            LOGGER.info("Particle, rain and snow sheets handed to Vulkan");
        } catch (Throwable t) {
            fail("Uploading the sprite sheets", t);
        }
    }

    /** Forgotten on a resource reload: the game hands out fresh GL names then. */
    static void forgetSheets() {
        sheetsSent = false;
    }

    /**
     * The GL name of a sheet, loading it if the game has not needed it yet.
     *
     * Binding is what forces the load. Rain and snow in particular are never
     * touched in a world with clear weather, so asking for the texture object
     * without this returns nothing at all.
     */
    private static int glTexture(TextureManager textures, ResourceLocation location) {
        textures.bindTexture(location);
        ITextureObject texture = textures.getTexture(location);
        return texture == null ? 0 : texture.getGlTextureId();
    }
}
