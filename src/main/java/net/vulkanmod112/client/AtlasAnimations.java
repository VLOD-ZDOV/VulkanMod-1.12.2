package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.vulkanmod112.VulkanBridge;
import org.lwjgl.opengl.GL11;

/**
 * Animation frames on their way from the game's atlas to the Vulkan copy.
 *
 * <h2>Why a batch</h2>
 *
 * A tick can change a dozen sprites, and each arrives as its own call. Sending
 * each one across on its own would mean a queue submission and a wait apiece,
 * twenty times a second, for a few kilobytes each time. So the frames of one
 * tick are gathered here and handed over together when the game has finished
 * with them: one submission, one wait, however many sprites moved.
 *
 * <h2>What is sent</h2>
 *
 * Two flat arrays, because that is what may cross into the Vulkan classloader.
 * A header of six ints per level of per sprite — which mip level, where it
 * goes, how big it is, and where its pixels start in the other array — and the
 * pixels themselves, in the game's own layout, converted on the far side.
 *
 * Every mip level is sent, not only the top one. A sprite's smaller levels are
 * what a distant block samples, and leaving them at the frame the world loaded
 * with would make lava change colour as you walked towards it.
 */
public final class AtlasAnimations {

    private static final int HEADER_INTS = 6;

    private static int[] header = new int[HEADER_INTS * 64];
    private static int headerCount;
    private static int[] pixels = new int[64 * 1024];
    private static int pixelCount;
    /** Set once the atlas has been handed over; nothing is worth collecting before. */
    private static boolean armed;

    private AtlasAnimations() {
    }

    public static void arm() {
        armed = true;
    }

    /**
     * Whether the game is inside the block atlas's own animation step.
     *
     * The check below has to answer "is this upload going into the block
     * atlas", because the method it hangs off uploads everything — entity
     * skins, the map item, whatever a mod puts through it. It answered by
     * asking the driver which texture was bound, once per sprite per tick, and
     * a question to the driver is a wait for everything already queued.
     *
     * The answer is known without asking whenever the upload comes from the
     * atlas stepping its own animations, because that is the one place this
     * renderer already hooks at both ends. So the flag is the fast path and the
     * driver is the fallback — kept rather than removed, because a mod that
     * animates a sprite of its own outside that method would otherwise freeze
     * in the terrain and nowhere else, which is exactly the bug this class was
     * written to fix.
     */
    private static boolean insideAtlasStep;

    public static void beginAtlasStep() {
        insideAtlasStep = true;
    }

    public static void endAtlasStep() {
        insideAtlasStep = false;
    }

    /**
     * One upload the game just made. Kept only when it is the block atlas.
     *
     * The same method uploads plenty that is not: entity textures, the map
     * item, anything a mod puts through it. What decides is which texture is
     * bound, because that is what the call is about to write into, and the
     * atlas knows its own name.
     */
    public static void record(int[][] data, int width, int height, int originX, int originY) {
        if (!armed || data == null || width <= 0 || height <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getTextureMapBlocks() == null) {
            return;
        }
        if (!insideAtlasStep
                && GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
                        != mc.getTextureMapBlocks().getGlTextureId()) {
            return;
        }
        for (int level = 0; level < data.length; level++) {
            int[] levelData = data[level];
            if (levelData == null) {
                continue;
            }
            int levelWidth = Math.max(1, width >> level);
            int levelHeight = Math.max(1, height >> level);
            if (levelData.length < levelWidth * levelHeight) {
                // A level the game did not fill: the sprite is smaller than the
                // chain claims. Nothing below it is meaningful either.
                break;
            }
            ensurePixels(pixelCount + levelWidth * levelHeight);
            ensureHeader();
            header[headerCount++] = level;
            header[headerCount++] = originX >> level;
            header[headerCount++] = originY >> level;
            header[headerCount++] = levelWidth;
            header[headerCount++] = levelHeight;
            header[headerCount++] = pixelCount;
            System.arraycopy(levelData, 0, pixels, pixelCount, levelWidth * levelHeight);
            pixelCount += levelWidth * levelHeight;
        }
    }

    /** Called when the game has finished stepping its animations for this tick. */
    public static void flush(VulkanBridge bridge) {
        if (headerCount == 0) {
            return;
        }
        if (bridge != null) {
            bridge.updateAtlasRegions(header, headerCount, pixels, pixelCount);
        }
        headerCount = 0;
        pixelCount = 0;
    }

    /** Dropped without sending, for when there is nowhere to send it. */
    public static void discard() {
        headerCount = 0;
        pixelCount = 0;
    }

    private static void ensureHeader() {
        if (headerCount + HEADER_INTS > header.length) {
            int[] bigger = new int[header.length * 2];
            System.arraycopy(header, 0, bigger, 0, headerCount);
            header = bigger;
        }
    }

    private static void ensurePixels(int needed) {
        if (needed <= pixels.length) {
            return;
        }
        int size = pixels.length;
        while (size < needed) {
            size *= 2;
        }
        int[] bigger = new int[size];
        System.arraycopy(pixels, 0, bigger, 0, pixelCount);
        pixels = bigger;
    }
}
