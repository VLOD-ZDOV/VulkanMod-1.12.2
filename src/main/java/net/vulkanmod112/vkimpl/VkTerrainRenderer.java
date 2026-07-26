package net.vulkanmod112.vkimpl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.GL11C;
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
    private long renderPass;
    private long descriptorSetLayout;
    private long descriptorPool;
    private long descriptorSet;
    private final long[] drawBatchBuffers = new long[framesInFlight * 3];
    private final long[] drawBatchMemories = new long[framesInFlight * 3];
    private final long[] drawBatchMapped = new long[framesInFlight * 3];
    private final long[] drawDescriptorSets = new long[framesInFlight * 3];
    /** Draws each batch buffer can hold; grown to fit the scene, never shrunk. */
    private int indirectDrawCapacity = INITIAL_INDIRECT_DRAWS;
    private long drawCommandOffset = (long) INITIAL_INDIRECT_DRAWS * DRAW_ORIGIN_BYTES;
    private long drawBatchBytes = drawCommandOffset
            + (long) INITIAL_INDIRECT_DRAWS * DRAW_COMMAND_BYTES;
    /** Largest layer of the previous frame; the batch is grown to fit it. */
    private int peakDrawsNeeded;
    /** Per-layer lookup results, reused across frames. */
    private VkChunkMirror.Entry[] lookupScratch = new VkChunkMirror.Entry[INITIAL_INDIRECT_DRAWS];
    private long pipelineLayout;
    /**
     * Two specialisations of the same shader modules: index 0 has the alpha
     * test compiled out for SOLID, index 1 keeps it for the CUTOUT layers.
     * Index with {@code layerOrdinal == 0 ? 0 : 1}.
     */
    private final long[] pipelines = new long[2];
    private long atlasSampler;
    private long lightmapSampler;

    // Atlas / lightmap
    private long atlasImage;
    private long atlasMemory;
    private long atlasView;
    private int atlasWidth;
    private int atlasHeight;
    private int atlasLevels = 1;
    private int lightmapGlId = -1;
    private long lightmapImage;
    private long lightmapMemory;
    private long lightmapView;
    private final long[] lightmapStagingBuffer = new long[framesInFlight];
    private final long[] lightmapStagingMemory = new long[framesInFlight];
    private final long[] lightmapStagingMapped = new long[framesInFlight];
    private ByteBuffer lightmapReadBuffer;
    private boolean lightmapImageInitialized;
    private int[] lightmapData;

    // Size-dependent shared targets
    private int width;
    private int height;
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
        if (layerOrdinal == 3 || mirror == null || atlasImage == 0 || lightmapGlId == -1) {
            return false;
        }
        ctx.ensureGlCapabilities();
        ensureBaseResources();
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
        sb.append("  index buffer: ").append(quadIndexCapacityQuads).append(" quads")
                .append(", draw batch ").append(indirectDrawCapacity)
                .append(", frames in flight ").append(framesInFlight).append('\n');
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

            if (lightmapData != null) {
                writeLightmapStaging(lightmapData);
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

            recordLightmapUpload(stack);

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

            // The MVP is identical for every chunk and layer: push it once
            ByteBuffer mvpPush = stack.malloc(64);
            for (int i = 0; i < 16; i++) {
                mvpPush.putFloat(i * 4, mvp[i]);
            }
            vkCmdPushConstants(commandBuffer, pipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, mvpPush);

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
        float cutoff = layerOrdinal == 0 ? 0.0f : (layerOrdinal == 1 ? 0.5f : 0.1f);
        try (MemoryStack stack = stackPush()) {
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipelines[layerOrdinal == 0 ? 0 : 1]);
            // Cutoff changes per layer; per-chunk origins are fetched by the vertex shader.
            ByteBuffer cutoffPush = stack.malloc(4);
            cutoffPush.putFloat(0, cutoff);
            vkCmdPushConstants(commandBuffer, pipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 76, cutoffPush);
            LongBuffer pBuffer = stack.longs(mirror.geometryBuffer());
            LongBuffer pOffset = stack.longs(0L);
            // All chunks are suballocations of this one device-local buffer.
            vkCmdBindVertexBuffers(commandBuffer, 0, pBuffer, pOffset);
            boolean logInputs = layerOrdinal == 0 && (frameCounter == 0 || frameCounter == 119);

            int batchIndex = activeFrameSlot * 3 + layerOrdinal;
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
                MemoryUtil.memPutFloat(origin + 12, 0.0f);
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
            if (frameCounter == 0 || frameCounter == 119) {
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
        if (frameCounter != 1 && frameCounter != 120) {
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

            createDescriptorInfrastructure(stack);
            createDrawBatches(stack);
            createRenderPass(stack);
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

        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
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
        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);
        LongBuffer pLayout = stack.mallocLong(1);
        check(vkCreateDescriptorSetLayout(device(), layoutInfo, null, pLayout), "vkCreateDescriptorSetLayout");
        descriptorSetLayout = pLayout.get(0);

        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
        poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(drawDescriptorSets.length * 2);
        poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(drawDescriptorSets.length);
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
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(indirect terrain)");
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
        LOGGER.info("Indirect draw batches grown to {} draws ({} MiB across {} batches)",
                target, String.format("%.1f", drawBatchBytes * drawBatchBuffers.length / (1024.0 * 1024.0)),
                drawBatchBuffers.length);
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

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(drawDescriptorSets.length * 3, stack);
            for (int i = 0; i < drawDescriptorSets.length; i++) {
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.get(0).buffer(drawBatchBuffers[i]).offset(0).range(drawCommandOffset);
                int write = i * 3;
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
    }

    private void createPipeline(MemoryStack stack) {
        long vertModule = createShaderModule(stack, "vulkanmod112/shaders/terrain.vert.spv");
        long fragModule = createShaderModule(stack, "vulkanmod112/shaders/terrain.frag.spv");

        ByteBuffer entryPoint = stack.UTF8("main");

        // Vanilla BLOCK vertex format: pos 3f | color 4ub | uv 2f | lightmap 2s = 28 bytes
        VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
        binding.get(0).binding(0).stride(BLOCK_VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(4, stack);
        attrs.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
        attrs.get(1).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(12);
        attrs.get(2).location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(16);
        attrs.get(3).location(3).binding(0).format(VK_FORMAT_R16G16_SSCALED).offset(24);
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

        VkPipelineDepthStencilStateCreateInfo depthState = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                .depthTestEnable(true)
                .depthWriteEnable(true)
                .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);

        VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                VkPipelineColorBlendAttachmentState.calloc(1, stack);
        blendAttachment.get(0)
                .blendEnable(false)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                        | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
        VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .pAttachments(blendAttachment);

        VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

        VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
        pushRange.get(0)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .offset(0)
                .size(80);
        VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(descriptorSetLayout))
                .pPushConstantRanges(pushRange);
        LongBuffer pLayout = stack.mallocLong(1);
        check(vkCreatePipelineLayout(device(), layoutInfo, null, pLayout), "vkCreatePipelineLayout(terrain)");
        pipelineLayout = pLayout.get(0);

        // ALPHA_TEST (constant_id 0) off for SOLID, on for the CUTOUT layers.
        // Booleans travel as a 32-bit value, like VkBool32.
        VkSpecializationMapEntry.Buffer specEntry = VkSpecializationMapEntry.calloc(1, stack);
        specEntry.get(0).constantID(0).offset(0).size(4);

        VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(2, stack);
        for (int variant = 0; variant < 2; variant++) {
            VkSpecializationInfo specInfo = VkSpecializationInfo.calloc(stack)
                    .pMapEntries(specEntry)
                    .pData(stack.bytes((byte) variant, (byte) 0, (byte) 0, (byte) 0));
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
                    .renderPass(renderPass)
                    .subpass(0);
        }
        LongBuffer pPipeline = stack.mallocLong(2);
        check(vkCreateGraphicsPipelines(device(), VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                "vkCreateGraphicsPipelines(terrain)");
        pipelines[0] = pPipeline.get(0);
        pipelines[1] = pPipeline.get(1);

        vkDestroyShaderModule(device(), vertModule, null);
        vkDestroyShaderModule(device(), fragModule, null);
    }

    private void createCompositeProgram() {
        compositePrograms[0] = buildCompositeProgram(true);
        compositePrograms[1] = buildCompositeProgram(false);
    }

    /**
     * @param writeDepth export the Vulkan depth per fragment; false when the
     *                   depth buffer is filled by glBlitFramebuffer instead
     */
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

            int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            for (int i = 0; i < compositePrograms.length; i++) {
                GL20C.glUseProgram(compositePrograms[i]);
                GL20C.glUniform2f(compositeInvSizeUniforms[i], 1.0f / width, 1.0f / height);
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
            GL11C.glDeleteTextures(glColorTexture);
            GL11C.glDeleteTextures(glDepthTexture);
            EXTMemoryObject.glDeleteMemoryObjectsEXT(glColorMemoryObject);
            EXTMemoryObject.glDeleteMemoryObjectsEXT(glDepthMemoryObject);
        }
        if (glDepthBlitFbo != -1 && glContextCurrent()) {
            GL30C.glDeleteFramebuffers(glDepthBlitFbo);
        }
        glDepthBlitFbo = -1;
        depthBlit = false;
        glColorTexture = -1;
        glDepthTexture = -1;
        vkDestroyFramebuffer(device(), framebuffer, null);
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
        vkDestroyPipelineLayout(device(), pipelineLayout, null);
        vkDestroyRenderPass(device(), renderPass, null);
        vkDestroyDescriptorPool(device(), descriptorPool, null);
        vkDestroyDescriptorSetLayout(device(), descriptorSetLayout, null);
        vkDestroySampler(device(), atlasSampler, null);
        vkDestroySampler(device(), lightmapSampler, null);
        vkDestroySemaphore(device(), vkSignalSemaphore, null);
        vkDestroySemaphore(device(), vkWaitSemaphore, null);
        if (fences != null) {
            for (long frameFence : fences) {
                vkDestroyFence(device(), frameFence, null);
            }
            fences = null;
        }
        if (queryPool != 0) {
            vkDestroyQueryPool(device(), queryPool, null);
            queryPool = 0;
        }
        vkDestroyCommandPool(device(), commandPool, null);
        baseReady = false;
    }

    private int findMemoryType(MemoryStack stack, int typeBits, int properties) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(device().getPhysicalDevice(), memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                    && (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        throw new IllegalStateException("No suitable memory type");
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
