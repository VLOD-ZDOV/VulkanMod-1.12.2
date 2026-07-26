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

    public static void onBufferData(int glBufferId, ByteBuffer data) {
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        if (bridge != null && bridge.isInitialized()) {
            // Passed straight through rather than duplicated. This runs for
            // every chunk the game uploads — a burst of them every time the
            // camera turns — and a defensive copy of the buffer object was an
            // allocation per upload. The mirror reads the address and the
            // remaining count and touches neither position nor limit, which
            // the bridge documents as a requirement, so the GL upload that
            // follows still sees exactly what it expects.
            bridge.mirrorChunkBuffer(glBufferId, data);
        }
    }

    public static void onBufferDelete(int glBufferId) {
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        if (bridge != null && bridge.isInitialized()) {
            bridge.releaseChunkBuffer(glBufferId);
        }
    }

}
