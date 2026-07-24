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
            // duplicate(): the mirror must not disturb position/limit the GL upload reads
            bridge.mirrorChunkBuffer(glBufferId, data.duplicate());
        }
    }

    public static void onBufferDelete(int glBufferId) {
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        if (bridge != null && bridge.isInitialized()) {
            bridge.releaseChunkBuffer(glBufferId);
        }
    }

}
