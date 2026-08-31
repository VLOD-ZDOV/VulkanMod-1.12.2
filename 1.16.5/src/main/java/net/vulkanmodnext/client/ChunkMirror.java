package net.vulkanmodnext.client;

import net.vulkanmodnext.vkimpl.VkContext;

import java.nio.ByteBuffer;

/**
 * Game-side receiver for the chunk hooks: hands built geometry to the Vulkan
 * mirror, and says nothing when Vulkan is not up.
 *
 * <p>Called from chunk builder threads, very early and very often, so it must
 * never assume the renderer exists and must not allocate. The buffer is passed
 * straight through rather than copied: the mirror reads the address and the
 * remaining count and touches neither position nor limit, so the OpenGL upload
 * that follows still sees exactly what it expects. A defensive copy here would
 * be an allocation per chunk, in a burst every time the camera turns.
 */
public final class ChunkMirror {

    private ChunkMirror() {
    }

    /**
     * Mirrors a chunk from the builder thread that produced it.
     *
     * @return false when the fast path was unavailable, which is not a failure
     */
    public static boolean onWorkerBuild(int slot, ByteBuffer data) {
        VkContext context = VulkanStartup.context();
        return context != null && context.stageChunkBuffer(slot, data);
    }

    /**
     * Publishes a chunk, on the render thread, as the game uploads it.
     *
     * <h2>Why this is needed when the builder thread already copied it</h2>
     *
     * It is easy to read {@link #onWorkerBuild} as the whole story and drop
     * this, and that is exactly what the first attempt at this port did. The
     * result was a renderer that started, drew every layer, reported no errors
     * and put nothing on the screen: <em>0 chunks, 0 vertices, 790 skipped</em>.
     *
     * <p>Staging on the builder thread only puts the bytes somewhere the card
     * can reach. What turns them into something drawable is this call: it makes
     * the mirror entry, and it takes the staged copy as a fast path instead of
     * copying again. Without it every chunk is staged and none is published.
     */
    public static void onBufferData(int slot, ByteBuffer data) {
        VkContext context = VulkanStartup.context();
        if (context != null) {
            context.mirrorChunkBuffer(slot, data);
        }
    }

    /** What a chunk layer is made of, before its geometry follows. */
    public static void onLayer(int slot, boolean translucent) {
        VkContext context = VulkanStartup.context();
        if (context != null) {
            context.noteChunkLayer(slot, translucent);
        }
    }

    /** The buffer is gone; whatever was staged for it must not be published. */
    public static void onBufferDelete(int slot) {
        VkContext context = VulkanStartup.context();
        if (context != null) {
            context.releaseChunkBuffer(slot);
        }
    }
}
