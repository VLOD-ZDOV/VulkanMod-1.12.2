package net.vulkanmod112.vkimpl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkSpecializationInfo;
import org.lwjgl.vulkan.VkSpecializationMapEntry;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;

/**
 * Stage 3.3: Vulkan draws the world's terrain.
 *
 * Per frame the three opaque-ish layers (SOLID, CUTOUT_MIPPED, CUTOUT) are
 * drawn from the VkChunkMirror buffers into a shared color+depth VRAM frame,
 * which a small GL shader then composites into the game's framebuffer —
 * writing gl_FragDepth so entities and translucent geometry keep correct
 * occlusion. Layer state machine: SOLID begins the command buffer, CUTOUT
 * submits it and runs the composite.
 *
 * v0 compromises (documented, deliberate): no fog and no mipmaps on the
 * atlas. Chunk draws are batched through Vulkan indirect commands.
 */
final class VkTerrainRenderer {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Terrain");
    private static final int BLOCK_VERTEX_STRIDE = 28;
    private static final int LIGHTMAP_SIZE = 16;
    private static final int INITIAL_INDIRECT_DRAWS = 4096;
    /**
     * Ceiling on the growth below. 4096 used to be the fixed size, and at
     * render distance 64 a layer has far more visible chunks than that — the
     * surplus was dropped, so the world had holes and the GPU was handed less
     * work than the scene actually contained.
     */
    private static final int MAX_INDIRECT_DRAWS = 1 << 18;
    private static final int DRAW_ORIGIN_BYTES = 16;
    private static final int DRAW_COMMAND_BYTES = 20;

    private final VulkanContextImpl ctx;

    /**
     * Two frames in flight: the CPU waits for frame N-2 instead of N-1, so it
     * no longer sits out the whole GPU render of the previous frame. GPU-side
     * ordering (terrain N → composite N → terrain N+1) is still enforced by
     * the shared-image semaphore chain.
     */
    private final int framesInFlight = resolveFramesInFlight();

    /**
     * How many terrain frames the CPU may prepare before it has to wait for
     * the GPU. Two means waiting on frame N-2 instead of N-1, so the CPU no
     * longer sits out the whole previous render; three gives it more room
     * again at the cost of a frame of input delay and another set of
     * per-frame buffers. Exposed in the settings screen, read once here
     * because every per-frame array is sized from it.
     */
    private static int resolveFramesInFlight() {
        try {
            int value = Integer.parseInt(System.getProperty("vulkanmod112.framesInFlight", "2"));
            return value < 1 ? 1 : (value > 3 ? 3 : value);
        } catch (NumberFormatException ignored) {
            return 2;
        }
    }

    // Stable resources
    private long commandPool;
    private VkCommandBuffer[] commandBuffers;
    private long[] fences;
    /** Aliases of the current slot's entries, set at the top of beginFrame. */
    private VkCommandBuffer commandBuffer;
    private long fence;
    /** Slot selected for the frame currently being recorded. */
    private int activeFrameSlot;
    private long vkSignalSemaphore;
    private long vkWaitSemaphore;
    private int glWaitSemaphore;
    private int glSignalSemaphore;

    /**
     * A second hand-off, for the translucent pass, and it has to be its own.
     *
     * The pair above is spent by the time the translucent layer is asked for:
     * OpenGL signals it at the end of the opaque composite, which happens
     * before the game draws entities. The translucent pass needs to wait for
     * something signalled <em>after</em> that — after the depth OpenGL now owns
     * has been copied back — and reusing a semaphore already signalled would
     * let the pass read a depth buffer that is still being written. A race of
     * exactly that kind cost a lost device and a revert on the upload ring.
     */
    private long vkTranslucentSignalSemaphore;
    private long vkTranslucentWaitSemaphore;
    private int glTranslucentWaitSemaphore;
    private int glTranslucentSignalSemaphore;
    private VkCommandBuffer[] translucentCommandBuffers;
    private long[] translucentFences;
    private int translucentCompositeProgram;
    private int translucentInvSizeUniform = -1;
    private long renderPass;
    private long descriptorSetLayout;
    private long descriptorPool;
    private long descriptorSet;
    /**
     * Indirect batches and descriptor sets per frame in flight: one for every
     * layer this renderer can draw, translucent included. It was three, which
     * is what {@code activeFrameSlot * 3 + layerOrdinal} indexed by — an
     * arrangement that stops working the moment a fourth layer arrives.
     */
    private static final int BATCHES_PER_FRAME = 4;
    private final long[] drawBatchBuffers = new long[framesInFlight * BATCHES_PER_FRAME];
    private final long[] drawBatchMemories = new long[framesInFlight * BATCHES_PER_FRAME];
    private final long[] drawBatchMapped = new long[framesInFlight * BATCHES_PER_FRAME];
    private final long[] drawDescriptorSets = new long[framesInFlight * BATCHES_PER_FRAME];
    /** Draws each batch buffer can hold; grown to fit the scene, never shrunk. */
    private int indirectDrawCapacity = INITIAL_INDIRECT_DRAWS;
    private long drawCommandOffset = (long) INITIAL_INDIRECT_DRAWS * DRAW_ORIGIN_BYTES;
    private long drawBatchBytes = drawCommandOffset
            + (long) INITIAL_INDIRECT_DRAWS * DRAW_COMMAND_BYTES;
    /** Largest layer of the previous frame; the batch is grown to fit it. */
    private int peakDrawsNeeded;
    /** Whether the indirect batches landed in BAR memory; diagnostics only. */
    private boolean indirectMemoryIsDeviceLocal;
    /** Per-layer lookup results, reused across frames. */
    private VkChunkMirror.Entry[] lookupScratch = new VkChunkMirror.Entry[INITIAL_INDIRECT_DRAWS];
    private long pipelineLayout;
    /**
     * Remembers compiled pipelines between runs. Buys startup time only; see
     * {@link VkPipelineCacheStore} for why every failure in it is silent.
     */
    private final VkPipelineCacheStore pipelineCache = new VkPipelineCacheStore();
    private long pipelineCacheHandle = VK_NULL_HANDLE;
    /**
     * Two specialisations of the same shader modules: index 0 has the alpha
     * test compiled out for SOLID, index 1 keeps it for the CUTOUT layers.
     * Index with {@code layerOrdinal == 0 ? 0 : 1}.
     */
    /**
     * How one terrain pipeline differs from the others.
     *
     * There used to be two of these built by a loop over a bare index, chosen
     * at draw time by {@code layerOrdinal == 0 ? 0 : 1}. That works for exactly
     * as long as the only difference between them is the alpha test, and the
     * translucent layer differs in blending and in depth writes as well —
     * neither of which a specialisation constant can express, because both are
     * pipeline state rather than shader code.
     */
    private static final class TerrainPipeline {
        final String name;
        /** Compiled into the shader; see the constant in terrain.frag. */
        final boolean alphaTest;
        final boolean blend;
        /**
         * Off for anything blended: a translucent surface must not stop what
         * is behind it from being drawn, and the layer is already sorted back
         * to front when the chunk is built.
         */
        final boolean depthWrite;

        TerrainPipeline(String name, boolean alphaTest, boolean blend, boolean depthWrite) {
            this.name = name;
            this.alphaTest = alphaTest;
            this.blend = blend;
            this.depthWrite = depthWrite;
        }
    }

    /**
     * The pipelines this renderer builds, in the order they are created.
     *
     * Adding one is an entry here plus a line in {@link #pipelineForLayer}.
     */
    private static final TerrainPipeline[] TERRAIN_PIPELINES = {
            new TerrainPipeline("solid", false, false, true),
            new TerrainPipeline("cutout", true, false, true),
            new TerrainPipeline("translucent", false, true, false),
    };
    /** Index into {@link #TERRAIN_PIPELINES}; also the layer ordinal vanilla uses. */
    private static final int LAYER_TRANSLUCENT = 3;
    private static final int PIPELINE_TRANSLUCENT = 2;

    /**
     * Vanilla's layer ordinals: SOLID, CUTOUT_MIPPED, CUTOUT, TRANSLUCENT.
     * The two cutout layers share a pipeline and differ only in their cutoff,
     * which is a push constant. -1 means the layer is not ours to draw.
     */
    private static final int[] LAYER_PIPELINE = {0, 1, 1, PIPELINE_TRANSLUCENT};
    private static final float[] LAYER_CUTOFF = {0.0f, 0.5f, 0.1f, 0.0f};

    private final long[] pipelines = new long[TERRAIN_PIPELINES.length];
    private long atlasSampler;
    private long lightmapSampler;

    // Atlas / lightmap
    private long atlasImage;
    private long atlasStagingBuffer;
    private long atlasStagingMemory;
    private long atlasStagingMapped;
    private long atlasStagingCapacity;
    private long atlasMemory;
    private long atlasView;
    private int atlasWidth;
    private int atlasHeight;
    private int atlasLevels = 1;
    private int lightmapGlId = -1;
    private long lightmapImage;
    private long lightmapMemory;
    private long lightmapView;
    /**
     * Per-frame shader constants: the view-projection matrix and the fog the
     * game set up, one buffer per frame in flight and permanently mapped.
     *
     * These used to be push constants, which put them at 96 of the 128 bytes
     * Vulkan guarantees — with a 16-byte draw parameter on top, that was the
     * room gone. Nothing further could be given to the shaders at all, and
     * everything this version is meant to add needs exactly that. They are also
     * per frame rather than per draw, so pushing them was work repeated for
     * every layer to say the same thing.
     *
     * The write happens while recording the frame that will read it, and the
     * slot's fence has already been waited on by then, so no frame still in
     * flight can be reading the bytes being overwritten.
     */
    private final long[] frameUniformBuffers = new long[framesInFlight];
    private final long[] frameUniformMemories = new long[framesInFlight];
    private final long[] frameUniformMapped = new long[framesInFlight];
    /**
     * mat4 mvp | vec4 fogColor | vec4 fogParams | vec4 lightInfo | vec4 lights[32],
     * padded to a round size. Uniform buffers are guaranteed at least 16 KiB,
     * so there is room here for a good deal more than this holds.
     */
    private static final int MAX_DYNAMIC_LIGHTS = 32;
    private static final int FRAME_UNIFORM_BYTES = 1024;
    private final float[] dynamicLights = new float[MAX_DYNAMIC_LIGHTS * 4];
    private int dynamicLightCount;

    private final long[] lightmapStagingBuffer = new long[framesInFlight];
    private final long[] lightmapStagingMemory = new long[framesInFlight];
    private final long[] lightmapStagingMapped = new long[framesInFlight];
    private ByteBuffer lightmapReadBuffer;
    private boolean lightmapImageInitialized;
    /** Hash of the last uploaded lightmap; see beginFrame. */
    private int lightmapHash;
    private boolean lightmapDirty = true;
    /**
     * How often the lightmap actually changed, against how many frames were
     * drawn. The game recomputes it once a tick, so a healthy ratio is about
     * 20 a second regardless of framerate — which is also why lighting changes
     * look stepped underwater, where the brightness ramps continuously.
     */
    private long lightmapUploads;
    private long lightmapFrames;
    private int[] lightmapData;
    /**
     * rgb + mode, then start/end/density/unused. Mode 0 means the game has fog
     * switched off, and the shader skips the blend entirely.
     */
    private final float[] fogState = new float[8];
    /** Start of the clock the shaders animate from; see writeFrameUniforms. */
    private final long startedNanos = System.nanoTime();
    /** 0 leaves dynamic light exactly as vanilla has it; 1 is the full effect. */
    private float directionalDynamicLight = 1.0f;
    /** Whether this frame has a material buffer bound; see writeFrameUniforms. */
    private boolean materialsBound;
    /** Diagnostic: paint the terrain by material instead of by texture. */
    private boolean showMaterials;
    /** How much of a water surface becomes sky at a grazing angle; 0 is off. */
    private float waterReflection;
    /** How far the water surface is tilted by the wave pattern; 0 is off. */
    private float waterWaves;
    /** How far the top of a plant leans in the wind; 0 is off. */
    private float foliageSway;
    /**
     * The camera in world coordinates, kept as doubles.
     *
     * Everything else here is camera-relative, which is what the shaders want
     * and what keeps them in single precision. The waves are the one thing that
     * must not be: a pattern anchored to the camera swims along behind the
     * player. Only the remainder modulo the wave lattice ever leaves this side.
     */
    private double viewWorldX;
    private double viewWorldZ;

    /**
     * Atlas rectangles the translucent shader classifies its fragments by.
     *
     * Eight is a ceiling with room to spare: water still and flowing, ice, lava
     * still and flowing is five. Each entry is minU, minV, maxU, maxV and the
     * material as a fifth float, laid out as two vec4s in the frame's uniform
     * buffer so the shader can walk them without a second binding.
     */
    private static final int MAX_MATERIAL_SPRITES = 8;
    private final float[] materialSprites = new float[MAX_MATERIAL_SPRITES * 8];
    private int materialSpriteCount;

    synchronized void setMaterialSprites(int[] materials, float[] rects, int count) {
        int used = Math.min(count, MAX_MATERIAL_SPRITES);
        for (int i = 0; i < used; i++) {
            materialSprites[i * 8] = rects[i * 4];
            materialSprites[i * 8 + 1] = rects[i * 4 + 1];
            materialSprites[i * 8 + 2] = rects[i * 4 + 2];
            materialSprites[i * 8 + 3] = rects[i * 4 + 3];
            materialSprites[i * 8 + 4] = materials[i];
            materialSprites[i * 8 + 5] = 0.0f;
            materialSprites[i * 8 + 6] = 0.0f;
            materialSprites[i * 8 + 7] = 0.0f;
        }
        materialSpriteCount = used;
        LOGGER.info("Material sprite table: {} entries", used);
    }
    private float heightFogStrength;
    private float heightFogFalloff = 2.0f / 24.0f;

    // Size-dependent shared targets
    private int width;
    private int height;
    /**
     * Where the translucent layer is drawn, kept apart from the opaque colour.
     *
     * It cannot share it. By the time the game asks for translucent terrain it
     * has already drawn entities, particles and weather into its own
     * framebuffer, and the opaque colour here was composited over there long
     * before any of that. Blending water into this image and compositing it
     * again would draw the terrain twice and lose everything OpenGL added in
     * between. So this one is cleared to fully transparent, receives only the
     * translucent layer, and is composited over the game's frame as a layer of
     * its own.
     */
    private long translucentImage;
    private long translucentMemory;
    private long translucentView;
    private long translucentFramebuffer;
    private long translucentRenderPass;
    private int glTranslucentMemoryObject;
    private int glTranslucentTexture = -1;

    private long colorImage;
    private long colorMemory;
    private long colorView;
    private long depthImage;
    private long depthMemory;
    private long depthView;
    private long framebuffer;
    private int glColorTexture = -1;
    private int glDepthTexture = -1;
    private int glColorMemoryObject;
    private int glDepthMemoryObject;

    // Composite GL programs: [0] writes gl_FragDepth, [1] colour only (depth
    // came from the hardware blit). Index with depthBlit ? 1 : 0.
    private final int[] compositePrograms = new int[2];
    /**
     * Bloom, done on the OpenGL side because the composite already is.
     *
     * The Vulkan colour target is exported as a GL texture and drawn as a
     * fullscreen quad, so light spilling off a bright surface is three more
     * quads over the same texture rather than a second renderer: pull out what
     * is glowing, blur it across, add it back. Two half-resolution targets,
     * ping-ponged, because a separable blur needs somewhere to put the first
     * half — and blurring at half resolution is most of the blur for a quarter
     * of the work, which matters because what a blur costs is reading
     * neighbours.
     */
    private final int[] bloomTexture = new int[2];
    private final int[] bloomFbo = new int[2];
    private int bloomWidth;
    private int bloomHeight;
    /**
     * A copy of the emissive mask, taken while the Vulkan target may still be
     * read, at half resolution.
     *
     * The glow is now added after the game has drawn its whole world, and by
     * then the colour target has been handed back to Vulkan through a
     * semaphore — reading it there would be a race with the next frame. The
     * mask is the only thing the last pass still needed from it, so it is taken
     * inside the window and kept here. Half resolution because all it decides
     * is how much of the glow a pixel is allowed to receive, and a boundary two
     * pixels soft on that is better than a hard one, not worse.
     */
    private int bloomMaskTexture;
    private int bloomMaskFbo;
    private int bloomMaskProgram;
    private int bloomMaskInvSize = -1;
    /** Set when the glow is blurred and waiting; cleared when it is added. */
    private boolean bloomReady;
    private int bloomExtractProgram;
    private int bloomBlurProgram;
    private int bloomAddProgram;
    private int bloomExtractInvSize = -1;
    private int bloomBlurStep = -1;
    private int bloomBlurInvSize = -1;
    private int bloomAddInvSize = -1;
    private int bloomAddStrength = -1;
    /** How much of the glow is added back; 0 is off and skips every pass. */
    private float bloomStrength;
    /** Set once if anything about the bloom targets fails; never retried. */
    private boolean bloomFailed;
    private final int[] compositeInvSizeUniforms = {-1, -1};
    /**
     * Copying depth with glBlitFramebuffer instead of writing gl_FragDepth
     * lets the composite quad keep early-Z and skips a per-pixel depth export.
     * Requires our depth target to match the game's depth format, so it is
     * only attempted when the D24 target was created, and switched off for
     * good if the driver rejects the blit.
     */
    private boolean depthBlit;
    private int glDepthBlitFbo = -1;
    /** 0 until the first format query; see depthFormat(MemoryStack). */
    private int depthFormat;

    // Shared quad→triangle index buffer (pattern 0,1,2 / 0,2,3 per quad):
    // vanilla chunk VBOs hold GL_QUADS, Vulkan only rasterizes triangles
    private long quadIndexBuffer;
    private long quadIndexMemory;
    private int quadIndexCapacityQuads;

    private boolean baseReady;
    private boolean firstFrame = true;
    private boolean frameOpen;

    /**
     * The startup coverage readback, off unless asked for.
     *
     * On the second and hundred-and-twentieth frame this used to stall the
     * pipeline with {@code glFinish} and pull the whole colour attachment back
     * across the bus — at 4K that is some thirty megabytes and a full stop of
     * both processors, twice, in the seconds where the world is being built and
     * the frame budget matters most. It answered one question, once: whether
     * this renderer was putting anything on screen at all. That question has
     * been answered, and the check for it sat in the per-draw path of every
     * frame ever since.
     *
     * {@code -Dvulkanmod112.startupReadback=true} brings it back for the next
     * time something is drawing nothing.
     */
    private static final boolean STARTUP_READBACK =
            Boolean.getBoolean("vulkanmod112.startupReadback");

    // Diagnostics (first frames are logged with a coverage readback)
    private long frameCounter;
    private int frameChunks;
    private int frameVertices;
    private int frameSkipped;
    private boolean glErrorLogged;
    // Frame-time breakdown, averaged and logged every TIMING_WINDOW frames
    private static final int TIMING_WINDOW = 600;
    private long fenceWaitNanos;
    private long recordNanos;
    private long submitCompositeNanos;
    private long timingWindowStartNanos;
    // GPU-side cost of the terrain pass, read back from timestamp queries one
    // frame late (the fence for a slot guarantees its queries have landed)
    private long queryPool;
    private float timestampPeriod;
    private boolean timestampsSupported;
    private long gpuNanos;
    private int gpuSamples;
    // VK-side readback of a horizontal strip of the color target: tells apart
    // "Vulkan drew nothing" from "GL cannot see what Vulkan drew"
    private static final int READBACK_ROWS = 8;
    private long readbackBuffer;
    private long readbackMemory;
    private long readbackMapped;
    private boolean readbackRecorded;

    VkTerrainRenderer(VulkanContextImpl ctx) {
        this.ctx = ctx;
    }

    private VkDevice device() {
        return ctx.getDevice();
    }

    // ------------------------------------------------------------------
    // Public entry points (called via the bridge, client thread)
    // ------------------------------------------------------------------

    /**
     * Replaces rectangles of the atlas with the animation frames of one tick.
     *
     * The copy of the atlas here is made once, out of OpenGL, and the game goes
     * on writing new frames into its own texture for as long as the world is
     * open. Without this the terrain shows whichever frame the atlas happened
     * to hold when it was read: lava, water, fire, portals and sea lanterns all
     * standing still, while the same block held in the hand — drawn by OpenGL
     * from the game's own texture — animates as it always did.
     *
     * Everything the tick produced arrives together and leaves in one
     * submission. Per sprite it would be a queue submission and a wait apiece,
     * twenty times a second, for a few kilobytes each.
     */
    synchronized void updateAtlasRegions(int[] header, int headerCount, int[] pixels, int pixelCount) {
        if (atlasImage == 0 || headerCount == 0 || pixelCount == 0) {
            return;
        }
        long bytes = (long) pixelCount * 4L;
        try (MemoryStack stack = stackPush()) {
            if (!ensureAtlasStaging(stack, bytes)) {
                return;
            }
            // The game's pixels are 0xAARRGGBB in an int; the image wants the
            // bytes in the order red, green, blue, alpha. Written straight into
            // mapped memory rather than through a ByteBuffer view, because this
            // runs every tick and the conversion is the whole cost.
            long dst = atlasStagingMapped;
            for (int i = 0; i < pixelCount; i++) {
                int argb = pixels[i];
                MemoryUtil.memPutByte(dst++, (byte) (argb >> 16));
                MemoryUtil.memPutByte(dst++, (byte) (argb >> 8));
                MemoryUtil.memPutByte(dst++, (byte) argb);
                MemoryUtil.memPutByte(dst++, (byte) (argb >>> 24));
            }

            int regions = headerCount / 6;
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pBuffer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device(), allocInfo, pBuffer),
                    "vkAllocateCommandBuffers(atlas regions)");
            VkCommandBuffer cmd = new VkCommandBuffer(pBuffer.get(0), device());
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer(atlas regions)");

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(atlasImage)
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            barrier.get(0).subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS)
                    .baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(regions, stack);
            for (int r = 0; r < regions; r++) {
                int base = r * 6;
                final int level = header[base];
                final int x = header[base + 1];
                final int y = header[base + 2];
                final int w = header[base + 3];
                final int h = header[base + 4];
                copy.get(r)
                        .bufferOffset((long) header[base + 5] * 4L)
                        .bufferRowLength(0)
                        .bufferImageHeight(0)
                        .imageOffset(o -> o.x(x).y(y).z(0))
                        .imageExtent(e -> e.width(w).height(h).depth(1));
                copy.get(r).imageSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(level).baseArrayLayer(0).layerCount(1);
            }
            vkCmdCopyBufferToImage(cmd, atlasStagingBuffer, atlasImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);

            barrier.get(0)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier);
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer(atlas regions)");

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pFence = stack.mallocLong(1);
            check(vkCreateFence(device(), fenceInfo, null, pFence), "vkCreateFence(atlas regions)");
            long fence = pFence.get(0);
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd));
            check(vkQueueSubmit(ctx.getGraphicsQueue(), submit, fence),
                    "vkQueueSubmit(atlas regions)");
            check(vkWaitForFences(device(), fence, true, 5_000_000_000L),
                    "vkWaitForFences(atlas regions)");
            vkDestroyFence(device(), fence, null);
            vkFreeCommandBuffers(device(), commandPool, cmd);
        }
    }

    /** Host-visible staging for the frames of one tick; grows and stays. */
    private boolean ensureAtlasStaging(MemoryStack stack, long bytes) {
        if (atlasStagingBuffer != 0 && bytes <= atlasStagingCapacity) {
            return true;
        }
        destroyAtlasStaging();
        VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(bytes)
                .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer pBuffer = stack.mallocLong(1);
        if (vkCreateBuffer(device(), info, null, pBuffer) != VK_SUCCESS) {
            return false;
        }
        atlasStagingBuffer = pBuffer.get(0);
        VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
        vkGetBufferMemoryRequirements(device(), atlasStagingBuffer, req);
        VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
        LongBuffer pMemory = stack.mallocLong(1);
        if (vkAllocateMemory(device(), alloc, null, pMemory) != VK_SUCCESS) {
            vkDestroyBuffer(device(), atlasStagingBuffer, null);
            atlasStagingBuffer = 0;
            return false;
        }
        atlasStagingMemory = pMemory.get(0);
        check(vkBindBufferMemory(device(), atlasStagingBuffer, atlasStagingMemory, 0),
                "vkBindBufferMemory(atlas staging)");
        PointerBuffer pMapped = stack.mallocPointer(1);
        check(vkMapMemory(device(), atlasStagingMemory, 0, req.size(), 0, pMapped),
                "vkMapMemory(atlas staging)");
        atlasStagingMapped = pMapped.get(0);
        atlasStagingCapacity = bytes;
        return true;
    }

    private void destroyAtlasStaging() {
        if (atlasStagingMemory != 0) {
            vkUnmapMemory(device(), atlasStagingMemory);
            vkFreeMemory(device(), atlasStagingMemory, null);
            atlasStagingMemory = 0;
        }
        if (atlasStagingBuffer != 0) {
            vkDestroyBuffer(device(), atlasStagingBuffer, null);
            atlasStagingBuffer = 0;
        }
        atlasStagingMapped = 0;
        atlasStagingCapacity = 0;
    }

    synchronized void updateAtlas(int atlasGlId) {
        ctx.ensureGlCapabilities();
        destroyAtlas();
        try (MemoryStack stack = stackPush()) {
            int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, atlasGlId);
            atlasWidth = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
            atlasHeight = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
            // Minecraft builds the atlas mip chain per sprite, so colours never
            // bleed between neighbouring textures. Copying those levels is both
            // cheaper and more correct than generating our own with vkCmdBlitImage.
            ByteBuffer[] levels = readAtlasLevels();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
            try {
                long[] imageOut = new long[3];
                createSampledImage(stack, atlasWidth, atlasHeight, levels, imageOut);
                atlasImage = imageOut[0];
                atlasMemory = imageOut[1];
                atlasView = imageOut[2];
            } finally {
                for (ByteBuffer level : levels) {
                    MemoryUtil.memFree(level);
                }
            }
        }
        updateDescriptors();
        LOGGER.info("Block atlas copied to Vulkan: {}x{}, {} mip level(s)",
                atlasWidth, atlasHeight, atlasLevels);
    }

    /** Reads level 0 plus every mip level the bound GL atlas actually has. */
    private ByteBuffer[] readAtlasLevels() {
        java.util.List<ByteBuffer> levels = new java.util.ArrayList<ByteBuffer>();
        int w = atlasWidth;
        int h = atlasHeight;
        int level = 0;
        while (w >= 1 && h >= 1) {
            if (level > 0
                    && GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, level, GL11C.GL_TEXTURE_WIDTH) != w) {
                break; // mipmaps turned off in video settings, or the chain ends here
            }
            ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
            GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, level, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
            levels.add(pixels);
            w /= 2;
            h /= 2;
            level++;
        }
        atlasLevels = levels.size();
        return levels.toArray(new ByteBuffer[0]);
    }

    synchronized void setLightmap(int glTextureId) {
        this.lightmapGlId = glTextureId;
        if (lightmapImage == 0) {
            createLightmapResources();
            updateDescriptors();
        }
    }

    /** CPU-side lightmap colors (256 ARGB ints); preferred over glGetTexImage. */
    /** rgb, mode, start, end, density, unused — see {@link #fogState}. */
    synchronized void setFogState(float[] fog) {
        if (fog != null && fog.length >= 7) {
            System.arraycopy(fog, 0, fogState, 0, 7);
        }
    }

    synchronized void setLightmapData(int[] argb) {
        if (argb != null && argb.length == LIGHTMAP_SIZE * LIGHTMAP_SIZE) {
            this.lightmapData = argb;
        }
    }

    /**
     * @return true when the layer was consumed by Vulkan.
     */
    synchronized boolean renderLayer(int layerOrdinal, int[] chunks, int chunkCount, float[] mvp,
                                     double viewX, double viewY, double viewZ,
                                     int fbWidth, int fbHeight, VkChunkMirror mirror) {
        if (mirror == null || atlasImage == 0 || lightmapGlId == -1) {
            return false;
        }
        ctx.ensureGlCapabilities();
        ensureBaseResources();
        // Before the first layer opens the frame, because that is where the
        // uniforms are written and the translucent pass reuses them.
        viewWorldX = viewX;
        viewWorldZ = viewZ;
        if (layerOrdinal == LAYER_TRANSLUCENT) {
            // Its own pass, its own submission, and it runs after the opaque
            // frame has already been composited — so none of the state machine
            // below applies to it.
            if (frameOpen || colorImage == 0) {
                return false;
            }
            long t = System.nanoTime();
            boolean taken = renderTranslucent(chunks, chunkCount, mvp, viewX, viewY, viewZ, mirror);
            recordNanos += System.nanoTime() - t;
            return taken;
        }
        if (layerOrdinal == 0) {
            ensureTargets(fbWidth, fbHeight);
            // Sized from the previous frame's largest layer as well, so a growth
            // step is not spent on SOLID only to be undone by CUTOUT.
            ensureDrawBatchCapacity(Math.max(chunkCount, peakDrawsNeeded));
            peakDrawsNeeded = chunkCount;
            beginFrame(mvp, mirror);
        } else if (chunkCount > peakDrawsNeeded) {
            peakDrawsNeeded = chunkCount;
        }
        if (!frameOpen) {
            return false; // out-of-order layer call (frame not started): let GL draw it
        }
        long t0 = System.nanoTime();
        drawChunks(layerOrdinal, chunks, chunkCount, mvp, viewX, viewY, viewZ, mirror);
        recordNanos += System.nanoTime() - t0;
        if (layerOrdinal == 2) {
            long t1 = System.nanoTime();
            submitFrame();
            composite();
            submitCompositeNanos += System.nanoTime() - t1;
            frameCounter++;
            logFrameDiagnostics();
            logFrameTimings();
        }
        return true;
    }

    private void logFrameTimings() {
        if (frameCounter % TIMING_WINDOW != 0) {
            return;
        }
        long now = System.nanoTime();
        if (timingWindowStartNanos != 0) {
            double frames = TIMING_WINDOW;
            LOGGER.info("Terrain timings over {} frames: fence wait {} ms, record {} ms, "
                            + "submit+composite {} ms, GPU {} per frame; {} fps overall",
                    TIMING_WINDOW,
                    String.format("%.2f", fenceWaitNanos / frames / 1e6),
                    String.format("%.2f", recordNanos / frames / 1e6),
                    String.format("%.2f", submitCompositeNanos / frames / 1e6),
                    gpuTimeText(),
                    String.format("%.0f", frames * 1e9 / (now - timingWindowStartNanos)));
        }
        timingWindowStartNanos = now;
        fenceWaitNanos = 0;
        recordNanos = 0;
        submitCompositeNanos = 0;
        gpuNanos = 0;
        gpuSamples = 0;
    }

    /** Everything the ultra log wants to know about this renderer. */
    synchronized void appendDiagnostics(StringBuilder sb) {
        sb.append("  terrain: frame ").append(frameCounter)
                .append(", ").append(frameChunks).append(" chunks, ")
                .append(frameVertices).append(" vertices, ")
                .append(frameSkipped).append(" skipped\n");
        sb.append("  targets: ").append(width).append('x').append(height)
                .append(", depth ").append(depthFormat == VK_FORMAT_X8_D24_UNORM_PACK32 ? "D24" : "D32F")
                .append(", depth blit ").append(depthBlit ? "on" : "off")
                .append(", atlas ").append(atlasWidth).append('x').append(atlasHeight)
                .append(" (").append(atlasLevels).append(" mips)\n");
        sb.append("  frame cost: fence wait ")
                .append(String.format("%.2f", fenceWaitNanos / (double) Math.max(1, timingSamples()) / 1e6))
                .append(" ms, record ")
                .append(String.format("%.2f", recordNanos / (double) Math.max(1, timingSamples()) / 1e6))
                .append(" ms, submit+composite ")
                .append(String.format("%.2f", submitCompositeNanos / (double) Math.max(1, timingSamples()) / 1e6))
                .append(" ms, GPU ").append(gpuTimeText()).append('\n');
        sb.append("  lightmap: ").append(lightmapUploads).append(" changes over ")
                .append(lightmapFrames).append(" frames")
                .append(lightmapFrames > 0
                        ? String.format(" (1 per %.1f frames)", lightmapFrames / (double) Math.max(1, lightmapUploads))
                        : "")
                .append("; the game recomputes it once a tick, so ~20/s is expected\n");
        sb.append("  index buffer: ").append(quadIndexCapacityQuads).append(" quads")
                .append(", draw batch ").append(indirectDrawCapacity)
                .append(" in ").append(indirectMemoryIsDeviceLocal ? "BAR (device-local)" : "host")
                .append(" memory, frames in flight ").append(framesInFlight).append('\n');
        if (glErrorLogged) {
            sb.append("  WARNING: a GL error was reported during composite (see the main log)\n");
        }
        if (frameSkipped > 0) {
            sb.append("  WARNING: ").append(frameSkipped)
                    .append(" chunks were skipped last frame — no mirror, or past the draw cap\n");
        }
    }

    /** Frames accumulated into the current timing window. */
    private int timingSamples() {
        return (int) (frameCounter % TIMING_WINDOW == 0 ? TIMING_WINDOW : frameCounter % TIMING_WINDOW);
    }

    /** Average GPU time over the window, or "n/a" where the queue has no timestamps. */
    private String gpuTimeText() {
        if (!timestampsSupported) {
            return "n/a";
        }
        if (gpuSamples == 0) {
            return "pending";
        }
        return String.format("%.2f ms", gpuNanos / (double) gpuSamples / 1e6);
    }

    /**
     * The graphics queue writes a timestamp before and after the terrain pass.
     * Reading them costs nothing here because the slot's fence has already been
     * waited on, so the results are guaranteed to be available.
     */
    private void createQueryPool(MemoryStack stack) {
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(ctx.getPhysicalDevice(), props);
        timestampPeriod = props.limits().timestampPeriod();

        IntBuffer familyCount = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(ctx.getPhysicalDevice(), familyCount, null);
        VkQueueFamilyProperties.Buffer families =
                VkQueueFamilyProperties.malloc(familyCount.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(ctx.getPhysicalDevice(), familyCount, families);
        int validBits = families.get(ctx.getGraphicsQueueFamily()).timestampValidBits();

        timestampsSupported = timestampPeriod > 0.0f && validBits > 0;
        if (!timestampsSupported) {
            LOGGER.info("Graphics queue has no timestamp support; GPU timings unavailable");
            return;
        }
        VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                .queryType(VK_QUERY_TYPE_TIMESTAMP)
                .queryCount(framesInFlight * 2);
        LongBuffer pPool = stack.mallocLong(1);
        check(vkCreateQueryPool(device(), info, null, pPool), "vkCreateQueryPool(terrain)");
        queryPool = pPool.get(0);
    }

    private void readGpuTimestamps(MemoryStack stack, int slot) {
        if (!timestampsSupported || frameCounter < framesInFlight) {
            return; // this slot has not run yet
        }
        LongBuffer results = stack.mallocLong(2);
        int result = vkGetQueryPoolResults(device(), queryPool, slot * 2, 2, results, 8,
                VK_QUERY_RESULT_64_BIT);
        if (result != VK_SUCCESS) {
            return; // VK_NOT_READY: skip this sample rather than stall the frame
        }
        long delta = results.get(1) - results.get(0);
        if (delta > 0) {
            gpuNanos += (long) (delta * timestampPeriod);
            gpuSamples++;
        }
    }

    // ------------------------------------------------------------------
    // Frame assembly
    // ------------------------------------------------------------------

    private void beginFrame(float[] mvp, VkChunkMirror mirror) {
        try (MemoryStack stack = stackPush()) {
            // Queue uploads before this frame. The graphics queue preserves
            // submission order, so the vertex fetches below see device-local
            // copies without a CPU-side wait.
            mirror.flushUploads();
            int slot = (int) (frameCounter % framesInFlight);
            activeFrameSlot = slot;
            commandBuffer = commandBuffers[slot];
            fence = fences[slot];
            long t0 = System.nanoTime();
            check(vkWaitForFences(device(), fence, true, 1_000_000_000L), "vkWaitForFences");
            fenceWaitNanos += System.nanoTime() - t0;
            vkResetFences(device(), fence);
            readGpuTimestamps(stack, slot);
            // This slot's fence covers frame N-2; everything up to it is done
            mirror.setFrameStamp(frameCounter);
            mirror.flushRetired(frameCounter - framesInFlight);
            ensureQuadIndexCapacity(mirror.maxEntrySize() / BLOCK_VERTEX_STRIDE / 4);
            frameChunks = 0;
            frameVertices = 0;
            frameSkipped = 0;

            // The lightmap changes when the light level does — dawn, dusk,
            // walking into a cave — and is identical on the great majority of
            // frames. Hashing 256 ints is far cheaper than writing 1 KiB of
            // staging and running two layout barriers plus a copy for data
            // the image already holds.
            lightmapDirty = true;
            lightmapFrames++;
            if (lightmapData != null) {
                int hash = hashLightmap(lightmapData);
                if (lightmapImageInitialized && hash == lightmapHash) {
                    lightmapDirty = false;
                } else {
                    lightmapHash = hash;
                    lightmapUploads++;
                    writeLightmapStaging(lightmapData);
                }
            } else {
                readLightmapFromGL(); // fallback: stalls the GL pipeline
            }

            vkResetCommandBuffer(commandBuffer, 0);
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer");

            if (timestampsSupported) {
                // Must be outside a render pass, so it goes first.
                vkCmdResetQueryPool(commandBuffer, queryPool, slot * 2, 2);
                vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        queryPool, slot * 2);
            }

            if (lightmapDirty) {
                recordLightmapUpload(stack);
            }

            VkClearValue.Buffer clears = VkClearValue.calloc(2, stack);
            clears.get(0).color()
                    .float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 0.0f);
            clears.get(1).depthStencil().depth(1.0f).stencil(0);

            VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(framebuffer)
                    .renderArea(VkRect2D.calloc(stack)
                            .extent(VkExtent2D.calloc(stack).width(width).height(height)))
                    .pClearValues(clears);
            vkCmdBeginRenderPass(commandBuffer, rpBegin, VK_SUBPASS_CONTENTS_INLINE);

            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout,
                    0, stack.longs(descriptorSet), null);
            vkCmdBindIndexBuffer(commandBuffer, quadIndexBuffer, 0, VK_INDEX_TYPE_UINT32);

            // The matrix and the fog are the same for every chunk and every
            // layer, so they live in this frame's uniform buffer rather than
            // being pushed again for each of them.
            refreshShaderSettings();
            // Asked here rather than remembered from last frame: the buffer
            // appears the first time a chunk carries materials, and a shader
            // told about it a frame late would read the one frame where the
            // second binding is still the geometry buffer.
            materialsBound = mirror != null && mirror.materialBuffer() != 0;
            writeFrameUniforms(mvp, fogState);

            // Standard (y-down) viewport: the GL-sourced matrices produce a
            // vertically flipped image in Vulkan's convention, which is
            // exactly GL's texture orientation — the composite pass and the
            // depth readback then sample it without any flip.
            org.lwjgl.vulkan.VkViewport.Buffer viewport = org.lwjgl.vulkan.VkViewport.calloc(1, stack);
            viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(commandBuffer, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).extent(VkExtent2D.calloc(stack).width(width).height(height));
            vkCmdSetScissor(commandBuffer, 0, scissor);

            frameOpen = true;
        }
    }

    private void drawChunks(int layerOrdinal, int[] chunks, int chunkCount, float[] mvp,
                            double viewX, double viewY, double viewZ, VkChunkMirror mirror) {
        int variant = pipelineForLayer(layerOrdinal);
        if (variant < 0) {
            return;
        }
        float cutoff = LAYER_CUTOFF[layerOrdinal];
        try (MemoryStack stack = stackPush()) {
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelines[variant]);
            // The only thing left that changes between draws. Per-chunk origins
            // are fetched by the vertex shader from the storage buffer.
            ByteBuffer drawPush = stack.calloc(16);
            drawPush.putFloat(0, cutoff);
            vkCmdPushConstants(commandBuffer, pipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, drawPush);
            long geometry = mirror.geometryBuffer();
            long materials = mirror.materialBuffer();
            // All chunks are suballocations of this one device-local buffer.
            //
            // The second binding is the materials, and when there are none the
            // geometry buffer is bound in its place. Something has to be bound
            // — the pipeline declares the binding — and this is in bounds by
            // construction: read at stride one, the furthest vertex of the
            // furthest chunk lands at a twenty-eighth of the buffer it is
            // reading from. What comes back is meaningless, and the shader is
            // told so and never looks.
            vkCmdBindVertexBuffers(commandBuffer, 0,
                    stack.longs(geometry, materials == 0 ? geometry : materials),
                    stack.longs(0L, 0L));
            boolean logInputs = STARTUP_READBACK && layerOrdinal == 0
                    && (frameCounter == 0 || frameCounter == 119);

            int batchIndex = activeFrameSlot * BATCHES_PER_FRAME + layerOrdinal;
            long mapped = drawBatchMapped[batchIndex];
            int drawCount = 0;
            if (lookupScratch.length < chunkCount) {
                lookupScratch = new VkChunkMirror.Entry[Integer.highestOneBit(chunkCount) * 2];
            }
            // One monitor acquisition for the whole layer, not one per chunk.
            mirror.findAll(chunks, chunkCount, lookupScratch);
            for (int c = 0; c < chunkCount; c++) {
                VkChunkMirror.Entry entry = lookupScratch[c];
                if (entry == null || entry.size < BLOCK_VERTEX_STRIDE
                        || entry.size % BLOCK_VERTEX_STRIDE != 0) {
                    frameSkipped++;
                    continue;
                }
                int vertexCount = entry.size / BLOCK_VERTEX_STRIDE;
                if (vertexCount / 4 > quadIndexCapacityQuads) {
                    frameSkipped++;
                    continue; // grew mid-frame; drawable next frame
                }
                if (drawCount >= indirectDrawCapacity) {
                    frameSkipped++;
                    continue;
                }
                frameChunks++;
                frameVertices += vertexCount;
                long origin = mapped + (long) drawCount * DRAW_ORIGIN_BYTES;
                MemoryUtil.memPutFloat(origin, (float) (chunks[c * 4 + 1] - viewX));
                MemoryUtil.memPutFloat(origin + 4, (float) (chunks[c * 4 + 2] - viewY));
                MemoryUtil.memPutFloat(origin + 8, (float) (chunks[c * 4 + 3] - viewZ));
                // Where this chunk's first vertex sits within a quad.
                //
                // gl_VertexIndex carries the draw's vertexOffset added in, and
                // the vertex shader needs the corner number inside the quad to
                // tell the top of a plant from its bottom. Every suballocation
                // begins on a vertex boundary but not necessarily on a quad
                // one, so the offset is not always a multiple of four and the
                // low two bits cannot simply be masked off. Handing them over
                // costs a float that was being written as zero anyway.
                int baseVertex = (int) (entry.offset / BLOCK_VERTEX_STRIDE);
                MemoryUtil.memPutFloat(origin + 12, baseVertex & 3);
                if (logInputs) {
                    logInputs = false;
                    ByteBuffer push = stack.malloc(12);
                    push.putFloat(0, MemoryUtil.memGetFloat(origin));
                    push.putFloat(4, MemoryUtil.memGetFloat(origin + 4));
                    push.putFloat(8, MemoryUtil.memGetFloat(origin + 8));
                    logDrawInputs(mvp, push, entry);
                }
                long command = mapped + drawCommandOffset + (long) drawCount * DRAW_COMMAND_BYTES;
                MemoryUtil.memPutInt(command, vertexCount / 4 * 6);
                MemoryUtil.memPutInt(command + 4, 1);
                MemoryUtil.memPutInt(command + 8, 0);
                MemoryUtil.memPutInt(command + 12, (int) (entry.offset / BLOCK_VERTEX_STRIDE));
                MemoryUtil.memPutInt(command + 16, drawCount++);
            }
            if (drawCount != 0) {
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout,
                        0, stack.longs(drawDescriptorSets[batchIndex]), null);
                vkCmdDrawIndexedIndirect(commandBuffer, drawBatchBuffers[batchIndex], drawCommandOffset,
                        drawCount, DRAW_COMMAND_BYTES);
            }
        }
    }

    /** One-shot dump of everything the vertex stage consumes, for offline math checks. */
    private void logDrawInputs(float[] mvp, ByteBuffer push, VkChunkMirror.Entry entry) {
        StringBuilder sb = new StringBuilder("Draw inputs (frame ").append(frameCounter + 1).append("): mvp=[");
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("%.4f", mvp[i])).append(i == 15 ? "]" : " ");
        }
        sb.append(" offset=[").append(push.getFloat(0)).append(' ').append(push.getFloat(4))
                .append(' ').append(push.getFloat(8)).append(']');
        // The first vertices used to be dumped here from the chunk's own
        // staging copy. Uploads now pass through a shared ring that is
        // overwritten within a few hundred chunks, so there is no copy left to
        // read — and the geometry buffer is device-local.
        sb.append(" verts=").append(entry.size / BLOCK_VERTEX_STRIDE);
        LOGGER.info(sb.toString());
    }

    private void submitFrame() {
        try (MemoryStack stack = stackPush()) {
            vkCmdEndRenderPass(commandBuffer);
            if (STARTUP_READBACK && (frameCounter == 0 || frameCounter == 119)) {
                recordColorReadback(stack);
            }
            if (timestampsSupported) {
                vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                        queryPool, activeFrameSlot * 2 + 1);
            }
            check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(commandBuffer))
                    .pSignalSemaphores(stack.longs(vkSignalSemaphore));
            if (!firstFrame) {
                submit.waitSemaphoreCount(1)
                        .pWaitSemaphores(stack.longs(vkWaitSemaphore))
                        .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));
            }
            firstFrame = false;
            check(vkQueueSubmit(ctx.getGraphicsQueue(), submit, fence), "vkQueueSubmit(terrain)");
            frameOpen = false;
        }
    }

    /**
     * Draws the translucent layer, after the game has drawn everything that
     * belongs behind it.
     *
     * The order matters more than the drawing does. Vanilla asks for this layer
     * once entities, particles and weather are already in its framebuffer, and
     * draws it depth-tested against them with depth writes off. This renderer
     * composited its opaque terrain long before that, so the depth image here
     * still holds terrain alone — which is why the first thing that happens is
     * copying the game's depth back into it. Skip that and water is drawn over
     * anything swimming behind it.
     *
     * @return true when the layer was taken and OpenGL should not draw it
     */
    private boolean renderTranslucent(int[] chunks, int chunkCount, float[] mvp,
                                      double viewX, double viewY, double viewZ,
                                      VkChunkMirror mirror) {
        if (translucentFramebuffer == 0 || !depthBlit || chunkCount == 0) {
            // Without the depth blit there is no way to get the game's depth
            // back, and drawing the layer without it would be worse than
            // leaving it where it is.
            return false;
        }
        int slot = activeFrameSlot;
        try (MemoryStack stack = stackPush()) {
            check(vkWaitForFences(device(), translucentFences[slot], true, Long.MAX_VALUE),
                    "vkWaitForFences(translucent)");
            check(vkResetFences(device(), translucentFences[slot]), "vkResetFences(translucent)");

            importGlDepth();

            VkCommandBuffer cmd = translucentCommandBuffers[slot];
            check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer(translucent)");
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer(translucent)");

            // One clear value only: the depth attachment is loaded, not cleared.
            VkClearValue.Buffer clears = VkClearValue.calloc(1, stack);
            clears.get(0).color().float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 0.0f);
            VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(translucentRenderPass)
                    .framebuffer(translucentFramebuffer)
                    .renderArea(VkRect2D.calloc(stack)
                            .extent(VkExtent2D.calloc(stack).width(width).height(height)))
                    .pClearValues(clears);
            vkCmdBeginRenderPass(cmd, rpBegin, VK_SUBPASS_CONTENTS_INLINE);

            vkCmdBindIndexBuffer(cmd, quadIndexBuffer, 0, VK_INDEX_TYPE_UINT32);
            org.lwjgl.vulkan.VkViewport.Buffer viewport = org.lwjgl.vulkan.VkViewport.calloc(1, stack);
            viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).extent(VkExtent2D.calloc(stack).width(width).height(height));
            vkCmdSetScissor(cmd, 0, scissor);

            VkCommandBuffer previous = commandBuffer;
            commandBuffer = cmd;
            try {
                drawChunks(LAYER_TRANSLUCENT, chunks, chunkCount, mvp, viewX, viewY, viewZ, mirror);
            } finally {
                commandBuffer = previous;
            }

            vkCmdEndRenderPass(cmd);
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer(translucent)");

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd))
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(vkTranslucentWaitSemaphore))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT))
                    .pSignalSemaphores(stack.longs(vkTranslucentSignalSemaphore));
            check(vkQueueSubmit(ctx.getGraphicsQueue(), submit, translucentFences[slot]),
                    "vkQueueSubmit(translucent)");
        }
        compositeTranslucent();
        return true;
    }

    /**
     * Copies the depth the game now owns into the shared image, then tells
     * Vulkan it may read it.
     *
     * This is the mirror of {@link #blitDepth}, which sends depth the other
     * way after the opaque pass. Between the two, OpenGL has drawn entities,
     * and their depth is exactly what the translucent layer has to be tested
     * against.
     */
    private void importGlDepth() {
        GL11C.glGetError();
        int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, glDepthBlitFbo);
        GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
        int error = GL11C.glGetError();
        if (error != 0 && !glErrorLogged) {
            glErrorLogged = true;
            LOGGER.error("glBlitFramebuffer(depth back into Vulkan) failed with 0x{}",
                    Integer.toHexString(error));
        }
        try (MemoryStack stack = stackPush()) {
            IntBuffer noBuffers = stack.mallocInt(0);
            IntBuffer textures = stack.ints(glDepthTexture);
            IntBuffer layouts = stack.ints(EXTSemaphore.GL_LAYOUT_DEPTH_STENCIL_ATTACHMENT_EXT);
            EXTSemaphore.glSignalSemaphoreEXT(glTranslucentSignalSemaphore, noBuffers, textures, layouts);
        }
        // Without this the signal can sit in the GL command stream while the
        // Vulkan queue is already waiting on it, and neither side moves.
        GL11C.glFlush();
    }

    /** Blends the translucent target over the game's frame. */
    private void compositeTranslucent() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer noBuffers = stack.mallocInt(0);
            IntBuffer textures = stack.ints(glTranslucentTexture);
            IntBuffer layouts = stack.ints(EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT);
            EXTSemaphore.glWaitSemaphoreEXT(glTranslucentWaitSemaphore, noBuffers, textures, layouts);

            int prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            int prevActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT
                    | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
                    | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                    | org.lwjgl.opengl.GL11.GL_TEXTURE_BIT
                    | org.lwjgl.opengl.GL11.GL_CURRENT_BIT
                    | org.lwjgl.opengl.GL11.GL_POLYGON_BIT);

            GL11C.glDisable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            // Occlusion was settled by the depth test in the Vulkan pass, so
            // this only has to put the colour down in the right proportion.
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(false);
            GL11C.glEnable(GL11C.GL_BLEND);
            // The target holds premultiplied colour, so the source is added as
            // it is rather than being scaled by its alpha a second time.
            GL11C.glBlendFunc(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);

            GL20C.glUseProgram(translucentCompositeProgram);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glTranslucentTexture);

            org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
            org.lwjgl.opengl.GL11.glVertex2f(-1.0f, -1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(1.0f, -1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(1.0f, 1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(-1.0f, 1.0f);
            org.lwjgl.opengl.GL11.glEnd();

            org.lwjgl.opengl.GL11.glPopAttrib();
            GL20C.glUseProgram(prevProgram);
            GL13C.glActiveTexture(prevActive);
            GL11C.glFlush();
        }
    }

    /** GL side: wait for Vulkan, draw the shared frame into the game's framebuffer, signal back. */
    private void composite() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer noBuffers = stack.mallocInt(0);
            IntBuffer textures = stack.ints(glColorTexture, glDepthTexture);
            IntBuffer layouts = stack.ints(EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT,
                    EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT);
            EXTSemaphore.glWaitSemaphoreEXT(glWaitSemaphore, noBuffers, textures, layouts);

            int prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            int prevActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT
                    | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
                    | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                    | org.lwjgl.opengl.GL11.GL_TEXTURE_BIT
                    | org.lwjgl.opengl.GL11.GL_CURRENT_BIT
                    | org.lwjgl.opengl.GL11.GL_POLYGON_BIT);

            GL11C.glDisable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDepthMask(true);

            if (depthBlit) {
                blitDepth();
            }

            GL20C.glUseProgram(compositePrograms[depthBlit ? 1 : 0]);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glColorTexture);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glDepthTexture);

            if (depthBlit) {
                // Depth already carries the terrain; the quad only paints colour.
                GL11C.glDisable(GL11C.GL_DEPTH_TEST);
                GL11C.glDepthMask(false);
            } else {
                GL11C.glEnable(GL11C.GL_DEPTH_TEST);
                GL11C.glDepthFunc(GL11C.GL_LEQUAL);
            }

            org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
            org.lwjgl.opengl.GL11.glVertex2f(-1.0f, -1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(1.0f, -1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(1.0f, 1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(-1.0f, 1.0f);
            org.lwjgl.opengl.GL11.glEnd();

            // After the terrain is in the frame and before the game draws
            // anything else into it.
            if (bloomStrength > 0.0f && !bloomFailed) {
                bloomPrepare();
            }

            org.lwjgl.opengl.GL11.glPopAttrib();
            GL20C.glUseProgram(prevProgram);
            GL13C.glActiveTexture(prevActive);

            EXTSemaphore.glSignalSemaphoreEXT(glSignalSemaphore, noBuffers, textures, layouts);
            GL11C.glFlush();

            if (!glErrorLogged) {
                int error = GL11C.glGetError();
                if (error != 0) {
                    glErrorLogged = true;
                    LOGGER.error("GL error 0x{} during terrain composite (frame {})",
                            Integer.toHexString(error), frameCounter);
                }
            }
        }
    }

    /**
     * Pull the glow out of the terrain, blur it, add it back.
     *
     * Three fullscreen quads, all of them on targets the composite already had
     * to make. What is glowing does not have to be guessed at from brightness:
     * the terrain shader writes it into the alpha of every opaque pixel, which
     * was carrying the constant 1.0 and nothing else. Guessing would have meant
     * bloom on snow and on sand in sunlight, which are as bright on screen as
     * lava and are not lights.
     *
     * The glow is added with a quad of its own rather than folded into the
     * composite, and that is not tidiness: the composite discards where there
     * is no terrain, so that the sky shows through, and a glow that stopped at
     * the silhouette of a lava lake would be a lake with a hard edge. Added
     * separately and blended, it reaches over the sky the way light does.
     */
    private void bloomPrepare() {
        if (!ensureBloomTargets()) {
            return;
        }
        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        // Pushed rather than read back: the viewport is four numbers and the
        // query for it returns them into a buffer, where this needs none of
        // them — only for the game's to be exactly what it was.
        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_VIEWPORT_BIT);

        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glViewport(0, 0, bloomWidth, bloomHeight);

        // What glows, at half resolution.
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomFbo[0]);
        GL20C.glUseProgram(bloomExtractProgram);
        GL20C.glUniform2f(bloomExtractInvSize, 1.0f / bloomWidth, 1.0f / bloomHeight);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glColorTexture);
        fullscreenQuad();

        // Across, then down. Separable: two passes of n taps instead of one of
        // n squared, and the same answer for a gaussian.
        GL20C.glUseProgram(bloomBlurProgram);
        GL20C.glUniform2f(bloomBlurInvSize, 1.0f / bloomWidth, 1.0f / bloomHeight);
        // Twice, and it ends where it started because each round is two passes.
        // Blurring a blur widens it: what a second round buys is a falloff that
        // fades out instead of ending, which is the difference between light
        // spilling and a bright ring drawn round a block.
        for (int round = 0; round < 3; round++) {
            for (int axis = 0; axis < 2; axis++) {
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomFbo[1 - axis]);
                GL20C.glUniform2f(bloomBlurStep, axis == 0 ? 1.0f : 0.0f, axis == 0 ? 0.0f : 1.0f);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomTexture[axis]);
                fullscreenQuad();
            }
        }

        // The mask, at half resolution, taken here because this is the last
        // moment the Vulkan target may be read.
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomMaskFbo);
        GL11C.glViewport(0, 0, Math.max(1, width / 2), Math.max(1, height / 2));
        GL20C.glUseProgram(bloomMaskProgram);
        GL20C.glUniform2f(bloomMaskInvSize, 2.0f / width, 2.0f / height);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glColorTexture);
        fullscreenQuad();

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
        org.lwjgl.opengl.GL11.glPopAttrib();
        bloomReady = true;
    }

    /**
     * Adds the glow to the frame, once the game has drawn everything into it.
     *
     * Called from the world render, after terrain, entities, particles, weather
     * and water and before the hand — which is the one moment the frame holds
     * the whole scene. Doing it here rather than in the composite is what puts
     * a mob standing in front of lava *in* the glow instead of on top of it,
     * and what lets a torch throw light onto the sky behind it, which it could
     * not before: the sky is not drawn until long after the composite.
     *
     * Nothing owned by Vulkan is read here. The blurred glow and the mask are
     * both ordinary OpenGL textures of ours, filled while the colour target was
     * still ours to read; reading that target at this point would race the next
     * frame, because the semaphore handing it back has already been signalled.
     */
    void applySceneBloom() {
        if (!bloomReady || bloomStrength <= 0.0f || bloomFailed || bloomTexture[0] == 0) {
            return;
        }
        bloomReady = false;
        int prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int prevActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT
                | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
                | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                | org.lwjgl.opengl.GL11.GL_TEXTURE_BIT
                | org.lwjgl.opengl.GL11.GL_CURRENT_BIT
                | org.lwjgl.opengl.GL11.GL_POLYGON_BIT);
        GL11C.glDisable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glEnable(GL11C.GL_BLEND);
        GL11C.glBlendFunc(GL11C.GL_ONE, GL11C.GL_ONE);
        GL20C.glUseProgram(bloomAddProgram);
        GL20C.glUniform2f(bloomAddInvSize, 1.0f / width, 1.0f / height);
        GL20C.glUniform1f(bloomAddStrength, bloomStrength);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomMaskTexture);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomTexture[0]);
        fullscreenQuad();
        org.lwjgl.opengl.GL11.glPopAttrib();
        GL20C.glUseProgram(prevProgram);
        GL13C.glActiveTexture(prevActive);
    }

    private void fullscreenQuad() {
        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        org.lwjgl.opengl.GL11.glVertex2f(-1.0f, -1.0f);
        org.lwjgl.opengl.GL11.glVertex2f(1.0f, -1.0f);
        org.lwjgl.opengl.GL11.glVertex2f(1.0f, 1.0f);
        org.lwjgl.opengl.GL11.glVertex2f(-1.0f, 1.0f);
        org.lwjgl.opengl.GL11.glEnd();
    }

    /** Half-resolution targets and the three programs; built once per size. */
    private boolean ensureBloomTargets() {
        // An eighth of the screen on each axis, and the number was reached by
        // being told twice that the effect could not be seen.
        //
        // What makes a glow read as a glow is how far it reaches, and a blur of
        // a fixed number of taps reaches twice as far across the frame for
        // every halving of the target it runs on — while costing a quarter as
        // much, so reach here is not a trade against speed but the same lever
        // as speed. At half resolution the halo stopped about six pixels out,
        // close enough to the edge of a block to be taken for the block being
        // brighter, which is the one thing it must not be taken for. At a
        // quarter it was a rim drawn round the block: visible, and still not
        // light falling on anything. What has to happen is that the ground
        // beside a lava lake changes colour, and that is tens of pixels.
        //
        // Small sources do not get lost at this size the way they look as
        // though they should. A torch is a texel here, but a blur moves energy
        // rather than discarding it, so what a torch becomes is a wide faint
        // glow — which is what a torch across a room actually looks like.
        int wantWidth = Math.max(1, width / 8);
        int wantHeight = Math.max(1, height / 8);
        if (bloomFbo[0] != 0 && wantWidth == bloomWidth && wantHeight == bloomHeight) {
            return true;
        }
        destroyBloomTargets();
        bloomWidth = wantWidth;
        bloomHeight = wantHeight;
        GL11C.glGetError();
        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        int prevTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        for (int i = 0; i < 2; i++) {
            bloomTexture[i] = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomTexture[i]);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, bloomWidth, bloomHeight,
                    0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            // Linear, and the extract pass leans on it: reading the full-size
            // frame into a half-size target is a box filter for free.
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            // Clamped, so a blur tap off the edge repeats the edge instead of
            // wrapping the glow round to the far side of the screen.
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            bloomFbo[i] = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomFbo[i]);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, bloomTexture[i], 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                LOGGER.error("Bloom framebuffer incomplete; the effect is off for this session");
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
                destroyBloomTargets();
                bloomFailed = true;
                return false;
            }
        }
        int maskWidth = Math.max(1, width / 2);
        int maskHeight = Math.max(1, height / 2);
        bloomMaskTexture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomMaskTexture);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, maskWidth, maskHeight,
                0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        bloomMaskFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomMaskFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, bloomMaskTexture, 0);
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.error("Bloom mask framebuffer incomplete; the effect is off for this session");
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
            destroyBloomTargets();
            bloomFailed = true;
            return false;
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
        try {
            buildBloomPrograms();
        } catch (RuntimeException e) {
            LOGGER.error("Bloom programs failed to build; the effect is off for this session", e);
            destroyBloomTargets();
            bloomFailed = true;
            return false;
        }
        return true;
    }

    private void destroyBloomTargets() {
        for (int i = 0; i < 2; i++) {
            if (bloomFbo[i] != 0) {
                GL30C.glDeleteFramebuffers(bloomFbo[i]);
                bloomFbo[i] = 0;
            }
            if (bloomTexture[i] != 0) {
                GL11C.glDeleteTextures(bloomTexture[i]);
                bloomTexture[i] = 0;
            }
        }
        if (bloomMaskFbo != 0) {
            GL30C.glDeleteFramebuffers(bloomMaskFbo);
            bloomMaskFbo = 0;
        }
        if (bloomMaskTexture != 0) {
            GL11C.glDeleteTextures(bloomMaskTexture);
            bloomMaskTexture = 0;
        }
        bloomWidth = 0;
        bloomHeight = 0;
        bloomReady = false;
    }

    /**
     * Hardware copy of the Vulkan depth buffer into the game's, replacing a
     * gl_FragDepth write in the composite shader. Any driver complaint retires
     * the path for the rest of the session — the shader fallback is correct,
     * just slower.
     */
    private void blitDepth() {
        GL11C.glGetError();
        int prevRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, glDepthBlitFbo);
        GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevRead);
        int error = GL11C.glGetError();
        if (error != 0) {
            depthBlit = false;
            LOGGER.warn("glBlitFramebuffer(depth) rejected with 0x{} — using the gl_FragDepth composite",
                    Integer.toHexString(error));
        }
    }

    /** Copies the middle strip of the color target into the readback buffer. */
    private void recordColorReadback(MemoryStack stack) {
        if (readbackBuffer == 0) {
            return;
        }
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(colorImage);
        barrier.get(0).subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).imageOffset(o -> o.x(0).y(Math.max(0, height / 2 - READBACK_ROWS / 2)).z(0));
        region.get(0).imageExtent(e -> e.width(width).height(READBACK_ROWS).depth(1));
        region.get(0).imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        vkCmdCopyImageToBuffer(commandBuffer, colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                readbackBuffer, region);

        barrier.get(0)
                .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier);
        readbackRecorded = true;
    }

    /**
     * Proof-by-log for the first frames: how much geometry was submitted and
     * how much of the shared color image actually contains pixels when GL
     * reads it back. Separates "Vulkan drew nothing" from "composite failed".
     */
    private void logFrameDiagnostics() {
        // The GL half of this stops the world with glFinish and pulls the whole
        // colour attachment back over the bus, which at 4K is around thirty
        // megabytes. Twice a session is not much, but it lands in the seconds
        // where the world is being built, and it is answering a question that
        // was answered a long time ago.
        if (!STARTUP_READBACK || (frameCounter != 1 && frameCounter != 120)) {
            return;
        }
        double vkCoverage = -1.0;
        if (readbackRecorded) {
            readbackRecorded = false;
            try {
                check(vkWaitForFences(device(), fence, true, 1_000_000_000L), "vkWaitForFences(readback)");
                int samples = width * READBACK_ROWS;
                int covered = 0;
                for (int i = 0; i < samples; i++) {
                    if (MemoryUtil.memGetByte(readbackMapped + i * 4L + 3) != 0) {
                        covered++;
                    }
                }
                vkCoverage = 100.0 * covered / samples;
            } catch (Throwable t) {
                LOGGER.warn("VK-side readback failed", t);
            }
        }
        double coverage = -1.0;
        try {
            GL11C.glFinish(); // make sure the Vulkan frame + semaphore handoff completed
            int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glColorTexture);
            ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
            try {
                GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
                long covered = 0;
                for (int i = 3; i < pixels.capacity(); i += 4 * 16) { // sample every 16th pixel
                    if (pixels.get(i) != 0) {
                        covered++;
                    }
                }
                coverage = 100.0 * covered / (width * (long) height / 16);
            } finally {
                MemoryUtil.memFree(pixels);
            }
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
        } catch (Throwable t) {
            LOGGER.warn("Coverage readback failed", t);
        }
        LOGGER.info("Terrain frame {}: {} chunks, {} vertices, {} skipped (no mirror); "
                        + "VK-side coverage {}% (middle strip), GL-side coverage {}%",
                frameCounter, frameChunks, frameVertices, frameSkipped,
                vkCoverage < 0 ? "?" : String.format("%.1f", vkCoverage),
                coverage < 0 ? "?" : String.format("%.1f", coverage));
    }

    // ------------------------------------------------------------------
    // Lightmap (16x16, refreshed every frame from the GL texture)
    // ------------------------------------------------------------------

    /** ARGB ints → RGBA8 staging bytes; no GL involvement, no pipeline stall. */
    /** Order-sensitive so a swap of two texels still counts as a change. */
    private static int hashLightmap(int[] argb) {
        int hash = 1;
        for (int i = 0; i < argb.length; i++) {
            hash = hash * 31 + argb[i];
        }
        return hash;
    }

    private void writeLightmapStaging(int[] argb) {
        for (int i = 0; i < argb.length; i++) {
            int v = argb[i];
            long p = lightmapStagingMapped[activeFrameSlot] + i * 4L;
            MemoryUtil.memPutByte(p, (byte) (v >> 16));
            MemoryUtil.memPutByte(p + 1, (byte) (v >> 8));
            MemoryUtil.memPutByte(p + 2, (byte) v);
            MemoryUtil.memPutByte(p + 3, (byte) (v >>> 24));
        }
    }

    private void readLightmapFromGL() {
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lightmapGlId);
        lightmapReadBuffer.clear();
        GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, lightmapReadBuffer);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
        MemoryUtil.memCopy(MemoryUtil.memAddress(lightmapReadBuffer), lightmapStagingMapped[activeFrameSlot],
                LIGHTMAP_SIZE * LIGHTMAP_SIZE * 4);
    }

    private void recordLightmapUpload(MemoryStack stack) {
        VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(1, stack);
        toTransfer.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(lightmapImageInitialized ? VK_ACCESS_SHADER_READ_BIT : 0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(lightmapImageInitialized ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                        : VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(lightmapImage);
        toTransfer.get(0).subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer,
                lightmapImageInitialized ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).imageExtent(e -> e.width(LIGHTMAP_SIZE).height(LIGHTMAP_SIZE).depth(1));
        region.get(0).imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        vkCmdCopyBufferToImage(commandBuffer, lightmapStagingBuffer[activeFrameSlot], lightmapImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

        VkImageMemoryBarrier.Buffer toShader = VkImageMemoryBarrier.calloc(1, stack);
        toShader.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(lightmapImage);
        toShader.get(0).subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, toShader);
        lightmapImageInitialized = true;
    }

    // ------------------------------------------------------------------
    // Resource creation
    // ------------------------------------------------------------------

    private void ensureBaseResources() {
        if (baseReady) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(ctx.getGraphicsQueueFamily());
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(device(), poolInfo, null, pPool), "vkCreateCommandPool(terrain)");
            commandPool = pPool.get(0);
            createQueryPool(stack);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(framesInFlight);
            PointerBuffer pCmd = stack.mallocPointer(framesInFlight);
            check(vkAllocateCommandBuffers(device(), allocInfo, pCmd), "vkAllocateCommandBuffers(terrain)");
            commandBuffers = new VkCommandBuffer[framesInFlight];
            fences = new long[framesInFlight];
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer pFence = stack.mallocLong(1);
            for (int i = 0; i < framesInFlight; i++) {
                commandBuffers[i] = new VkCommandBuffer(pCmd.get(i), device());
                check(vkCreateFence(device(), fenceInfo, null, pFence), "vkCreateFence(terrain)");
                fences[i] = pFence.get(0);
            }
            commandBuffer = commandBuffers[0];
            fence = fences[0];

            // The translucent pass is a submission of its own, so it needs a
            // command buffer of its own per frame in flight — the opaque one is
            // already submitted and cannot be added to.
            PointerBuffer pTranslucentCmd = stack.mallocPointer(framesInFlight);
            check(vkAllocateCommandBuffers(device(), allocInfo, pTranslucentCmd),
                    "vkAllocateCommandBuffers(translucent)");
            translucentCommandBuffers = new VkCommandBuffer[framesInFlight];
            translucentFences = new long[framesInFlight];
            for (int i = 0; i < framesInFlight; i++) {
                translucentCommandBuffers[i] = new VkCommandBuffer(pTranslucentCmd.get(i), device());
                check(vkCreateFence(device(), fenceInfo, null, pFence), "vkCreateFence(translucent)");
                translucentFences[i] = pFence.get(0);
            }

            VkExportSemaphoreCreateInfo export = VkExportSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO)
                    .handleTypes(Interop.SEMAPHORE_HANDLE_TYPE);
            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                    .pNext(export.address());
            LongBuffer pSem = stack.mallocLong(1);
            check(vkCreateSemaphore(device(), semInfo, null, pSem), "vkCreateSemaphore");
            vkSignalSemaphore = pSem.get(0);
            check(vkCreateSemaphore(device(), semInfo, null, pSem), "vkCreateSemaphore");
            vkWaitSemaphore = pSem.get(0);
            glWaitSemaphore = importSemaphore(stack, vkSignalSemaphore);
            glSignalSemaphore = importSemaphore(stack, vkWaitSemaphore);
            check(vkCreateSemaphore(device(), semInfo, null, pSem), "vkCreateSemaphore(translucent)");
            vkTranslucentSignalSemaphore = pSem.get(0);
            check(vkCreateSemaphore(device(), semInfo, null, pSem), "vkCreateSemaphore(translucent)");
            vkTranslucentWaitSemaphore = pSem.get(0);
            glTranslucentWaitSemaphore = importSemaphore(stack, vkTranslucentSignalSemaphore);
            glTranslucentSignalSemaphore = importSemaphore(stack, vkTranslucentWaitSemaphore);

            createDescriptorInfrastructure(stack);
            createDrawBatches(stack);
            createRenderPass(stack);
            pipelineCacheHandle = pipelineCache.create(
                    device(), System.getProperty("vulkanmod112.pipelineCache"));
            createPipeline(stack);
            createCompositeProgram();
        }
        updateDescriptors();
        baseReady = true;
        LOGGER.info("Terrain renderer base resources ready");
    }

    private void createDescriptorInfrastructure(MemoryStack stack) {
        VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_NEAREST)
                .minFilter(VK_FILTER_NEAREST)
                // Nearest inside a level keeps the pixel-art look; linear
                // between levels kills the shimmer on distant chunks. maxLod
                // is clamped by the image's actual level count.
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                .maxLod(VK_LOD_CLAMP_NONE)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        LongBuffer pSampler = stack.mallocLong(1);
        check(vkCreateSampler(device(), samplerInfo, null, pSampler), "vkCreateSampler(atlas)");
        atlasSampler = pSampler.get(0);

        samplerInfo.magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).maxLod(0.0f);
        check(vkCreateSampler(device(), samplerInfo, null, pSampler), "vkCreateSampler(lightmap)");
        lightmapSampler = pSampler.get(0);

        createFrameUniforms(stack);

        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
        bindings.get(0).binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        bindings.get(1).binding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        bindings.get(2).binding(2)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
        // The frame's matrix is wanted in the vertex stage and its fog in the
        // fragment stage, so this one is visible to both.
        bindings.get(3).binding(3)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);
        LongBuffer pLayout = stack.mallocLong(1);
        check(vkCreateDescriptorSetLayout(device(), layoutInfo, null, pLayout), "vkCreateDescriptorSetLayout");
        descriptorSetLayout = pLayout.get(0);

        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
        poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(drawDescriptorSets.length * 2);
        poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(drawDescriptorSets.length);
        poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(drawDescriptorSets.length);
        VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .pPoolSizes(poolSizes)
                .maxSets(drawDescriptorSets.length);
        LongBuffer pPool = stack.mallocLong(1);
        check(vkCreateDescriptorPool(device(), poolInfo, null, pPool), "vkCreateDescriptorPool");
        descriptorPool = pPool.get(0);

        VkDescriptorSetAllocateInfo setInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descriptorPool)
                .pSetLayouts(stack.mallocLong(drawDescriptorSets.length));
        for (int i = 0; i < drawDescriptorSets.length; i++) {
            setInfo.pSetLayouts().put(i, descriptorSetLayout);
        }
        LongBuffer pSet = stack.mallocLong(drawDescriptorSets.length);
        check(vkAllocateDescriptorSets(device(), setInfo, pSet), "vkAllocateDescriptorSets");
        for (int i = 0; i < drawDescriptorSets.length; i++) {
            drawDescriptorSets[i] = pSet.get(i);
        }
        descriptorSet = drawDescriptorSets[0];
    }

    /**
     * Allocates the per-frame, per-layer indirect batches.
     *
     * The GPU reads both halves of every batch on each frame: the vertex shader
     * fetches a chunk origin per draw, and the command processor fetches the
     * draw commands themselves. Left in ordinary host memory, that is thousands
     * of small reads across PCIe every frame, and it competes with chunk
     * geometry streaming over the same bus.
     *
     * So we ask for memory that is device-local *and* host-visible — the BAR
     * window, present on any card with resizable BAR and on integrated GPUs by
     * definition — and fall back to plain host-visible memory where the driver
     * does not expose such a type. Both are written the same way, so the
     * fallback costs nothing but the bandwidth it was going to cost anyway.
     *
     * What this memory is bad at is being read back: it is uncached on the CPU
     * side. Nothing on the frame path reads it; {@code logDrawInputs} does, on
     * two frames of a session, and that is why it stays a diagnostic.
     */
    private void createDrawBatches(MemoryStack stack) {
        for (int i = 0; i < drawBatchBuffers.length; i++) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(drawBatchBytes)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device(), info, null, pBuffer), "vkCreateBuffer(indirect terrain)");
            drawBatchBuffers[i] = pBuffer.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), drawBatchBuffers[i], req);
            int hostVisible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            int barType = findMemoryTypeOrNone(stack, req.memoryTypeBits(),
                    hostVisible | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            LongBuffer pMemory = stack.mallocLong(1);
            boolean deviceLocal = false;
            if (barType >= 0 && (i == 0 || indirectMemoryIsDeviceLocal)) {
                VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(req.size())
                        .memoryTypeIndex(barType);
                // Without resizable BAR this window is 256 MiB for the whole
                // system, so a driver saying no here is an ordinary outcome and
                // not an error. Host memory still works; it is only slower.
                deviceLocal = vkAllocateMemory(device(), alloc, null, pMemory) == VK_SUCCESS;
                if (!deviceLocal && i == 0) {
                    LOGGER.info("Indirect batches did not fit in BAR memory; using host memory");
                }
            }
            if (!deviceLocal) {
                VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(req.size())
                        .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), hostVisible));
                check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(indirect terrain)");
            }
            // One batch falling back means the window is full; report the whole
            // set as host memory and stop asking for the rest.
            indirectMemoryIsDeviceLocal = deviceLocal && (i == 0 || indirectMemoryIsDeviceLocal);
            drawBatchMemories[i] = pMemory.get(0);
            check(vkBindBufferMemory(device(), drawBatchBuffers[i], drawBatchMemories[i], 0),
                    "vkBindBufferMemory(indirect terrain)");
            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(device(), drawBatchMemories[i], 0, drawBatchBytes, 0, mapped),
                    "vkMapMemory(indirect terrain)");
            drawBatchMapped[i] = mapped.get(0);
        }
    }

    private void destroyDrawBatches() {
        for (int i = 0; i < drawBatchBuffers.length; i++) {
            if (drawBatchMemories[i] != 0) {
                vkUnmapMemory(device(), drawBatchMemories[i]);
                vkDestroyBuffer(device(), drawBatchBuffers[i], null);
                vkFreeMemory(device(), drawBatchMemories[i], null);
                drawBatchBuffers[i] = 0;
                drawBatchMemories[i] = 0;
                drawBatchMapped[i] = 0;
            }
        }
    }

    /**
     * Grows the indirect batch so a layer of {@code draws} chunks fits.
     *
     * Called before the frame's command buffer is opened, because the old
     * buffers may still be read by a frame in flight and the descriptor sets
     * that point at them have to be rewritten.
     */
    private void ensureDrawBatchCapacity(int draws) {
        if (draws <= indirectDrawCapacity || indirectDrawCapacity >= MAX_INDIRECT_DRAWS) {
            return;
        }
        // Headroom, because the visible-chunk count moves with every step.
        int target = Math.min(MAX_INDIRECT_DRAWS, Integer.highestOneBit(draws) * 2);
        if (target <= indirectDrawCapacity) {
            return;
        }
        vkDeviceWaitIdle(device());
        destroyDrawBatches();
        indirectDrawCapacity = target;
        drawCommandOffset = (long) target * DRAW_ORIGIN_BYTES;
        drawBatchBytes = drawCommandOffset + (long) target * DRAW_COMMAND_BYTES;
        try (MemoryStack stack = stackPush()) {
            createDrawBatches(stack);
            // Only binding 2 moved. Rewriting it directly also keeps this
            // independent of whether the atlas and lightmap are ready, which
            // updateDescriptors() requires and would otherwise skip — leaving
            // the sets pointing at buffers that were just destroyed.
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(drawDescriptorSets.length, stack);
            for (int i = 0; i < drawDescriptorSets.length; i++) {
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.get(0).buffer(drawBatchBuffers[i]).offset(0).range(drawCommandOffset);
                writes.get(i)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(drawDescriptorSets[i]).dstBinding(2).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bufferInfo);
            }
            vkUpdateDescriptorSets(device(), writes, null);
        }
        LOGGER.info("Indirect draw batches grown to {} draws ({} MiB across {} batches, {} memory)",
                target, String.format("%.1f", drawBatchBytes * drawBatchBuffers.length / (1024.0 * 1024.0)),
                drawBatchBuffers.length, indirectMemoryIsDeviceLocal ? "BAR" : "host");
    }

    /**
     * One permanently mapped uniform buffer per frame in flight.
     *
     * Host-visible and coherent rather than device-local: the contents are
     * rewritten by the CPU every frame and read once by the GPU, which is the
     * case staging would only add a copy to.
     */
    private void createFrameUniforms(MemoryStack stack) {
        VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(FRAME_UNIFORM_BYTES)
                .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
        LongBuffer pBuffer = stack.mallocLong(1);
        LongBuffer pMemory = stack.mallocLong(1);
        PointerBuffer ppData = stack.mallocPointer(1);
        for (int i = 0; i < framesInFlight; i++) {
            check(vkCreateBuffer(device(), bufferInfo, null, pBuffer), "vkCreateBuffer(frame uniforms)");
            frameUniformBuffers[i] = pBuffer.get(0);
            vkGetBufferMemoryRequirements(device(), frameUniformBuffers[i], req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(frame uniforms)");
            frameUniformMemories[i] = pMemory.get(0);
            check(vkBindBufferMemory(device(), frameUniformBuffers[i], frameUniformMemories[i], 0),
                    "vkBindBufferMemory(frame uniforms)");
            check(vkMapMemory(device(), frameUniformMemories[i], 0, FRAME_UNIFORM_BYTES, 0, ppData),
                    "vkMapMemory(frame uniforms)");
            frameUniformMapped[i] = ppData.get(0);
        }
    }

    /**
     * Writes this frame's matrix and fog where the shaders read them.
     *
     * {@code fogState} is copied straight through, and its eight floats land on
     * the two vec4s the shader declares: colour rgb, then the mode in the
     * alpha, then start, end and density. That is the same order the push
     * constants carried, so the shader reads the same bytes from a different
     * place.
     */
    private void writeFrameUniforms(float[] mvp, float[] fogState) {
        long base = frameUniformMapped[activeFrameSlot];
        if (base == 0L) {
            return;
        }
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(base + i * 4L, mvp[i]);
        }
        for (int i = 0; i < 8; i++) {
            MemoryUtil.memPutFloat(base + 64 + i * 4L, fogState[i]);
        }
        // vec4 lightInfo at 96: x is how many of the array below to read.
        MemoryUtil.memPutFloat(base + 96, dynamicLightCount);
        MemoryUtil.memPutFloat(base + 100, 0.0f);
        MemoryUtil.memPutFloat(base + 104, 0.0f);
        MemoryUtil.memPutFloat(base + 108, 0.0f);
        for (int i = 0; i < dynamicLightCount * 4; i++) {
            MemoryUtil.memPutFloat(base + 112 + i * 4L, dynamicLights[i]);
        }
        // vec4 frameInfo at 624, straight after lights[32].
        //
        // The clock is read here rather than sent across the bridge. What
        // anything animated needs is a value that advances smoothly and never
        // jumps, and System.nanoTime is that on this side already; routing it
        // through the game would add a bridge call per frame and tie the
        // animation to the game thread for nothing. Reduced modulo an hour so
        // the float keeps its precision however long the session runs.
        float seconds = (float) (((System.nanoTime() - startedNanos) / 1_000_000L) % 3_600_000L)
                / 1000.0f;
        MemoryUtil.memPutFloat(base + 624, seconds);
        MemoryUtil.memPutFloat(base + 628, directionalDynamicLight);
        // z: whether the material buffer is bound at all. When it is not, the
        // second vertex binding is the geometry buffer read at stride one, and
        // what it hands back is meaningless.
        MemoryUtil.memPutFloat(base + 632, materialsBound ? 1.0f : 0.0f);
        MemoryUtil.memPutFloat(base + 636, showMaterials ? 1.0f : 0.0f);
        // vec4 heightFog at 640.
        MemoryUtil.memPutFloat(base + 640, heightFogStrength);
        MemoryUtil.memPutFloat(base + 644, heightFogFalloff);
        // z: how many of the sprite rectangles below are in use.
        MemoryUtil.memPutFloat(base + 648, materialSpriteCount);
        // w: how much of a water surface turns into sky at a grazing angle.
        MemoryUtil.memPutFloat(base + 652, waterReflection);
        // The sprite table at 656: two vec4s each, rectangle then material.
        for (int i = 0; i < materialSpriteCount * 8; i++) {
            MemoryUtil.memPutFloat(base + 656 + i * 4L, materialSprites[i]);
        }
        // vec4 water at 912, straight after the sprite table's sixteen vec4s.
        MemoryUtil.memPutFloat(base + 912, waterWaves);
        // yz: where the camera is on the wave lattice. The reduction happens
        // here, in double precision, because that is the only place it can:
        // world coordinates in this game reach tens of millions, and a float
        // stops being able to separate one block from the next long before
        // that. What crosses into the shader is a remainder under sixteen.
        MemoryUtil.memPutFloat(base + 916, (float) waveWrap(viewWorldX));
        MemoryUtil.memPutFloat(base + 920, (float) waveWrap(viewWorldZ));
        // w: how far a plant leans away from where the game put it.
        MemoryUtil.memPutFloat(base + 924, foliageSway);
    }

    /** The wave lattice from terrain.frag, which this side has to agree with. */
    private static final double WAVE_LATTICE = 16.0;

    private static double waveWrap(double world) {
        return world - Math.floor(world / WAVE_LATTICE) * WAVE_LATTICE;
    }

    /**
     * Shader settings read once a frame from the properties the game side sets.
     *
     * Both are plain numbers in the frame's uniform buffer rather than
     * specialization constants, which is the whole point: a slider that
     * rebuilds a pipeline is a slider that stutters, and neither of these
     * changes what the shader costs enough to be worth a second variant of it.
     */
    private void refreshShaderSettings() {
        directionalDynamicLight =
                clampPercent(intProperty("vulkanmod112.directionalLight", 100));
        heightFogStrength = clampPercent(intProperty("vulkanmod112.heightFog", 0));
        // The setting is a depth in blocks; the shader wants a rate per block.
        // Two e-foldings over that depth, so the drop the slider names is where
        // the fog has taken about six sevenths of what its strength allows —
        // near enough to "this is where it is as thick as it gets" to set by
        // eye, which is how the slider is going to be used.
        int depth = Math.max(1, intProperty("vulkanmod112.heightFogDepth", 24));
        heightFogFalloff = 2.0f / depth;
        showMaterials = "true".equals(System.getProperty("vulkanmod112.showMaterials"));
        waterReflection = clampPercent(intProperty("vulkanmod112.waterReflection", 0));
        waterWaves = clampPercent(intProperty("vulkanmod112.waterWaves", 0));
        foliageSway = clampPercent(intProperty("vulkanmod112.foliageSway", 0));
        bloomStrength = clampPercent(intProperty("vulkanmod112.bloom", 0));
    }

    private static float clampPercent(int value) {
        return Math.max(0, Math.min(100, value)) / 100.0f;
    }

    private static int intProperty(String name, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * The light sources for the coming frame. Copied rather than referenced:
     * the array on the other side of the bridge belongs to the game thread and
     * is refilled every frame.
     */
    void setDynamicLights(float[] lights, int count) {
        int clamped = Math.max(0, Math.min(count, MAX_DYNAMIC_LIGHTS));
        System.arraycopy(lights, 0, dynamicLights, 0, clamped * 4);
        dynamicLightCount = clamped;
    }

    private void updateDescriptors() {
        if (descriptorSet == 0 || atlasImage == 0 || lightmapImage == 0) {
            return;
        }
        vkDeviceWaitIdle(device());
        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer atlasInfo = VkDescriptorImageInfo.calloc(1, stack);
            atlasInfo.get(0)
                    .sampler(atlasSampler)
                    .imageView(atlasView)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkDescriptorImageInfo.Buffer lightmapInfo = VkDescriptorImageInfo.calloc(1, stack);
            lightmapInfo.get(0)
                    .sampler(lightmapSampler)
                    .imageView(lightmapView)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(drawDescriptorSets.length * 4, stack);
            for (int i = 0; i < drawDescriptorSets.length; i++) {
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.get(0).buffer(drawBatchBuffers[i]).offset(0).range(drawCommandOffset);
                // Three sets per frame in flight, one per layer, and they all
                // read the same frame constants.
                VkDescriptorBufferInfo.Buffer frameInfo = VkDescriptorBufferInfo.calloc(1, stack);
                frameInfo.get(0).buffer(frameUniformBuffers[i / BATCHES_PER_FRAME]).offset(0).range(FRAME_UNIFORM_BYTES);
                int write = i * 4;
                writes.get(write)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(drawDescriptorSets[i]).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(atlasInfo);
                writes.get(write + 1)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(drawDescriptorSets[i]).dstBinding(1).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(lightmapInfo);
                writes.get(write + 2)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(drawDescriptorSets[i]).dstBinding(2).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bufferInfo);
                writes.get(write + 3)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(drawDescriptorSets[i]).dstBinding(3).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(frameInfo);
            }
            vkUpdateDescriptorSets(device(), writes, null);
        }
    }

    private void createRenderPass(MemoryStack stack) {
        VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(2, stack);
        attachments.get(0)
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        attachments.get(1)
                .format(depthFormat(stack))
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
        colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

        VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
        subpass.get(0)
                .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                .colorAttachmentCount(1)
                .pColorAttachments(colorRef)
                .pDepthStencilAttachment(depthRef);

        VkRenderPassCreateInfo rpInfo = VkRenderPassCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                .pAttachments(attachments)
                .pSubpasses(subpass);
        LongBuffer pRenderPass = stack.mallocLong(1);
        check(vkCreateRenderPass(device(), rpInfo, null, pRenderPass), "vkCreateRenderPass(terrain)");
        renderPass = pRenderPass.get(0);

        createTranslucentRenderPass(stack);
    }

    /**
     * The pass the translucent layer is drawn in.
     *
     * Two things separate it from the opaque one. Its colour attachment starts
     * cleared to fully transparent, because what it produces is composited over
     * a frame OpenGL has meanwhile drawn entities into rather than replacing
     * it. And its depth attachment is loaded rather than cleared, and never
     * stored: the layer is depth-tested against what is already there and
     * writes nothing back, which is what vanilla does too — {@code
     * depthMask(false)} right before it asks for the layer.
     */
    private void createTranslucentRenderPass(MemoryStack stack) {
        VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(2, stack);
        attachments.get(0)
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        attachments.get(1)
                .format(depthFormat(stack))
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                // What the opaque pass left it in, and what it is handed back as
                // so the GL side can go on sampling it.
                .initialLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
        colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        // Read-only depth: the test runs, nothing is written.
        VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);

        VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
        subpass.get(0)
                .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                .colorAttachmentCount(1)
                .pColorAttachments(colorRef)
                .pDepthStencilAttachment(depthRef);

        VkRenderPassCreateInfo rpInfo = VkRenderPassCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                .pAttachments(attachments)
                .pSubpasses(subpass);
        LongBuffer pRenderPass = stack.mallocLong(1);
        check(vkCreateRenderPass(device(), rpInfo, null, pRenderPass),
                "vkCreateRenderPass(translucent)");
        translucentRenderPass = pRenderPass.get(0);
    }

    /**
     * Which pipeline draws a vanilla render layer, or -1 if this renderer does
     * not draw that layer at all.
     */
    private static int pipelineForLayer(int layerOrdinal) {
        if (layerOrdinal < 0 || layerOrdinal >= LAYER_PIPELINE.length) {
            return -1;
        }
        int variant = LAYER_PIPELINE[layerOrdinal];
        return variant < TERRAIN_PIPELINES.length ? variant : -1;
    }

    private void createPipeline(MemoryStack stack) {
        long vertModule = createShaderModule(stack, "vulkanmod112/shaders/terrain.vert.spv");
        long fragModule = createShaderModule(stack, "vulkanmod112/shaders/terrain.frag.spv");

        ByteBuffer entryPoint = stack.UTF8("main");

        // Vanilla BLOCK vertex format: pos 3f | color 4ub | uv 2f | lightmap 2s = 28 bytes
        VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(2, stack);
        binding.get(0).binding(0).stride(BLOCK_VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        // One byte per vertex, in a buffer of its own. The game's vertex is 28
        // bytes and is mirrored unchanged, so a fifth attribute cannot live in
        // it; a second binding costs nothing here because the indirect draw's
        // vertexOffset applies to every bound buffer, so the same per-chunk
        // number already lands on the right materials.
        binding.get(1).binding(1).stride(1).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(5, stack);
        attrs.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
        attrs.get(1).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(12);
        attrs.get(2).location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(16);
        attrs.get(3).location(3).binding(0).format(VK_FORMAT_R16G16_SSCALED).offset(24);
        attrs.get(4).location(4).binding(1).format(VK_FORMAT_R8_UINT).offset(0);
        VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(binding)
                .pVertexAttributeDescriptions(attrs);

        VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

        VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                .viewportCount(1)
                .scissorCount(1);

        // Vanilla renders terrain with backface culling (GL front = CCW). Our
        // image is vertically flipped relative to GL, which mirrors winding:
        // front faces arrive clockwise. -Dvulkanmod112.cull=false to disable.
        boolean cull = !"false".equals(System.getProperty("vulkanmod112.cull"));
        VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .polygonMode(VK_POLYGON_MODE_FILL)
                .cullMode(cull ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
                .frontFace(VK_FRONT_FACE_CLOCKWISE)
                .lineWidth(1.0f);
        VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

        // Depth and blend state are per pipeline, so they are built inside the
        // loop below rather than shared the way the rest of the state is.

        VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

        VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
        pushRange.get(0)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .offset(0)
                // One vec4 of per-draw parameters, x = alpha cutoff. Everything
                // that is the same for a whole frame moved into a uniform
                // buffer; this used to be 112 of the 128 bytes Vulkan
                // guarantees, which left nothing to grow into.
                .size(16);
        VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(descriptorSetLayout))
                .pPushConstantRanges(pushRange);
        LongBuffer pLayout = stack.mallocLong(1);
        check(vkCreatePipelineLayout(device(), layoutInfo, null, pLayout), "vkCreatePipelineLayout(terrain)");
        pipelineLayout = pLayout.get(0);

        // ALPHA_TEST (constant_id 0) off for SOLID, on for the CUTOUT layers.
        // Booleans travel as a 32-bit value, like VkBool32.
        VkSpecializationMapEntry.Buffer specEntry = VkSpecializationMapEntry.calloc(2, stack);
        specEntry.get(0).constantID(0).offset(0).size(4);
        specEntry.get(1).constantID(1).offset(4).size(4);

        VkGraphicsPipelineCreateInfo.Buffer pipelineInfo =
                VkGraphicsPipelineCreateInfo.calloc(TERRAIN_PIPELINES.length, stack);
        for (int variant = 0; variant < TERRAIN_PIPELINES.length; variant++) {
            TerrainPipeline spec = TERRAIN_PIPELINES[variant];
            VkSpecializationInfo specInfo = VkSpecializationInfo.calloc(stack)
                    .pMapEntries(specEntry)
                    .pData(stack.bytes(
                            (byte) (spec.alphaTest ? 1 : 0), (byte) 0, (byte) 0, (byte) 0,
                            (byte) (spec.blend ? 1 : 0), (byte) 0, (byte) 0, (byte) 0));

            VkPipelineDepthStencilStateCreateInfo depthState =
                    VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                            .depthTestEnable(true)
                            .depthWriteEnable(spec.depthWrite)
                            .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttachment.get(0)
                    .blendEnable(spec.blend)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            if (spec.blend) {
                // Premultiplied "over", not the SRC_ALPHA form the game uses.
                //
                // Vanilla blends water straight onto its finished frame, once.
                // Here it happens twice — into a target of its own, and then
                // compositing that target over the frame — and "over" only
                // survives being split like that if the colour carries its
                // coverage. With straight alpha, two overlapping water surfaces
                // would each be scaled by their alpha again at composite time
                // and the overlap would come out too dark. The shader writes
                // colour already multiplied by alpha; see BLEND in terrain.frag.
                blendAttachment.get(0)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK_BLEND_OP_ADD);
            }
            VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .pAttachments(blendAttachment);

            VkPipelineShaderStageCreateInfo.Buffer variantStages =
                    VkPipelineShaderStageCreateInfo.calloc(2, stack);
            variantStages.get(0)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(entryPoint);
            variantStages.get(1)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(entryPoint)
                    .pSpecializationInfo(specInfo);
            pipelineInfo.get(variant)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(variantStages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(raster)
                    .pMultisampleState(multisample)
                    .pDepthStencilState(depthState)
                    .pColorBlendState(blend)
                    .pDynamicState(dynamic)
                    .layout(pipelineLayout)
                    // A pipeline belongs to the pass it is used in, and the
                    // blended one is drawn in the translucent pass because that
                    // is the pass whose depth is loaded rather than cleared.
                    .renderPass(spec.blend ? translucentRenderPass : renderPass)
                    .subpass(0);
        }
        LongBuffer pPipeline = stack.mallocLong(TERRAIN_PIPELINES.length);
        check(vkCreateGraphicsPipelines(device(), pipelineCacheHandle, pipelineInfo, null,
                pPipeline), "vkCreateGraphicsPipelines(terrain)");
        for (int variant = 0; variant < TERRAIN_PIPELINES.length; variant++) {
            pipelines[variant] = pPipeline.get(variant);
        }

        vkDestroyShaderModule(device(), vertModule, null);
        vkDestroyShaderModule(device(), fragModule, null);
    }

    private void createCompositeProgram() {
        compositePrograms[0] = buildCompositeProgram(true);
        compositePrograms[1] = buildCompositeProgram(false);
        translucentCompositeProgram = buildTranslucentCompositeProgram();
    }

    /**
     * Composite for the translucent target, which differs in one thing that
     * matters: it keeps the alpha it sampled instead of forcing it to 1. The
     * opaque composite replaces what is underneath, this one is blended over
     * it, and the proportion is carried in that channel.
     */
    private int buildTranslucentCompositeProgram() {
        String vertSrc = "#version 120\n"
                + "void main() { gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0); }\n";
        String fragSrc = "#version 120\n"
                + "uniform sampler2D uColor;\n"
                + "uniform vec2 uInvSize;\n"
                + "void main() {\n"
                + "    vec4 c = texture2D(uColor, gl_FragCoord.xy * uInvSize);\n"
                + "    if (c.a < 0.004) discard;\n"
                + "    gl_FragColor = c;\n"
                + "}\n";
        int vert = compileGlShader(GL20C.GL_VERTEX_SHADER, vertSrc);
        int frag = compileGlShader(GL20C.GL_FRAGMENT_SHADER, fragSrc);
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vert);
        GL20C.glAttachShader(program, frag);
        GL20C.glLinkProgram(program);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Translucent composite link failed: "
                    + GL20C.glGetProgramInfoLog(program));
        }
        GL20C.glDeleteShader(vert);
        GL20C.glDeleteShader(frag);
        int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        GL20C.glUseProgram(program);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uColor"), 0);
        translucentInvSizeUniform = GL20C.glGetUniformLocation(program, "uInvSize");
        GL20C.glUseProgram(prev);
        return program;
    }

    /**
     * @param writeDepth export the Vulkan depth per fragment; false when the
     *                   depth buffer is filled by glBlitFramebuffer instead
     */
    private void buildBloomPrograms() {
        if (bloomExtractProgram != 0) {
            return;
        }
        // What is glowing, at half resolution. The test is not "is this pixel
        // bright" — snow and sand in sunlight are as bright on screen as lava
        // and are not lights. It is what the terrain shader wrote into the
        // alpha it was spending on the constant 1.0.
        bloomExtractProgram = buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "void main() {\n"
                        + "    vec4 c = texture2D(uSource, gl_FragCoord.xy * uInvSize);\n"
                        + "    float emissive = clamp((c.a - 0.5) * 2.0, 0.0, 1.0);\n"
                        + "    gl_FragColor = vec4(c.rgb * emissive, 1.0);\n"
                        + "}\n");
        bloomExtractInvSize = GL20C.glGetUniformLocation(bloomExtractProgram, "uInvSize");

        // One axis per pass. A gaussian is separable, so two passes of five
        // taps do what one of twenty-five would, and the offsets sit between
        // texels on purpose: linear filtering makes each of those one read
        // where the weights say two.
        bloomBlurProgram = buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "uniform vec2 uStep;\n"
                        + "void main() {\n"
                        + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                        + "    vec2 d = uStep * uInvSize;\n"
                        + "    vec3 sum = texture2D(uSource, uv).rgb * 0.227027;\n"
                        + "    sum += (texture2D(uSource, uv + d * 1.3846154).rgb\n"
                        + "          + texture2D(uSource, uv - d * 1.3846154).rgb) * 0.3162162;\n"
                        + "    sum += (texture2D(uSource, uv + d * 3.2307692).rgb\n"
                        + "          + texture2D(uSource, uv - d * 3.2307692).rgb) * 0.0702703;\n"
                        + "    gl_FragColor = vec4(sum, 1.0);\n"
                        + "}\n");
        bloomBlurInvSize = GL20C.glGetUniformLocation(bloomBlurProgram, "uInvSize");
        bloomBlurStep = GL20C.glGetUniformLocation(bloomBlurProgram, "uStep");

        // Added, not laid over: the alpha is zero so a blend of one and one
        // leaves the frame's own alpha alone.
        bloomAddProgram = buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform sampler2D uScene;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "uniform float uStrength;\n"
                        + "void main() {\n"
                        + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                        + "    vec3 glow = texture2D(uSource, uv).rgb;\n"
                        // Held back on the surfaces producing it, and this is
                        // not taste. Adding light to a pixel that is already
                        // near the top of an eight-bit channel does not make it
                        // brighter, it makes it flat: the frame has no headroom
                        // anywhere, so the only thing the addition can spend is
                        // the texture's own detail. Lava came out as a sheet of
                        // orange with its pattern gone. What a glow is, is the
                        // light that landed somewhere else, so that is what is
                        // added — the source keeps the look it earned.
                        + "    float self = texture2D(uScene, uv).r;\n"
                        + "    gl_FragColor = vec4(glow * uStrength * 1.8 * (1.0 - self), 0.0);\n"
                        + "}\n");
        GL20C.glUseProgram(bloomAddProgram);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(bloomAddProgram, "uScene"), 1);
        GL20C.glUseProgram(0);
        bloomAddInvSize = GL20C.glGetUniformLocation(bloomAddProgram, "uInvSize");

        // Nothing but the mask, kept for the pass that runs after the Vulkan
        // target has been handed back.
        bloomMaskProgram = buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "void main() {\n"
                        + "    float a = texture2D(uSource, gl_FragCoord.xy * uInvSize).a;\n"
                        + "    gl_FragColor = vec4(clamp((a - 0.5) * 2.0, 0.0, 1.0));\n"
                        + "}\n");
        bloomMaskInvSize = GL20C.glGetUniformLocation(bloomMaskProgram, "uInvSize");
        bloomAddStrength = GL20C.glGetUniformLocation(bloomAddProgram, "uStrength");
    }

    /** A fragment shader over a fullscreen quad, with uSource on unit 0. */
    private int buildQuadProgram(String body) {
        int vert = compileGlShader(GL20C.GL_VERTEX_SHADER, "#version 120\n"
                + "void main() { gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0); }\n");
        int frag = compileGlShader(GL20C.GL_FRAGMENT_SHADER, "#version 120\n" + body);
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vert);
        GL20C.glAttachShader(program, frag);
        GL20C.glLinkProgram(program);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Bloom program link failed: "
                    + GL20C.glGetProgramInfoLog(program));
        }
        GL20C.glDeleteShader(vert);
        GL20C.glDeleteShader(frag);
        int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        GL20C.glUseProgram(program);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uSource"), 0);
        GL20C.glUseProgram(prev);
        return program;
    }

    private int buildCompositeProgram(boolean writeDepth) {
        String vertSrc = "#version 120\n"
                + "void main() { gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0); }\n";
        String fragSrc = "#version 120\n"
                + "uniform sampler2D uColor;\n"
                + "uniform sampler2D uDepth;\n"
                + "uniform vec2 uInvSize;\n"
                + "void main() {\n"
                + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                + "    vec4 c = texture2D(uColor, uv);\n"
                + "    if (c.a < 0.004) discard;\n"
                + (writeDepth ? "    gl_FragDepth = texture2D(uDepth, uv).r;\n" : "")
                + "    gl_FragColor = vec4(c.rgb, 1.0);\n"
                + "}\n";
        int vert = compileGlShader(GL20C.GL_VERTEX_SHADER, vertSrc);
        int frag = compileGlShader(GL20C.GL_FRAGMENT_SHADER, fragSrc);
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vert);
        GL20C.glAttachShader(program, frag);
        GL20C.glLinkProgram(program);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Composite program link failed: "
                    + GL20C.glGetProgramInfoLog(program));
        }
        GL20C.glDeleteShader(vert);
        GL20C.glDeleteShader(frag);

        int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        GL20C.glUseProgram(program);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uColor"), 0);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uDepth"), 1);
        compositeInvSizeUniforms[writeDepth ? 0 : 1] =
                GL20C.glGetUniformLocation(program, "uInvSize");
        GL20C.glUseProgram(prev);
        return program;
    }

    private static int compileGlShader(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("Composite shader compile failed: "
                    + GL20C.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private void ensureTargets(int fbWidth, int fbHeight) {
        if (fbWidth == width && fbHeight == height && colorImage != 0) {
            return;
        }
        destroyTargets();
        width = fbWidth;
        height = fbHeight;
        try (MemoryStack stack = stackPush()) {
            long[] colorOut = new long[4];
            createExportedTarget(stack, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT, colorOut);
            colorImage = colorOut[0];
            colorMemory = colorOut[1];
            colorView = colorOut[2];
            glColorMemoryObject = importMemoryToGL(stack, colorMemory, colorOut[3]);
            glColorTexture = createGlTexture(glColorMemoryObject, org.lwjgl.opengl.GL11.GL_RGBA8);

            long[] depthOut = new long[4];
            createExportedTarget(stack, depthFormat(stack),
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT, depthOut);
            depthImage = depthOut[0];
            depthMemory = depthOut[1];
            depthView = depthOut[2];
            glDepthMemoryObject = importMemoryToGL(stack, depthMemory, depthOut[3]);
            boolean depth24 = depthFormat(stack) == VK_FORMAT_X8_D24_UNORM_PACK32;
            glDepthTexture = createGlTexture(glDepthMemoryObject, depth24
                    ? org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24
                    : org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT32F);
            // glBlitFramebuffer only copies depth between matching formats,
            // and the game's framebuffer is 24-bit.
            depthBlit = depth24 && depthBlitAllowed();
            if (depthBlit) {
                glDepthBlitFbo = createDepthReadFbo();
                depthBlit = glDepthBlitFbo != -1;
            }

            long[] translucentOut = new long[4];
            createExportedTarget(stack, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT, translucentOut);
            translucentImage = translucentOut[0];
            translucentMemory = translucentOut[1];
            translucentView = translucentOut[2];
            glTranslucentMemoryObject = importMemoryToGL(stack, translucentMemory, translucentOut[3]);
            glTranslucentTexture = createGlTexture(glTranslucentMemoryObject,
                    org.lwjgl.opengl.GL11.GL_RGBA8);

            VkFramebufferCreateInfo fbInfo = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(stack.longs(colorView, depthView))
                    .width(width)
                    .height(height)
                    .layers(1);
            LongBuffer pFb = stack.mallocLong(1);
            check(vkCreateFramebuffer(device(), fbInfo, null, pFb), "vkCreateFramebuffer(terrain)");
            framebuffer = pFb.get(0);

            // Same depth image, so the translucent layer is hidden by opaque
            // terrain in front of it without any work of its own.
            fbInfo.renderPass(translucentRenderPass)
                    .pAttachments(stack.longs(translucentView, depthView));
            check(vkCreateFramebuffer(device(), fbInfo, null, pFb),
                    "vkCreateFramebuffer(translucent)");
            translucentFramebuffer = pFb.get(0);

            int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            for (int i = 0; i < compositePrograms.length; i++) {
                GL20C.glUseProgram(compositePrograms[i]);
                GL20C.glUniform2f(compositeInvSizeUniforms[i], 1.0f / width, 1.0f / height);
            }
            if (translucentCompositeProgram != 0 && translucentInvSizeUniform != -1) {
                GL20C.glUseProgram(translucentCompositeProgram);
                GL20C.glUniform2f(translucentInvSizeUniform, 1.0f / width, 1.0f / height);
            }
            GL20C.glUseProgram(prev);

            // Diagnostic readback strip (host-visible, persistently mapped)
            int readbackSize = width * READBACK_ROWS * 4;
            VkBufferCreateInfo rbInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(readbackSize)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pRb = stack.mallocLong(1);
            check(vkCreateBuffer(device(), rbInfo, null, pRb), "vkCreateBuffer(readback)");
            readbackBuffer = pRb.get(0);
            VkMemoryRequirements rbReq = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), readbackBuffer, rbReq);
            VkMemoryAllocateInfo rbAlloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(rbReq.size())
                    .memoryTypeIndex(findMemoryType(stack, rbReq.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pRbMem = stack.mallocLong(1);
            check(vkAllocateMemory(device(), rbAlloc, null, pRbMem), "vkAllocateMemory(readback)");
            readbackMemory = pRbMem.get(0);
            check(vkBindBufferMemory(device(), readbackBuffer, readbackMemory, 0), "vkBindBufferMemory(readback)");
            PointerBuffer ppRb = stack.mallocPointer(1);
            check(vkMapMemory(device(), readbackMemory, 0, readbackSize, 0, ppRb), "vkMapMemory(readback)");
            readbackMapped = ppRb.get(0);
        }
        firstFrame = true;
        LOGGER.info("Terrain targets (re)created: {}x{} color+depth shared with GL (textures {}/{})",
                width, height, glColorTexture, glDepthTexture);
    }

    private static boolean depthBlitAllowed() {
        return !"false".equals(System.getProperty("vulkanmod112.depthBlit"));
    }

    /**
     * Prefers 24-bit depth so the result can be blitted straight into the
     * game's depth buffer; falls back to D32_SFLOAT where the driver has no
     * sampleable D24 (common on AMD).
     */
    private int depthFormat(MemoryStack stack) {
        if (depthFormat != 0) {
            return depthFormat;
        }
        if (!depthBlitAllowed()) {
            return depthFormat = VK_FORMAT_D32_SFLOAT;
        }
        VkFormatProperties props = VkFormatProperties.malloc(stack);
        vkGetPhysicalDeviceFormatProperties(ctx.getPhysicalDevice(),
                VK_FORMAT_X8_D24_UNORM_PACK32, props);
        int needed = VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT
                | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        return depthFormat = (props.optimalTilingFeatures() & needed) == needed
                ? VK_FORMAT_X8_D24_UNORM_PACK32
                : VK_FORMAT_D32_SFLOAT;
    }

    /** Read-only FBO wrapping the shared depth texture; -1 if incomplete. */
    private int createDepthReadFbo() {
        int prevRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int fbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11C.GL_TEXTURE_2D, glDepthTexture, 0);
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_READ_FRAMEBUFFER);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevRead);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.warn("Depth blit FBO incomplete (0x{}), falling back to gl_FragDepth composite",
                    Integer.toHexString(status));
            GL30C.glDeleteFramebuffers(fbo);
            return -1;
        }
        return fbo;
    }

    /** out: image, memory, view, allocationSize */
    private void createExportedTarget(MemoryStack stack, int format, int usage, int aspect, long[] out) {
        VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
                .handleTypes(Interop.MEMORY_HANDLE_TYPE);
        VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(external.address())
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        imageInfo.extent().width(width).height(height).depth(1);
        LongBuffer pImage = stack.mallocLong(1);
        check(vkCreateImage(device(), imageInfo, null, pImage), "vkCreateImage(target)");
        long image = pImage.get(0);

        VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
        vkGetImageMemoryRequirements(device(), image, req);
        VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
                .image(image);
        VkExportMemoryAllocateInfo export = VkExportMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
                .pNext(dedicated.address())
                .handleTypes(Interop.MEMORY_HANDLE_TYPE);
        VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .pNext(export.address())
                .allocationSize(req.size())
                .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
        LongBuffer pMemory = stack.mallocLong(1);
        check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(target)");
        long memory = pMemory.get(0);
        check(vkBindImageMemory(device(), image, memory, 0), "vkBindImageMemory(target)");

        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
        viewInfo.subresourceRange()
                .aspectMask(aspect)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        LongBuffer pView = stack.mallocLong(1);
        check(vkCreateImageView(device(), viewInfo, null, pView), "vkCreateImageView(target)");

        out[0] = image;
        out[1] = memory;
        out[2] = pView.get(0);
        out[3] = req.size();
    }

    private int importMemoryToGL(MemoryStack stack, long memory, long size) {
        // The targets are allocated with VkMemoryDedicatedAllocateInfo, so GL
        // must be told so before the import or it assumes a different memory
        // layout and reads garbage (small images happen to match, large ones
        // do not) — Interop.importMemoryToGL does that for us.
        return Interop.importMemoryToGL(stack, device(), memory, size, true);
    }

    private int createGlTexture(int memoryObject, int internalFormat) {
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, EXTMemoryObject.GL_TEXTURE_TILING_EXT,
                EXTMemoryObject.GL_OPTIMAL_TILING_EXT);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        EXTMemoryObject.glTexStorageMem2DEXT(GL11C.GL_TEXTURE_2D, 1, internalFormat,
                width, height, memoryObject, 0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
        int error = GL11C.glGetError();
        if (error != 0) {
            throw new IllegalStateException("GL error 0x" + Integer.toHexString(error)
                    + " importing terrain target (format 0x" + Integer.toHexString(internalFormat) + ")");
        }
        return texture;
    }

    private int importSemaphore(MemoryStack stack, long vkSemaphore) {
        return Interop.importSemaphoreToGL(stack, device(), vkSemaphore);
    }

    private void ensureQuadIndexCapacity(int quads) {
        quads = Math.max(quads, 4096);
        if (quads <= quadIndexCapacityQuads) {
            return;
        }
        quads = Integer.highestOneBit(quads) * 2; // headroom: chunks keep growing
        if (quadIndexBuffer != 0) {
            vkDestroyBuffer(device(), quadIndexBuffer, null);
            vkFreeMemory(device(), quadIndexMemory, null);
        }
        try (MemoryStack stack = stackPush()) {
            long byteSize = quads * 6L * 4L;
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(byteSize)
                    .usage(VK_BUFFER_USAGE_INDEX_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device(), bufferInfo, null, pBuffer), "vkCreateBuffer(quad index)");
            quadIndexBuffer = pBuffer.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), quadIndexBuffer, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(quad index)");
            quadIndexMemory = pMemory.get(0);
            check(vkBindBufferMemory(device(), quadIndexBuffer, quadIndexMemory, 0),
                    "vkBindBufferMemory(quad index)");

            PointerBuffer ppData = stack.mallocPointer(1);
            check(vkMapMemory(device(), quadIndexMemory, 0, byteSize, 0, ppData), "vkMapMemory(quad index)");
            long addr = ppData.get(0);
            for (int q = 0; q < quads; q++) {
                long base = addr + q * 24L;
                int v = q * 4;
                MemoryUtil.memPutInt(base, v);
                MemoryUtil.memPutInt(base + 4, v + 1);
                MemoryUtil.memPutInt(base + 8, v + 2);
                MemoryUtil.memPutInt(base + 12, v);
                MemoryUtil.memPutInt(base + 16, v + 2);
                MemoryUtil.memPutInt(base + 20, v + 3);
            }
            vkUnmapMemory(device(), quadIndexMemory);
        }
        quadIndexCapacityQuads = quads;
        LOGGER.info("Quad index buffer sized for {} quads", quads);
    }

    private void createLightmapResources() {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().width(LIGHTMAP_SIZE).height(LIGHTMAP_SIZE).depth(1);
            LongBuffer pImage = stack.mallocLong(1);
            check(vkCreateImage(device(), imageInfo, null, pImage), "vkCreateImage(lightmap)");
            lightmapImage = pImage.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device(), lightmapImage, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(lightmap)");
            lightmapMemory = pMemory.get(0);
            check(vkBindImageMemory(device(), lightmapImage, lightmapMemory, 0), "vkBindImageMemory(lightmap)");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(lightmapImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(device(), viewInfo, null, pView), "vkCreateImageView(lightmap)");
            lightmapView = pView.get(0);

            int stagingSize = LIGHTMAP_SIZE * LIGHTMAP_SIZE * 4;
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(stagingSize)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer ppData = stack.mallocPointer(1);
            for (int i = 0; i < framesInFlight; i++) {
                check(vkCreateBuffer(device(), bufferInfo, null, pBuffer), "vkCreateBuffer(lightmap)");
                lightmapStagingBuffer[i] = pBuffer.get(0);
                vkGetBufferMemoryRequirements(device(), lightmapStagingBuffer[i], req);
                alloc = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(req.size())
                        .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(),
                                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
                check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(lightmap staging)");
                lightmapStagingMemory[i] = pMemory.get(0);
                check(vkBindBufferMemory(device(), lightmapStagingBuffer[i], lightmapStagingMemory[i], 0),
                        "vkBindBufferMemory(lightmap)");
                check(vkMapMemory(device(), lightmapStagingMemory[i], 0, stagingSize, 0, ppData),
                        "vkMapMemory(lightmap)");
                lightmapStagingMapped[i] = ppData.get(0);
            }
            lightmapReadBuffer = MemoryUtil.memAlloc(stagingSize);
        }
    }

    /** Uploads pixels into a new device-local sampled image via a one-time submit. */
    /**
     * Creates a device-local sampled image and fills every supplied mip level
     * (index 0 is full size, each next one half). Uploads through one staging
     * buffer per level and blocks until done — only ever called on atlas load.
     */
    private void createSampledImage(MemoryStack stack, int imgWidth, int imgHeight, ByteBuffer[] levels,
                                    long[] out) {
        int mipLevels = levels.length;
        VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .mipLevels(mipLevels)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        imageInfo.extent().width(imgWidth).height(imgHeight).depth(1);
        LongBuffer pImage = stack.mallocLong(1);
        check(vkCreateImage(device(), imageInfo, null, pImage), "vkCreateImage(sampled)");
        long image = pImage.get(0);

        VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
        vkGetImageMemoryRequirements(device(), image, req);
        VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
        LongBuffer pMemory = stack.mallocLong(1);
        check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(sampled)");
        long memory = pMemory.get(0);
        check(vkBindImageMemory(device(), image, memory, 0), "vkBindImageMemory(sampled)");

        // Staging upload, one buffer per mip level
        long[] stagingBuffers = new long[mipLevels];
        long[] stagingMemories = new long[mipLevels];
        LongBuffer pBuffer = stack.mallocLong(1);
        LongBuffer pSMemory = stack.mallocLong(1);
        PointerBuffer ppData = stack.mallocPointer(1);
        for (int level = 0; level < mipLevels; level++) {
            int byteCount = levels[level].remaining();
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(byteCount)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateBuffer(device(), bufferInfo, null, pBuffer), "vkCreateBuffer(staging)");
            stagingBuffers[level] = pBuffer.get(0);
            VkMemoryRequirements sreq = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), stagingBuffers[level], sreq);
            VkMemoryAllocateInfo salloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(sreq.size())
                    .memoryTypeIndex(findMemoryType(stack, sreq.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            check(vkAllocateMemory(device(), salloc, null, pSMemory), "vkAllocateMemory(staging)");
            stagingMemories[level] = pSMemory.get(0);
            check(vkBindBufferMemory(device(), stagingBuffers[level], stagingMemories[level], 0),
                    "vkBindBufferMemory(staging)");
            check(vkMapMemory(device(), stagingMemories[level], 0, byteCount, 0, ppData), "vkMapMemory(staging)");
            MemoryUtil.memCopy(MemoryUtil.memAddress(levels[level]), ppData.get(0), byteCount);
            vkUnmapMemory(device(), stagingMemories[level]);
        }

        // One-time command: transition, copy, transition
        ensureBaseResources();
        check(vkWaitForFences(device(), fence, true, 1_000_000_000L), "vkWaitForFences(atlas)");
        vkResetFences(device(), fence);
        vkResetCommandBuffer(commandBuffer, 0);
        VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer(atlas)");

        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.get(0).subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        for (int level = 0; level < mipLevels; level++) {
            final int levelWidth = Math.max(1, imgWidth >> level);
            final int levelHeight = Math.max(1, imgHeight >> level);
            region.get(0).imageExtent(e -> e.width(levelWidth).height(levelHeight).depth(1));
            region.get(0).imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).baseArrayLayer(0).layerCount(1);
            vkCmdCopyBufferToImage(commandBuffer, stagingBuffers[level], image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }

        barrier.get(0)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier);

        check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer(atlas)");
        VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(commandBuffer));
        check(vkQueueSubmit(ctx.getGraphicsQueue(), submit, fence), "vkQueueSubmit(atlas)");
        check(vkWaitForFences(device(), fence, true, 5_000_000_000L), "vkWaitForFences(atlas upload)");
        vkResetFences(device(), fence);
        // Leave the fence signaled for the frame loop's initial wait
        VkSubmitInfo empty = VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
        check(vkQueueSubmit(ctx.getGraphicsQueue(), empty, fence), "vkQueueSubmit(fence reprime)");

        for (int level = 0; level < mipLevels; level++) {
            vkDestroyBuffer(device(), stagingBuffers[level], null);
            vkFreeMemory(device(), stagingMemories[level], null);
        }

        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_UNORM);
        viewInfo.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
        LongBuffer pView = stack.mallocLong(1);
        check(vkCreateImageView(device(), viewInfo, null, pView), "vkCreateImageView(sampled)");

        out[0] = image;
        out[1] = memory;
        out[2] = pView.get(0);
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    private void destroyAtlas() {
        destroyAtlasStaging();
        if (atlasImage != 0) {
            vkDeviceWaitIdle(device());
            vkDestroyImageView(device(), atlasView, null);
            vkDestroyImage(device(), atlasImage, null);
            vkFreeMemory(device(), atlasMemory, null);
            atlasImage = 0;
        }
    }

    /**
     * True when the calling thread has a GL context (the client thread). The
     * JVM shutdown hook has none — GL calls there abort the whole JVM.
     */
    private static boolean glContextCurrent() {
        try {
            org.lwjgl.opengl.GL.getCapabilities();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void destroyTargets() {
        if (colorImage == 0) {
            return;
        }
        vkDeviceWaitIdle(device());
        if (glColorTexture != -1 && glContextCurrent()) {
            // Sized from this target, so it goes with it.
            destroyBloomTargets();
            GL11C.glDeleteTextures(glColorTexture);
            GL11C.glDeleteTextures(glDepthTexture);
            EXTMemoryObject.glDeleteMemoryObjectsEXT(glColorMemoryObject);
            EXTMemoryObject.glDeleteMemoryObjectsEXT(glDepthMemoryObject);
            if (glTranslucentTexture != -1) {
                GL11C.glDeleteTextures(glTranslucentTexture);
                EXTMemoryObject.glDeleteMemoryObjectsEXT(glTranslucentMemoryObject);
            }
        }
        glTranslucentTexture = -1;
        if (glDepthBlitFbo != -1 && glContextCurrent()) {
            GL30C.glDeleteFramebuffers(glDepthBlitFbo);
        }
        glDepthBlitFbo = -1;
        depthBlit = false;
        glColorTexture = -1;
        glDepthTexture = -1;
        vkDestroyFramebuffer(device(), framebuffer, null);
        if (translucentFramebuffer != 0) {
            vkDestroyFramebuffer(device(), translucentFramebuffer, null);
            vkDestroyImageView(device(), translucentView, null);
            vkDestroyImage(device(), translucentImage, null);
            vkFreeMemory(device(), translucentMemory, null);
            translucentFramebuffer = 0;
            translucentImage = 0;
        }
        vkDestroyImageView(device(), colorView, null);
        vkDestroyImage(device(), colorImage, null);
        vkFreeMemory(device(), colorMemory, null);
        vkDestroyImageView(device(), depthView, null);
        vkDestroyImage(device(), depthImage, null);
        vkFreeMemory(device(), depthMemory, null);
        if (readbackBuffer != 0) {
            vkUnmapMemory(device(), readbackMemory);
            vkDestroyBuffer(device(), readbackBuffer, null);
            vkFreeMemory(device(), readbackMemory, null);
            readbackBuffer = 0;
        }
        colorImage = 0;
    }

    synchronized void destroy() {
        if (!baseReady) {
            return;
        }
        vkDeviceWaitIdle(device());
        destroyTargets();
        destroyAtlas();
        if (lightmapImage != 0) {
            vkDestroyImageView(device(), lightmapView, null);
            vkDestroyImage(device(), lightmapImage, null);
            vkFreeMemory(device(), lightmapMemory, null);
            for (int i = 0; i < framesInFlight; i++) {
                if (lightmapStagingMemory[i] != 0) {
                    vkUnmapMemory(device(), lightmapStagingMemory[i]);
                    vkDestroyBuffer(device(), lightmapStagingBuffer[i], null);
                    vkFreeMemory(device(), lightmapStagingMemory[i], null);
                    lightmapStagingBuffer[i] = 0;
                    lightmapStagingMemory[i] = 0;
                    lightmapStagingMapped[i] = 0;
                }
            }
            for (int i = 0; i < framesInFlight; i++) {
                if (frameUniformMemories[i] != 0) {
                    vkUnmapMemory(device(), frameUniformMemories[i]);
                    vkDestroyBuffer(device(), frameUniformBuffers[i], null);
                    vkFreeMemory(device(), frameUniformMemories[i], null);
                    frameUniformBuffers[i] = 0;
                    frameUniformMemories[i] = 0;
                    frameUniformMapped[i] = 0;
                }
            }
            MemoryUtil.memFree(lightmapReadBuffer);
            lightmapReadBuffer = null;
            lightmapImage = 0;
        }
        if (quadIndexBuffer != 0) {
            vkDestroyBuffer(device(), quadIndexBuffer, null);
            vkFreeMemory(device(), quadIndexMemory, null);
            quadIndexBuffer = 0;
            quadIndexCapacityQuads = 0;
        }
        destroyDrawBatches();
        for (int i = 0; i < pipelines.length; i++) {
            if (pipelines[i] != 0) {
                vkDestroyPipeline(device(), pipelines[i], null);
                pipelines[i] = 0;
            }
        }
        // Saved here rather than at exit: the device is still alive, and this
        // is the last point at which the driver can be asked for the blob.
        pipelineCache.destroy(device());
        pipelineCacheHandle = VK_NULL_HANDLE;
        vkDestroyPipelineLayout(device(), pipelineLayout, null);
        if (translucentRenderPass != 0) {
            vkDestroyRenderPass(device(), translucentRenderPass, null);
            translucentRenderPass = 0;
        }
        vkDestroyRenderPass(device(), renderPass, null);
        vkDestroyDescriptorPool(device(), descriptorPool, null);
        vkDestroyDescriptorSetLayout(device(), descriptorSetLayout, null);
        vkDestroySampler(device(), atlasSampler, null);
        vkDestroySampler(device(), lightmapSampler, null);
        vkDestroySemaphore(device(), vkSignalSemaphore, null);
        vkDestroySemaphore(device(), vkWaitSemaphore, null);
        if (vkTranslucentSignalSemaphore != 0) {
            vkDestroySemaphore(device(), vkTranslucentSignalSemaphore, null);
            vkDestroySemaphore(device(), vkTranslucentWaitSemaphore, null);
            vkTranslucentSignalSemaphore = 0;
            vkTranslucentWaitSemaphore = 0;
        }
        if (fences != null) {
            for (long frameFence : fences) {
                vkDestroyFence(device(), frameFence, null);
            }
            fences = null;
        }
        if (translucentFences != null) {
            for (long frameFence : translucentFences) {
                vkDestroyFence(device(), frameFence, null);
            }
            translucentFences = null;
        }
        if (queryPool != 0) {
            vkDestroyQueryPool(device(), queryPool, null);
            queryPool = 0;
        }
        vkDestroyCommandPool(device(), commandPool, null);
        baseReady = false;
    }

    private int findMemoryType(MemoryStack stack, int typeBits, int properties) {
        int type = findMemoryTypeOrNone(stack, typeBits, properties);
        if (type < 0) {
            throw new IllegalStateException("No suitable memory type");
        }
        return type;
    }

    /** Same, but returns -1 instead of throwing, for properties that are a preference. */
    private int findMemoryTypeOrNone(MemoryStack stack, int typeBits, int properties) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(device().getPhysicalDevice(), memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                    && (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        return -1;
    }

    private long createShaderModule(MemoryStack stack, String resource) {
        byte[] code = readResource(resource);
        ByteBuffer buf = MemoryUtil.memAlloc(code.length);
        buf.put(code);
        buf.rewind();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(buf);
            LongBuffer pModule = stack.mallocLong(1);
            check(vkCreateShaderModule(device(), info, null, pModule), "vkCreateShaderModule " + resource);
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static byte[] readResource(String resource) {
        try (InputStream in = VkTerrainRenderer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Shader " + resource + " not found on classpath");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read shader " + resource, e);
        }
    }

    private static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(call + " failed with VkResult " + result);
        }
    }

}
