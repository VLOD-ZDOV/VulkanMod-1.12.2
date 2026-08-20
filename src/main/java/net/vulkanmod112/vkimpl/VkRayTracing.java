package net.vulkanmod112.vkimpl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Acceleration structures over the terrain this renderer already owns.
 *
 * <h2>What this is for, and how it got here</h2>
 *
 * These are traced against every frame: the terrain shader asks them whether
 * anything stands between a surface and the sun, and since August whether
 * anything creature-shaped does.
 *
 * They were built before anything read them, and deliberately. The roadmap had
 * carried one sentence about ray tracing for weeks — that the structure has to
 * be rebuilt whenever a chunk is, and that rebuilding chunks is already the
 * largest cost in a moving frame — and it had never been a number. Writing the
 * shader first would have meant finding out afterwards whether the thing it
 * read could be maintained at all. So this built them, kept them, and reported
 * what they cost, and the answer decided that there was a shader worth
 * writing.
 *
 * <h2>How it maps onto what already exists</h2>
 *
 * It maps unusually well, which is the reason to try. Chunk geometry is already
 * one device-local buffer of quads with a shared triangle index buffer beside
 * it, and vertices are already chunk-local with the chunk's position supplied
 * separately — which is exactly the shape an acceleration structure wants: one
 * structure per chunk in its own coordinates, and one instance per chunk
 * carrying the translation. The position is the first twelve bytes of each
 * twenty-eight byte vertex, so no repacking is needed either.
 *
 * <h2>Bounded on purpose</h2>
 *
 * Only chunks near the camera get a structure, and only a few are built per
 * frame. Both limits exist because the alternative is unbounded: a world at
 * render distance sixty-four is tens of thousands of chunks, and a structure
 * for each would cost more memory than the geometry it describes. Anything a
 * traced ray is going to be believed about — a shadow, a contact reflection —
 * is near the camera anyway.
 */
final class VkRayTracing {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/RayTracing");

    /** Vertex stride of the mirrored chunk geometry, positions first. */
    private static final int VERTEX_STRIDE = 28;

    private final VulkanContextImpl ctx;

    private long commandPool;
    private VkCommandBuffer commandBuffer;
    private long fence;
    private boolean ready;
    private boolean broken;
    private boolean submitted;

    private long scratchBuffer;
    private long scratchMemory;
    private long scratchAddress;
    private long scratchCapacity;
    private long scratchAlignment = 256;

    private long instanceBuffer;
    private long instanceMemory;
    private long instanceMapped;
    private long instanceAddress;
    private int instanceCapacity;

    /**
     * One top-level structure per frame in flight, and the reason is a lost
     * device rather than tidiness.
     *
     * A shader traverses this while it shades, and shading a frame outlives the
     * command that started it by however many frames the renderer runs ahead.
     * One structure rebuilt every frame is therefore rewritten underneath the
     * frames still reading it, and a traversal walking a structure being
     * rewritten does not come back wrong — it does not come back. The card
     * gives up on switching away from it and the driver resets, which is what a
     * CTX SWITCH TIMEOUT is.
     *
     * Per slot, the write happens after the fence that says every frame which
     * used that slot has finished.
     */
    private long[] tlas;
    private long[] tlasBuffer;
    private long[] tlasMemory;
    private long[] tlasCapacity;
    private int activeSlot;
    private int slotCount = 1;

    /**
     * Structures that are no longer wanted but may still be under a ray.
     *
     * Freed once every frame that could name them has finished. The same rule
     * the chunk mirror follows for its geometry buffers, and for the same
     * reason: a handle in a command buffer already submitted is a handle the
     * card will read.
     */
    private final java.util.ArrayList<Retired> retired = new java.util.ArrayList<Retired>();

    private static final class Retired {
        long structure;
        long buffer;
        long memory;
        long bytes;
        long frame;
    }

    /** One structure per mirror slot, keyed by it. */
    private final java.util.HashMap<Integer, Blas> structures = new java.util.HashMap<Integer, Blas>();
    private final java.util.ArrayList<Blas> live = new java.util.ArrayList<Blas>();

    // Everything below is measurement, kept because the cost of these
    // structures is the thing that decides whether tracing stays on.
    private int lastBuilt;
    private int lastInstances;
    private long lastBuildNanos;
    private long totalBuilt;
    private long structureBytes;

    private static final class Blas {
        long structure;
        long buffer;
        long memory;
        long address;
        long bytes;
        /** What it was built from; a change in any of these means it is stale. */
        long sourceOffset;
        int sourceSize;
        long sourceVersion;
        float x;
        float y;
        float z;
        long touchedFrame;
        /** See {@link #KIND_SOLID}: what a ray is allowed to see through. */
        int kind;
    }

    /**
     * What a structure is made of, as far as a shadow ray is concerned.
     *
     * A ray does not read textures, so without this every leaf quad stops a ray
     * as though it were stone — which is exactly what a canopy's shadow looked
     * like: a solid block of shade under a tree that is mostly holes. The kind
     * travels in the instance's custom index, twenty-four bits of which are
     * free, and the shader turns it into how likely the quad is to stop light.
     */
    static final int KIND_SOLID = 0;
    static final int KIND_FOLIAGE = 1;
    static final int KIND_CUTOUT = 2;
    static final int KIND_CREATURE = 3;

    private int solidCount;
    private int foliageEnd;

    /**
     * Where each kind ends in the list handed to {@link #update}.
     *
     * The renderer builds that list by joining the layers in a fixed order, so
     * a position in it is the only thing that says what a chunk is made of —
     * there is no room for a tag beside it and no need for one.
     */
    void setKindBounds(int solidCount, int foliageEnd) {
        this.solidCount = solidCount;
        this.foliageEnd = foliageEnd;
    }

    VkRayTracing(VulkanContextImpl ctx) {
        this.ctx = ctx;
    }

    private VkDevice device() {
        return ctx.getDevice();
    }

    /** The structure the shaders of one frame slot trace against, or 0. */
    long topLevel(int slot) {
        return tlas == null || slot < 0 || slot >= tlas.length ? 0 : tlas[slot];
    }

    boolean isUsable() {
        return !broken && ctx.isRayTracingEnabled();
    }

    /**
     * Brings this frame's structures up to date and submits the builds.
     *
     * Called once a frame with the same chunk list the opaque layer draws, so
     * the set of structures follows what is actually on screen rather than a
     * second notion of visibility maintained here.
     */
    void update(int[] chunks, int chunkCount, VkChunkMirror mirror, long frameIndex,
                int slot, int slots, double viewX, double viewY, double viewZ) {
        if (!isUsable()) {
            return;
        }
        this.activeSlot = slot;
        this.slotCount = Math.max(1, slots);
        try {
            ensureResources();
            waitForPreviousBuild();
            // Safe here and nowhere earlier: the submission that named these
            // has just been waited on.
            releaseRetiredScratch();
            releaseRetired(frameIndex);
            buildFrame(chunks, chunkCount, mirror, frameIndex, viewX, viewY, viewZ);
        } catch (Throwable t) {
            broken = true;
            // Told to the context as well, because what asks "is this session
            // tracing" asks it there.
            ctx.noteRayTracingBroken();
            LOGGER.error("Acceleration structures failed and are now off for this session; "
                    + "nothing else in the renderer depends on them", t);
        }
    }

    /** The frame the current batch of builds belongs to, for retiring. */
    private long buildFrameIndex;

    private void buildFrame(int[] chunks, int chunkCount, VkChunkMirror mirror, long frameIndex,
                            double viewX, double viewY, double viewZ) {
        buildFrameIndex = frameIndex;
        int radius = radiusBlocks();
        int budget = buildsPerFrame();
        int maxStructures = maxStructures();

        VkChunkMirror.Entry[] entries = new VkChunkMirror.Entry[chunkCount];
        mirror.findAll(chunks, chunkCount, entries);

        live.clear();
        java.util.ArrayList<Blas> toBuild = new java.util.ArrayList<Blas>();
        long geometryAddress = bufferAddress(mirror.geometryBuffer());
        if (geometryAddress == 0) {
            return;
        }

        for (int c = 0; c < chunkCount && live.size() < maxStructures; c++) {
            VkChunkMirror.Entry entry = entries[c];
            if (entry == null || entry.size < VERTEX_STRIDE || entry.size % VERTEX_STRIDE != 0) {
                continue;
            }
            double dx = chunks[c * 4 + 1] - viewX;
            double dy = chunks[c * 4 + 2] - viewY;
            double dz = chunks[c * 4 + 3] - viewZ;
            if (dx * dx + dy * dy + dz * dz > (double) radius * radius) {
                continue;
            }
            int slot = chunks[c * 4];
            Blas blas = structures.get(slot);
            if (blas == null) {
                blas = new Blas();
                structures.put(slot, blas);
            }
            blas.x = (float) dx;
            blas.y = (float) dy;
            blas.z = (float) dz;
            blas.touchedFrame = frameIndex;
            blas.kind = c < solidCount ? KIND_SOLID
                    : (c < foliageEnd ? KIND_FOLIAGE : KIND_CUTOUT);
            live.add(blas);
            // The version and not just the place and the length. Break one
            // block and a chunk usually keeps its length, and the allocator
            // usually hands back the range it just freed — so the two look
            // identical while the geometry is not, and the structure went on
            // describing a wall that was no longer there. Which is exactly what
            // it looked like: light refusing to reach through a hole.
            boolean stale = blas.structure == 0
                    || blas.sourceOffset != entry.offset
                    || blas.sourceSize != entry.size
                    || blas.sourceVersion != entry.version;
            if (stale && toBuild.size() < budget) {
                blas.sourceOffset = entry.offset;
                blas.sourceSize = entry.size;
                blas.sourceVersion = entry.version;
                toBuild.add(blas);
            }
        }

        dropUntouched(frameIndex);

        // The creatures, if there are any and there is room for one more.
        //
        // Always stale, because a walking animal is different geometry every
        // frame — there is nothing to compare against and no point looking.
        // Counted outside the per-frame build budget: that budget exists to
        // spread the cost of a filling world over several frames, and a shadow
        // that appears on the third frame after the mob does would be worse
        // than none.
        boolean creatures = creatureVertices >= 4 && creatureAddress != 0
                && live.size() < maxStructures;
        // Recorded, because what takes vanilla's round shadow away has to ask
        // whether anything replaced it. Three ways to end up here with nothing
        // in the structure: no creature drawn by us this frame, a run of
        // vertices that turned out not to be contiguous, and a full structure
        // budget. In all three the mob would otherwise stand on nothing at all.
        creaturesInStructure = creatures;
        if (creatures) {
            creatureBlas.x = 0.0f;
            creatureBlas.y = 0.0f;
            creatureBlas.z = 0.0f;
            creatureBlas.touchedFrame = frameIndex;
            // Opaque, and deliberately: a skin is opaque wherever it is drawn
            // at all, and letting a ray through it at random would give a mob
            // a shadow full of holes.
            creatureBlas.kind = KIND_CREATURE;
            creatureBlas.sourceOffset = creatureOffset;
            creatureBlas.sourceSize = creatureVertices * VERTEX_STRIDE;
            live.add(creatureBlas);
        }

        long start = System.nanoTime();
        try (MemoryStack stack = stackPush()) {
            beginCommands(stack);
            for (Blas blas : toBuild) {
                buildOne(stack, blas, geometryAddress);
            }
            if (creatures) {
                buildOne(stack, creatureBlas, creatureAddress);
            }
            int instances = writeInstances(stack);
            if (instances > 0) {
                buildTopLevel(stack, instances);
            }
            lastInstances = instances;
            endAndSubmit(stack);
        }
        lastBuilt = toBuild.size();
        totalBuilt += lastBuilt;
        lastBuildNanos = System.nanoTime() - start;
    }

    /**
     * Frees structures for chunks that were not in this frame's list.
     *
     * The mirror reuses a slot as soon as a chunk is gone, so a structure kept
     * for a slot that moved on describes geometry that is no longer there —
     * which is worse than not having it, because a ray would hit it.
     */
    private void dropUntouched(long frameIndex) {
        java.util.Iterator<java.util.Map.Entry<Integer, Blas>> it = structures.entrySet().iterator();
        while (it.hasNext()) {
            Blas blas = it.next().getValue();
            // Not "missing this frame" but "missing for a while". A chunk
            // leaves the drawn list every time the camera turns past it, and
            // dropping its structure for that costs a full rebuild the moment
            // the camera turns back. The first version did exactly that and
            // spent a hundred rebuilds for every structure it was keeping.
            if (frameIndex - blas.touchedFrame < KEEP_FRAMES) {
                continue;
            }
            retire(blas, frameIndex);
            it.remove();
        }
    }

    /**
     * How long a structure survives out of sight.
     *
     * A couple of seconds at the frame rates this renderer reaches: long enough
     * to cover looking away and back, short enough that a slot the mirror has
     * handed to another chunk cannot be described by a stale structure for
     * anything a player would notice. The slot check in the build loop is what
     * actually catches reuse; this only decides when the memory goes back.
     */
    private static final int KEEP_FRAMES = 300;

    private void buildOne(MemoryStack stack, Blas blas, long geometryAddress) {
        int vertexCount = blas.sourceSize / VERTEX_STRIDE;
        int triangles = vertexCount / 4 * 2;
        if (triangles <= 0) {
            return;
        }

        VkAccelerationStructureGeometryKHR.Buffer geometry =
                VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0)
                .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                // Opaque only where it is true. Marking a leaf quad opaque
                // tells the driver it may stop a ray without asking anybody,
                // and then no amount of work in the shader can put the holes
                // back — the ray never comes back to be asked.
                .flags(blas.kind == KIND_SOLID || blas.kind == KIND_CREATURE
                        ? KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
        geometry.get(0).geometry().triangles()
                .sType(KHRAccelerationStructure
                        .VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                // Offset into the shared buffer rather than a firstVertex on
                // the build range: the address can carry it, and then the
                // indices are read exactly as the draw path reads them.
                .vertexData(it -> it.deviceAddress(geometryAddress + blas.sourceOffset))
                .vertexStride(VERTEX_STRIDE)
                .maxVertex(vertexCount - 1)
                .indexType(VK_INDEX_TYPE_UINT32)
                .indexData(it -> it.deviceAddress(indexAddress()));

        VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        buildInfo.get(0)
                .sType(KHRAccelerationStructure
                        .VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1)
                .pGeometries(geometry);

        VkAccelerationStructureBuildSizesInfoKHR sizes =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                        .sType(KHRAccelerationStructure
                                .VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);
        KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(device(),
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo.get(0), stack.ints(triangles), sizes);

        // A fresh structure every time, and the old one retired rather than
        // rewritten. Building into a structure a previous frame is still
        // tracing through is the same hazard as rewriting the top level, and it
        // has the same ending — the difference is only that a chunk changes far
        // less often than a frame passes.
        retire(blas, buildFrameIndex);
        createBlas(stack, blas, sizes.accelerationStructureSize());
        ensureScratch(sizes.buildScratchSize());

        buildInfo.get(0)
                .dstAccelerationStructure(blas.structure)
                .scratchData(it -> it.deviceAddress(scratchAddress));

        VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(triangles).primitiveOffset(0).firstVertex(0).transformOffset(0);
        PointerBuffer ranges = stack.mallocPointer(1);
        ranges.put(0, range.address());

        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(commandBuffer, buildInfo, ranges);
        // One scratch buffer serves every build in the batch, so each one has
        // to finish before the next begins. Serialising them is slower than
        // giving each its own scratch and costs nothing worth having here:
        // what is being measured is whether the builds are affordable at all,
        // and a batch that is affordable serialised is affordable either way.
        scratchBarrier();
    }

    private void scratchBarrier() {
        try (MemoryStack stack = stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                    .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                            | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
            vkCmdPipelineBarrier(commandBuffer,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    0, barrier, null, null);
        }
    }

    /**
     * Writes one instance per live structure: which structure, and where it is.
     *
     * The transform is the chunk's position relative to the camera, the same
     * number the vertex shader adds to every vertex — so the structures and the
     * drawn world agree by construction rather than by two calculations that
     * have to be kept the same.
     */
    private int writeInstances(MemoryStack stack) {
        int count = live.size();
        if (count == 0) {
            return 0;
        }
        ensureInstanceCapacity(count);
        long at = instanceMapped;
        for (Blas blas : live) {
            if (blas.structure == 0 || blas.address == 0) {
                continue;
            }
            // A row-major 3x4: identity rotation, chunk origin in the last column.
            MemoryUtil.memPutFloat(at, 1.0f);
            MemoryUtil.memPutFloat(at + 4, 0.0f);
            MemoryUtil.memPutFloat(at + 8, 0.0f);
            MemoryUtil.memPutFloat(at + 12, blas.x);
            MemoryUtil.memPutFloat(at + 16, 0.0f);
            MemoryUtil.memPutFloat(at + 20, 1.0f);
            MemoryUtil.memPutFloat(at + 24, 0.0f);
            MemoryUtil.memPutFloat(at + 28, blas.y);
            MemoryUtil.memPutFloat(at + 32, 0.0f);
            MemoryUtil.memPutFloat(at + 36, 0.0f);
            MemoryUtil.memPutFloat(at + 40, 1.0f);
            MemoryUtil.memPutFloat(at + 44, blas.z);
            // instanceCustomIndex 24 bits, mask 8 bits: visible to every ray.
            // The low bits carry what this structure is made of, which is how
            // the shader knows whether it may see through what it just hit.
            MemoryUtil.memPutInt(at + 48, 0xFF000000 | (blas.kind & 0xFFFFFF));
            // shaderBindingTableRecordOffset 24 bits, flags 8 bits.
            MemoryUtil.memPutInt(at + 52, 0);
            MemoryUtil.memPutLong(at + 56, blas.address);
            at += 64;
        }
        return (int) ((at - instanceMapped) / 64);
    }

    private void buildTopLevel(MemoryStack stack, int instances) {
        VkAccelerationStructureGeometryKHR.Buffer geometry =
                VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0)
                .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR);
        geometry.get(0).geometry().instances()
                .sType(KHRAccelerationStructure
                        .VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
                .arrayOfPointers(false)
                .data(it -> it.deviceAddress(instanceAddress));

        VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        buildInfo.get(0)
                .sType(KHRAccelerationStructure
                        .VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1)
                .pGeometries(geometry);

        VkAccelerationStructureBuildSizesInfoKHR sizes =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                        .sType(KHRAccelerationStructure
                                .VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);
        KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(device(),
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo.get(0), stack.ints(instances), sizes);

        ensureTopLevelSlots();
        int slot = activeSlot;
        if (tlas[slot] == 0 || tlasCapacity[slot] < sizes.accelerationStructureSize()) {
            retireTopLevel(slot);
            long[] out = new long[3];
            createStructure(stack, sizes.accelerationStructureSize(),
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, out);
            tlas[slot] = out[0];
            tlasBuffer[slot] = out[1];
            tlasMemory[slot] = out[2];
            tlasCapacity[slot] = sizes.accelerationStructureSize();
        }
        ensureScratch(sizes.buildScratchSize());

        buildInfo.get(0)
                .dstAccelerationStructure(tlas[slot])
                .scratchData(it -> it.deviceAddress(scratchAddress));

        VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(instances).primitiveOffset(0).firstVertex(0).transformOffset(0);
        PointerBuffer ranges = stack.mallocPointer(1);
        ranges.put(0, range.address());
        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(commandBuffer, buildInfo, ranges);
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    private void ensureResources() {
        if (ready) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(ctx.getGraphicsQueueFamily());
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(device(), poolInfo, null, pPool), "vkCreateCommandPool(rt)");
            commandPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device(), allocInfo, pCmd), "vkAllocateCommandBuffers(rt)");
            commandBuffer = new VkCommandBuffer(pCmd.get(0), device());

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pFence = stack.mallocLong(1);
            check(vkCreateFence(device(), fenceInfo, null, pFence), "vkCreateFence(rt)");
            fence = pFence.get(0);

            VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack)
                            .sType(KHRAccelerationStructure
                                    .VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR);
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
                    .pNext(asProps.address());
            org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2(ctx.getPhysicalDevice(), props2);
            scratchAlignment = Math.max(256L, asProps.minAccelerationStructureScratchOffsetAlignment());
        }
        ready = true;
        LOGGER.info("Acceleration structures ready, scratch alignment {}", scratchAlignment);
    }

    private void waitForPreviousBuild() {
        if (!submitted) {
            return;
        }
        // Timed, because it is the only view this class has of what the builds
        // cost the card. Everything else here is the processor writing
        // commands; the work itself happens after the submit, and if it were
        // expensive this is where it would show — the frame after.
        long start = System.nanoTime();
        check(vkWaitForFences(device(), fence, true, 5_000_000_000L), "vkWaitForFences(rt)");
        lastWaitNanos = System.nanoTime() - start;
        check(vkResetFences(device(), fence), "vkResetFences(rt)");
        submitted = false;
    }

    private long lastWaitNanos;

    private void beginCommands(MemoryStack stack) {
        vkResetCommandBuffer(commandBuffer, 0);
        VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer(rt)");
    }

    private void endAndSubmit(MemoryStack stack) {
        // The finished structures, made visible to everything that comes after
        // on this queue.
        //
        // Submission order alone does not give this. Work from separate
        // submissions to one queue may overlap, so without a barrier the
        // terrain pass can begin tracing a structure whose build has not
        // finished — and a traversal of a half-built structure is the failure
        // that does not come back at all.
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        vkCmdPipelineBarrier(commandBuffer,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0, barrier, null, null);
        check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer(rt)");
        VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(commandBuffer));
        check(vkQueueSubmit(ctx.getGraphicsQueue(), submit, fence), "vkQueueSubmit(rt)");
        submitted = true;
    }

    private void createBlas(MemoryStack stack, Blas blas, long size) {
        long[] out = new long[3];
        createStructure(stack, size,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, out);
        blas.structure = out[0];
        blas.buffer = out[1];
        blas.memory = out[2];
        blas.bytes = size;
        structureBytes += size;
        VkAccelerationStructureDeviceAddressInfoKHR info =
                VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                        .sType(KHRAccelerationStructure
                                .VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                        .accelerationStructure(blas.structure);
        blas.address = KHRAccelerationStructure
                .vkGetAccelerationStructureDeviceAddressKHR(device(), info);
    }

    /** Backing buffer plus the structure that lives in it. */
    private void createStructure(MemoryStack stack, long size, int type, long[] out) {
        long[] buffer = new long[2];
        createBuffer(stack, size,
                KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                        | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, buffer);
        VkAccelerationStructureCreateInfoKHR info = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                .buffer(buffer[0])
                .offset(0)
                .size(size)
                .type(type);
        LongBuffer pStructure = stack.mallocLong(1);
        check(KHRAccelerationStructure.vkCreateAccelerationStructureKHR(device(), info, null, pStructure),
                "vkCreateAccelerationStructureKHR");
        out[0] = pStructure.get(0);
        out[1] = buffer[0];
        out[2] = buffer[1];
    }

    /**
     * Scratch buffers replaced while a command buffer was still being recorded.
     *
     * This is what took the device down, three times, over two days. Builds are
     * recorded one after another into one command buffer, and each names the
     * scratch address it will write to. A later chunk in the same batch can be
     * bigger than an earlier one — which is ordinary while chunks are flooding
     * in — and growing the buffer used to free the old one on the spot. The
     * commands already recorded went on naming it, and a build writes to
     * scratch: a write to freed memory, which is exactly what the fault said.
     *
     * Intermittent for the same reason it was hard to see: it needs a bigger
     * chunk to arrive later in a batch than an earlier one, so it happens while
     * the world is filling in and never while standing still.
     *
     * The buffer is kept until the submission that names it has finished, which
     * is the wait at the top of the next update. Nothing is freed early and
     * nothing leaks.
     */
    private final java.util.List<long[]> retiredScratch = new java.util.ArrayList<long[]>();

    private void releaseRetiredScratch() {
        for (int i = 0; i < retiredScratch.size(); i++) {
            long[] pair = retiredScratch.get(i);
            vkDestroyBuffer(device(), pair[0], null);
            vkFreeMemory(device(), pair[1], null);
        }
        retiredScratch.clear();
    }

    private void ensureScratch(long size) {
        long want = size + scratchAlignment;
        if (scratchBuffer != 0 && scratchCapacity >= want) {
            return;
        }
        if (scratchBuffer != 0) {
            // Not destroyed: commands already recorded in this batch write here.
            retiredScratch.add(new long[]{scratchBuffer, scratchMemory});
            scratchBuffer = 0;
            scratchMemory = 0;
            scratchCapacity = 0;
            scratchAddress = 0;
        }
        try (MemoryStack stack = stackPush()) {
            long[] buffer = new long[2];
            createBuffer(stack, want,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, buffer);
            scratchBuffer = buffer[0];
            scratchMemory = buffer[1];
            scratchCapacity = want;
            long base = bufferAddress(scratchBuffer);
            // Rounded up rather than assumed: the alignment a driver demands of
            // scratch is its own number, and a buffer's address only happens to
            // satisfy it.
            scratchAddress = (base + scratchAlignment - 1) / scratchAlignment * scratchAlignment;
        }
    }

    private void ensureInstanceCapacity(int count) {
        if (instanceBuffer != 0 && instanceCapacity >= count) {
            return;
        }
        destroyInstances();
        int want = Math.max(256, Integer.highestOneBit(count) * 2);
        try (MemoryStack stack = stackPush()) {
            long[] buffer = new long[2];
            createBuffer(stack, (long) want * 64,
                    KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, buffer);
            instanceBuffer = buffer[0];
            instanceMemory = buffer[1];
            instanceCapacity = want;
            PointerBuffer ppData = stack.mallocPointer(1);
            check(vkMapMemory(device(), instanceMemory, 0, (long) want * 64, 0, ppData),
                    "vkMapMemory(rt instances)");
            instanceMapped = ppData.get(0);
            instanceAddress = bufferAddress(instanceBuffer);
        }
    }

    private void createBuffer(MemoryStack stack, long size, int usage, int properties, long[] out) {
        VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer pBuffer = stack.mallocLong(1);
        check(vkCreateBuffer(device(), info, null, pBuffer), "vkCreateBuffer(rt)");
        long buffer = pBuffer.get(0);
        VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
        vkGetBufferMemoryRequirements(device(), buffer, req);
        VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(memoryType(stack, req.memoryTypeBits(), properties))
                .pNext(VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                        .flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT)
                        .address());
        LongBuffer pMemory = stack.mallocLong(1);
        check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(rt)");
        check(vkBindBufferMemory(device(), buffer, pMemory.get(0), 0), "vkBindBufferMemory(rt)");
        out[0] = buffer;
        out[1] = pMemory.get(0);
    }

    private int memoryType(MemoryStack stack, int typeBits, int properties) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(ctx.getPhysicalDevice(), memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                    && (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        throw new IllegalStateException("No memory type for ray tracing buffer");
    }

    private long bufferAddress(long buffer) {
        if (buffer == 0) {
            return 0;
        }
        try (MemoryStack stack = stackPush()) {
            VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(buffer);
            return VK12.vkGetBufferDeviceAddress(device(), info);
        }
    }

    /**
     * The creatures of the frame, as one structure over one run of vertices.
     *
     * A mob is animated, so this is rebuilt every frame — and that is the whole
     * cost, because it is one build for every creature on screen rather than
     * one each. The geometry is already there: the same twenty-eight bytes a
     * vertex the sprite pass draws from, positions first, in world axes
     * relative to the camera, which is the space the chunk structures are in
     * too. So the instance needs no transform of its own.
     *
     * What this buys is the shadow. Vanilla draws a round blur under every
     * creature because it has no shadows at all; with the creature in the
     * structure the sun casts a real one, shaped like the animal, from the same
     * ray the terrain already uses. Nothing in the shader changes.
     */
    private long creatureAddress;
    private long creatureOffset;
    private int creatureVertices;
    private volatile boolean creaturesInStructure;
    private final Blas creatureBlas = new Blas();

    /** Whether this frame's build actually put the creatures in a structure. */
    boolean creaturesInStructure() {
        return creaturesInStructure;
    }

    void setCreatureGeometry(long buffer, long byteOffset, int vertexCount) {
        this.creatureAddress = buffer == 0 ? 0L : bufferAddress(buffer);
        this.creatureOffset = byteOffset;
        this.creatureVertices = this.creatureAddress == 0 ? 0 : vertexCount;
    }

    private long indexBufferAddress;

    /** Supplied by the terrain renderer, which owns the shared quad indices. */
    void setIndexBuffer(long buffer) {
        this.indexBufferAddress = bufferAddress(buffer);
    }

    private long indexAddress() {
        return indexBufferAddress;
    }

    // ------------------------------------------------------------------
    // Reporting and teardown
    // ------------------------------------------------------------------

    void appendDiagnostics(StringBuilder sb) {
        sb.append("  ray tracing: ").append(ctx.rayTracingStatus());
        if (broken) {
            sb.append(", FAILED and disabled");
        }
        if (ctx.isRayTracingEnabled()) {
            sb.append("; ").append(structures.size()).append(" chunk structures (")
                    .append(structureBytes / (1024 * 1024)).append(" MiB), ")
                    .append(lastInstances).append(" in the scene, ")
                    .append(lastBuilt).append(" rebuilt last frame of ").append(totalBuilt)
                    .append(" total, ")
                    .append(String.format("%.2f", lastBuildNanos / 1e6)).append(" ms to record, ")
                    .append(String.format("%.2f", lastWaitNanos / 1e6))
                    .append(" ms waiting for the card to finish the last batch");
            // Named separately from the chunks, because it answers a different
            // question: whether creatures are in the structure at all. A
            // shadow that is missing under a cow and a structure that was never
            // built look identical from outside, and this is the line that
            // tells them apart.
            sb.append("; creatures ")
                    .append(creatureVertices > 0
                            ? creatureVertices / 4 + " quads in one structure"
                            : "not in the structure");
        }
        sb.append('\n');
    }

    static int radiusBlocks() {
        return intProperty("vulkanmod112.rayTracingRadius", 96);
    }

    private static int buildsPerFrame() {
        return intProperty("vulkanmod112.rayTracingBuilds", 32);
    }

    private static int maxStructures() {
        return intProperty("vulkanmod112.rayTracingChunks", 2048);
    }

    private static int intProperty(String name, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void destroyBlas(Blas blas) {
        if (blas.structure == 0) {
            return;
        }
        KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device(), blas.structure, null);
        vkDestroyBuffer(device(), blas.buffer, null);
        vkFreeMemory(device(), blas.memory, null);
        structureBytes -= blas.bytes;
        blas.structure = 0;
        blas.buffer = 0;
        blas.memory = 0;
        blas.address = 0;
        blas.bytes = 0;
        blas.sourceSize = 0;
    }

    private void ensureTopLevelSlots() {
        if (tlas != null && tlas.length >= slotCount) {
            return;
        }
        tlas = new long[slotCount];
        tlasBuffer = new long[slotCount];
        tlasMemory = new long[slotCount];
        tlasCapacity = new long[slotCount];
    }

    private void retireTopLevel(int slot) {
        if (tlas[slot] == 0) {
            return;
        }
        Retired entry = new Retired();
        entry.structure = tlas[slot];
        entry.buffer = tlasBuffer[slot];
        entry.memory = tlasMemory[slot];
        entry.frame = buildFrameIndex;
        retired.add(entry);
        tlas[slot] = 0;
        tlasBuffer[slot] = 0;
        tlasMemory[slot] = 0;
        tlasCapacity[slot] = 0;
    }

    private void retire(Blas blas, long frameIndex) {
        if (blas.structure == 0) {
            return;
        }
        Retired entry = new Retired();
        entry.structure = blas.structure;
        entry.buffer = blas.buffer;
        entry.memory = blas.memory;
        entry.bytes = blas.bytes;
        entry.frame = frameIndex;
        retired.add(entry);
        blas.structure = 0;
        blas.buffer = 0;
        blas.memory = 0;
        blas.address = 0;
        blas.bytes = 0;
        blas.sourceSize = 0;
    }

    /**
     * Frees what no frame can still be reading.
     *
     * The margin is the number of frames the renderer runs ahead plus two: one
     * because a frame is submitted before the next begins, one because this
     * class submits its own work on a queue of its own reckoning. Cheap
     * insurance against the only failure mode here that is fatal rather than
     * visible.
     */
    private void releaseRetired(long frameIndex) {
        if (retired.isEmpty()) {
            return;
        }
        long safe = frameIndex - (slotCount + 2);
        java.util.Iterator<Retired> it = retired.iterator();
        while (it.hasNext()) {
            Retired entry = it.next();
            if (entry.frame > safe) {
                continue;
            }
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device(), entry.structure, null);
            vkDestroyBuffer(device(), entry.buffer, null);
            vkFreeMemory(device(), entry.memory, null);
            structureBytes -= entry.bytes;
            it.remove();
        }
    }

    private void destroyTopLevel() {
        if (tlas == null) {
            return;
        }
        for (int slot = 0; slot < tlas.length; slot++) {
            if (tlas[slot] == 0) {
                continue;
            }
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device(), tlas[slot], null);
            vkDestroyBuffer(device(), tlasBuffer[slot], null);
            vkFreeMemory(device(), tlasMemory[slot], null);
            tlas[slot] = 0;
        }
    }

    private void destroyScratch() {
        if (scratchBuffer == 0) {
            return;
        }
        vkDestroyBuffer(device(), scratchBuffer, null);
        vkFreeMemory(device(), scratchMemory, null);
        scratchBuffer = 0;
        scratchMemory = 0;
        scratchCapacity = 0;
        scratchAddress = 0;
    }

    private void destroyInstances() {
        if (instanceBuffer == 0) {
            return;
        }
        vkUnmapMemory(device(), instanceMemory);
        vkDestroyBuffer(device(), instanceBuffer, null);
        vkFreeMemory(device(), instanceMemory, null);
        instanceBuffer = 0;
        instanceMemory = 0;
        instanceMapped = 0;
        instanceCapacity = 0;
        instanceAddress = 0;
    }

    void destroy() {
        if (!ready) {
            return;
        }
        vkDeviceWaitIdle(device());
        for (Blas blas : structures.values()) {
            destroyBlas(blas);
        }
        structures.clear();
        // Not in that map — it is one structure rebuilt every frame rather than
        // one per chunk slot — so it is freed by name or not at all.
        destroyBlas(creatureBlas);
        // The device is idle here, so nothing can still be reading these.
        for (Retired entry : retired) {
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device(), entry.structure, null);
            vkDestroyBuffer(device(), entry.buffer, null);
            vkFreeMemory(device(), entry.memory, null);
        }
        retired.clear();
        live.clear();
        destroyTopLevel();
        releaseRetiredScratch();
        destroyScratch();
        destroyInstances();
        vkDestroyFence(device(), fence, null);
        vkDestroyCommandPool(device(), commandPool, null);
        ready = false;
        submitted = false;
    }

    private static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(call + " failed with VkResult " + result);
        }
    }
}
