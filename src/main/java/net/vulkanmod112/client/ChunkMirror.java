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
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
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
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        return bridge != null && bridge.isInitialized() && bridge.stageChunkBuffer(slot, data);
    }

    public static void onBufferDelete(int slot) {
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        if (bridge != null && bridge.isInitialized()) {
            bridge.releaseChunkBuffer(slot);
        }
    }

}
