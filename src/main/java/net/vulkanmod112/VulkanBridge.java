package net.vulkanmod112;

/**
 * Boundary between the game (LWJGL 2 world) and the Vulkan renderer
 * (LWJGL 3 world, loaded in an isolated classloader). Implementations live in
 * net.vulkanmod112.vkimpl and must only be instantiated via VulkanLoader.
 *
 * No org.lwjgl types may ever appear in these signatures: the two sides see
 * different, incompatible org.lwjgl.* classes.
 */
public interface VulkanBridge {

    /** Initializes the Vulkan instance, device and graphics queue. */
    void init();

    /** Releases all Vulkan resources. Safe to call more than once. */
    void destroy();

    boolean isInitialized();

    /** Human-readable summary of the selected GPU, for logs and the F3 screen. */
    String gpuSummary();

    /**
     * Total device-local memory in MiB, or 0 before the device is selected.
     * Drives the automatic geometry budget: this is a hardware fact the game
     * side has no other way to learn.
     */
    int vramMegabytes();

    /**
     * Renders the demo scene offscreen on the GPU via Vulkan and returns the
     * frame as tightly packed RGBA8 pixels ({@code width * height * 4} bytes,
     * top row first). Direct java.nio buffers are safe to pass across the
     * classloader boundary.
     */
    java.nio.ByteBuffer renderDemo(int width, int height);

    /**
     * Sets up the zero-copy Vulkan→OpenGL image sharing (VRAM image visible
     * to both APIs, GPU-side semaphore sync). Must be called on the client
     * thread with the game's GL context current. Returns false when the
     * drivers lack the required extensions — callers should fall back to
     * {@link #renderDemo}.
     */
    boolean initInterop(int width, int height);

    /** GL texture id of the shared image, or -1 before {@link #initInterop}. */
    int interopTextureId();

    /**
     * Renders the next frame into the shared image on the Vulkan queue and
     * enqueues the GL-side wait; after this call the game may draw the
     * texture. Client thread only.
     */
    void renderInteropFrame(float timeSeconds);

    /** Signals Vulkan that the frame was displayed; call after drawing. */
    void interopFrameDisplayed();

    /**
     * Mirrors a game VBO upload into a Vulkan vertex buffer, keyed by the GL
     * buffer id. Client thread only.
     *
     * {@code data} is the game's own buffer, positioned at the payload, and
     * the game uploads it to OpenGL immediately afterwards. Implementations
     * must treat it as read-only and must not change its position or limit.
     */
    void mirrorChunkBuffer(int slot, java.nio.ByteBuffer data);

    /** Frees the Vulkan mirror of a deleted game VBO. */
    void releaseChunkBuffer(int slot);

    /** One-line mirror statistics for the F3 screen. */
    String chunkMirrorStats();

    /**
     * Multi-line dump of everything the Vulkan side knows about itself: device,
     * chosen formats, active code paths, resource counts and the latest frame
     * timings. Plain text so the bridge stays free of LWJGL types.
     */
    String diagnosticsReport();

    /**
     * Copies the game's block atlas (a GL texture) into a Vulkan image.
     * Call on the client thread after texture stitching / resource reloads.
     */
    void updateAtlas(int atlasGlTextureId);

    /** Tells the Vulkan side which GL texture holds the 16x16 lightmap. */
    void setLightmap(int lightmapGlTextureId);

    /**
     * Hands over the game's CPU-side lightmap colors (256 ARGB ints). Avoids
     * a per-frame glGetTexImage pipeline stall; the array is read once per
     * frame at terrain render time.
     */
    void updateLightmapData(int[] argb);

    /**
     * Hands over the fixed-function fog the game has set up for this frame:
     * {r, g, b, mode, start, end, density}, where mode is 0 for off, 1 linear,
     * 2 exponential and 3 exponential squared.
     *
     * Without this the terrain is the only thing in the scene drawn without
     * fog, which is most visible underwater — entities take the colour of the
     * water while the blocks behind them stay clear.
     */
    void updateFogState(float[] fog);

    /**
     * Draws one terrain layer with Vulkan. Layer ordinals follow
     * BlockRenderLayer: 0 SOLID, 1 CUTOUT_MIPPED, 2 CUTOUT, 3 TRANSLUCENT.
     * SOLID begins the frame, CUTOUT submits it and composites color+depth
     * into the game's framebuffer. {@code chunks} packs [slot, x, y, z]
     * per chunk. Returns true when Vulkan took the layer (GL must skip it).
     */
    boolean renderTerrainLayer(int layerOrdinal, int[] chunks, int chunkCount, float[] mvp,
                               double viewX, double viewY, double viewZ, int fbWidth, int fbHeight);

}
