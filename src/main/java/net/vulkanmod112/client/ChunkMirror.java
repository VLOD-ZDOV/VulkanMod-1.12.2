package net.vulkanmod112.client;

import net.vulkanmod112.VulkanBridge;
import net.vulkanmod112.VulkanLoader;

import java.nio.ByteBuffer;

/**
 * Game-side receiver for the VertexBuffer mixin. Forwards VBO uploads across
 * the bridge so the Vulkan side keeps a VRAM mirror of all world geometry.
 * Called from the mixin very early — must never assume the bridge exists.
 */
public final class ChunkMirror {

    private ChunkMirror() {
    }

    public static void onBufferData(int slot, ByteBuffer data) {
        VulkanBridge bridge = TerrainHooks.liveBridge();
        if (bridge != null && bridge.isInitialized()) {
            // Passed straight through rather than duplicated. This runs for
            // every chunk the game uploads — a burst of them every time the
            // camera turns — and a defensive copy of the buffer object was an
            // allocation per upload. The mirror reads the address and the
            // remaining count and touches neither position nor limit, which
            // the bridge documents as a requirement, so the GL upload that
            // follows still sees exactly what it expects.
            bridge.mirrorChunkBuffer(slot, data);
        }
    }

    /**
     * Mirrors a chunk from the builder thread that produced it. Returns false
     * when the fast path was unavailable, which is not a failure: the render
     * thread then mirrors it the ordinary way when it uploads the GL buffer.
     */
    public static boolean onWorkerBuild(int slot, ByteBuffer data) {
        VulkanBridge bridge = TerrainHooks.liveBridge();
        return bridge != null && bridge.isInitialized() && bridge.stageChunkBuffer(slot, data);
    }

    /**
     * Hands over what a chunk layer is made of, before its geometry follows.
     *
     * Silently does nothing when the bridge is not up, which is the same answer
     * the geometry paths give: materials are an extra, and a chunk without them
     * draws exactly as terrain always has.
     */
    public static void onMaterials(int slot, int[] runs, int runCount) {
        VulkanBridge bridge = TerrainHooks.liveBridge();
        if (bridge != null && bridge.isInitialized()) {
            bridge.stageChunkMaterials(slot, runs, runCount);
        }
    }

    /** Which layer this slot is for; see {@link VulkanBridge#noteChunkLayer}. */
    public static void onLayer(int slot, boolean translucent) {
        VulkanBridge bridge = TerrainHooks.liveBridge();
        if (bridge != null && bridge.isInitialized()) {
            bridge.noteChunkLayer(slot, translucent);
        }
    }

    public static void onBufferDelete(int slot) {
        VulkanBridge bridge = TerrainHooks.liveBridge();
        if (bridge != null && bridge.isInitialized()) {
            bridge.releaseChunkBuffer(slot);
        }
    }

}
