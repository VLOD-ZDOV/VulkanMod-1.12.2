package net.vulkanmod112.vkimpl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * VRAM mirror of the game's world geometry (stage 3.2).
 *
 * Every VBO upload the game performs is copied into a Vulkan vertex buffer
 * keyed by a dense mirror slot, so the terrain renderer can draw the
 * exact same geometry without touching OpenGL.
 *
 * All chunks are suballocations of one device-local buffer, and every upload
 * passes through one host-visible staging ring. Uploads made while Minecraft
 * rebuilds chunks are recorded together and submitted once immediately before
 * the terrain frame, which avoids the very expensive PCIe reads that using
 * host-visible memory as a vertex buffer would cause.
 *
 * Note what this mirror costs, because it is not free: the world's geometry
 * exists twice, once in the game's own GL buffers and once here. At high
 * render distances that doubling is the dominant memory cost of the mod.
 */
final class VkChunkMirror {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/ChunkMirror");

    /**
     * A chunk's slice of the shared geometry buffer. Nothing else: uploads go
     * through one shared staging ring, so a mirrored chunk costs a range in
     * VRAM and this object, not a Vulkan allocation of its own.
     */
    static final class Entry {
        long offset;
        int capacity;
        int size;
        /**
         * How many bytes at the start of this range face straight down, and how
         * many at the end face straight up.
         *
         * Zero for geometry that was not grouped, which is the safe reading:
         * the draw then covers the whole range exactly as it always did.
         */
        /**
         * How many down-facing quads of this range lie below each of the
         * section's seventeen level boundaries, and how many up-facing ones lie
         * at or above it — the first seventeen entries and the second.
         *
         * A camera knows from these exactly how much of the front and the back
         * of the range it cannot see, and both at once. One conservative pair of
         * heights was tried instead and answered for a seventh of the geometry
         * where this answers for a fifth: a single overhang near the top of a
         * section made the answer no for everything below it.
         *
         * {@link #grouped} rather than a zero test, because a chunk with no
         * down-facing quads at all is a legitimate answer and not "unsorted".
         */
        final int[] shelves = new int[34];
        boolean grouped;
        /**
         * Bumped on every upload into this slot.
         *
         * Where the chunk lives and how long it is are not enough to tell that
         * it changed: breaking one block usually leaves a chunk the same length
         * and the allocator usually hands back the range it just freed, so the
         * two look identical while the contents are not. Anything caching work
         * derived from this geometry — the acceleration structures do — has to
         * watch this instead.
         */
        long version;
    }

    /** Lookup for the terrain renderer; null when that slot has no mirror. */
    synchronized Entry find(int slot) {
        return entries.get(slot);
    }

    /**
     * Resolves a whole layer's chunks at once, writing {@code out[i]} for each
     * chunk in the packed array (null where the game VBO has no mirror).
     *
     * The draw loop used to call {@link #find} per chunk. At render distance 64
     * that is tens of thousands of monitor acquisitions per frame, on the
     * thread that has to finish the frame; taking the lock once per layer costs
     * the same as one of them.
     */
    synchronized void findAll(int[] chunks, int chunkCount, Entry[] out) {
        for (int i = 0; i < chunkCount; i++) {
            out[i] = entries.get(chunks[i * 4]);
        }
    }

    /**
     * Largest mirrored payload in bytes; sizes the shared quad index buffer.
     *
     * This is a high-water mark rather than the current maximum, updated as
     * chunks are uploaded. The index buffer only ever grows, so the two are
     * interchangeable there — and scanning every mirrored chunk once a frame
     * to compute the exact value was pure waste at high render distances.
     */
    synchronized int maxEntrySize() {
        return largestEntrySize;
    }

    /** 0 while no chunk has ever carried materials; see {@link #materialBuffer}. */
    synchronized long materialBuffer() {
        return materialBuffer;
    }

    synchronized long geometryBuffer() {
        return geometryBuffer;
    }

    private final VulkanContextImpl ctx;
    private final EntryTable entries = new EntryTable();
    /** High-water mark behind {@link #maxEntrySize()}. */
    private int largestEntrySize;

    /**
     * Dense slot → Entry table.
     *
     * This replaced an open-addressed hash map keyed by GL buffer name. The
     * names are handed out by the driver, are not dense, and at render distance
     * 64 the session accumulates hundreds of thousands of them, so resolving a
     * visible chunk meant hashing into a table far larger than the live grid
     * and landing wherever the probe took it. Slots come from
     * {@code ChunkSlots}, which recycles them, so the table stays about the
     * size of the chunk grid and a lookup is an array index.
     */
    private static final class EntryTable {
        private Entry[] values = new Entry[4096];
        private int size;

        int size() {
            return size;
        }

        Entry[] values() {
            return values;
        }

        Entry get(int slot) {
            Entry[] table = values;
            return slot >= 0 && slot < table.length ? table[slot] : null;
        }

        void put(int slot, Entry value) {
            if (slot >= values.length) {
                int length = values.length;
                while (slot >= length) {
                    length *= 2;
                }
                Entry[] grown = new Entry[length];
                System.arraycopy(values, 0, grown, 0, values.length);
                values = grown;
            }
            if (values[slot] == null) {
                size++;
            }
            values[slot] = value;
        }

        Entry remove(int slot) {
            if (slot < 0 || slot >= values.length) {
                return null;
            }
            Entry previous = values[slot];
            if (previous != null) {
                values[slot] = null;
                size--;
            }
            return previous;
        }

        void clear() {
            java.util.Arrays.fill(values, null);
            size = 0;
        }
    }

    /**
     * A chunk copied into staging by a builder thread, waiting for the render
     * thread to record the copy command for it.
     *
     * The builder threads may not touch Vulkan at all — the queue and the
     * command pool are not thread-safe, and the render thread owns both. So a
     * builder does only what needs no driver call: reserve a range and memcpy
     * into already-mapped memory. Everything that allocates, records or submits
     * stays where it was.
     */
    private static final class Staged {
        long srcOffset;
        int size;
        /** Down-facing quads below each level, then up-facing at or above it. */
        final int[] shelves = new int[34];
        boolean grouped;
        /**
         * This copy's materials, one byte a vertex, in this copy's own order.
         *
         * Carried with the geometry rather than looked up when it is written,
         * and that is the fix for a real defect: the game's own upload calls
         * itself a second time on the render thread, so the hook that publishes
         * material runs fires twice per chunk. The second firing replaced the
         * runs the builder thread had already put in the new order with the
         * original ones, and the materials then described a chunk whose quads
         * had moved. Nothing pairs runs with geometry except being carried
         * together, so now they are.
         */
        byte[] material = new byte[0];
        int materialVertices;
    }

    /**
     * Guards the builder half of the staging ring. Deliberately not the
     * mirror's own monitor: the render thread holds that one several times a
     * frame (every lookup of a visible chunk goes through it), and builders
     * blocking it would move the cost back onto the thread this is meant to
     * relieve. Builders never take the mirror monitor, so there is no cycle.
     */
    private final Object workerLock = new Object();
    private final java.util.HashMap<Integer, Staged> staged = new java.util.HashMap<>();
    /**
     * Bumped whenever a slot is released. A builder reads it before its copy
     * and publishes only if it still matches, which is what makes releasing a
     * slot mid-copy safe: the copy is simply dropped.
     *
     * Without this the builder's publish happens after {@code release} has
     * already searched for it, so a slot recycled to an unrelated chunk could
     * inherit the previous one's staged bytes — and the size check alone would
     * not catch it, because chunk buffers cluster around the same few sizes.
     */
    private int[] slotEpoch = new int[4096];
    /** Builders own [workerRegionStart, workerRegionStart + workerRegionSize). */
    private long workerRegionStart;
    private long workerRegionSize;
    private long workerHead;
    /** Builders inside a memcpy right now; the region must not be reset under them. */
    private int workerInFlight;
    private long workerStaged;
    private long workerRejected;

    private static final class Retired {
        final Entry entry;
        final long frameStamp;

        Retired(Entry entry, long frameStamp) {
            this.entry = entry;
            this.frameStamp = frameStamp;
        }
    }

    /**
     * A geometry buffer replaced by a larger one, waiting for every frame that
     * could still name it to finish.
     *
     * Draws bind the buffer handle by value at record time, so a frame already
     * recorded keeps referring to the old handle even after the field has been
     * reassigned. Destroying it at replacement time would be a use-after-free
     * on submit — the same reason mirrored ranges are retired rather than freed.
     */
    private static final class RetiredBuffer {
        final long buffer;
        final long memory;
        final long frameStamp;

        RetiredBuffer(long buffer, long memory, long frameStamp) {
            this.buffer = buffer;
            this.memory = memory;
            this.frameStamp = frameStamp;
        }
    }

    private final List<RetiredBuffer> retiredBuffers = new ArrayList<RetiredBuffer>();

    /**
     * A hole in the geometry buffer. Kept sorted by offset so neighbours can
     * be merged; capacity is a long because a fully merged buffer can exceed
     * what an int holds.
     */
    private static final class FreeRange {
        final long offset;
        final long capacity;

        FreeRange(long offset, long capacity) {
            this.offset = offset;
            this.capacity = capacity;
        }
    }

    /**
     * Buffers replaced or released while a terrain frame may still be
     * executing on the GPU. Destroying them immediately is a GPU
     * use-after-free (VK_ERROR_DEVICE_LOST); each is stamped with the current
     * terrain frame number and freed once every frame that could reference it
     * has completed ({@link #flushRetired}).
     */
    private final List<Retired> retired = new ArrayList<Retired>();
    private long frameStamp;
    private long totalBytes;

    /**
     * What the card has committed to chunk geometry — the allocation, not the
     * part of it that currently holds something.
     *
     * The distinction is the whole point. Video memory is taken by the
     * allocation: a buffer of a gigabyte with six hundred megabytes of world in
     * it occupies a gigabyte, and the driver's own figures agree. Reporting the
     * filled part instead is how an estimate came out a third low against
     * nvidia-smi.
     */
    synchronized long geometryBytes() {
        return Math.max(geometryCapacity, totalBytes) + stagingCapacity;
    }
    private long uploadCount;

    private long geometryBuffer;
    private long geometryMemory;
    private long geometryCapacity;
    private long nextGeometryOffset;
    /** Once a session: the second complaint says nothing the first did not. */
    private final List<FreeRange> freeRanges = new ArrayList<FreeRange>();
    /**
     * How much VRAM the geometry buffer is allowed to take before growth turns
     * cautious. Resolved once from the settings screen, or from the amount of
     * device-local memory the GPU reports when left on automatic.
     *
     * This is deliberately not a hard wall: refusing to store geometry would
     * make chunks disappear. Above the budget the buffer still grows, just in
     * fixed steps instead of doubling — so a 4 GiB card ends up with a snug
     * buffer and a 16 GiB one skips the regrowth stalls entirely. Each growth
     * stops the GPU and re-uploads every mirrored chunk, which is exactly the
     * stutter a larger starting size buys away.
     */
    private long geometryBudget;
    private long initialGeometryCapacity;

    /**
     * One byte per vertex saying what that vertex is made of, in a buffer that
     * runs alongside the geometry rather than inside it.
     *
     * The vertex is the game's own 28 bytes and is mirrored unchanged, so there
     * is nowhere in it to put this. A second buffer indexed by the same vertex
     * number costs no allocator of its own: every suballocation in the geometry
     * buffer begins on a vertex boundary, so a chunk's materials live at its
     * offset divided by the stride, and the indirect draw's vertexOffset — which
     * Vulkan applies to every bound vertex buffer, not just the first — lands on
     * them without anything being told twice where the chunk is.
     *
     * It exists only once materials are actually being recorded. Nothing is
     * allocated for a feature that is switched off, and while it is missing the
     * shaders are told so and read nothing.
     */
    private long materialBuffer;
    private long materialMemory;
    private long materialCapacity;

    /**
     * Material runs handed over by the thread that built a chunk, waiting for
     * the geometry they describe.
     *
     * They arrive first: the game hands a finished chunk to the render thread,
     * and the layer's buffer is uploaded some time after the loop that produced
     * it has moved on. Indexed by slot, like the epochs, because slots are dense
     * and this is read for every upload.
     */
    private final Object materialLock = new Object();
    private int[][] slotRuns = new int[1024][];
    private int[] slotRunCount = new int[1024];
    private long materialsStaged;
    /** How the run permutation went, per grouped copy; all three are printed. */
    private long runsPermuted;
    private long runsAbsent;
    private long materialsApplied;
    private long materialsMissing;
    private long materialsKept;

    private long uploadCommandPool;
    private VkCommandBuffer uploadCommandBuffer;
    private long uploadFence;
    private boolean uploadsRecording;
    private boolean uploadsSubmitted;

    /**
     * One host-visible ring every chunk upload passes through, instead of a
     * permanently mapped staging buffer per chunk.
     *
     * The old scheme cost a {@code vkAllocateMemory}, a {@code vkCreateBuffer}
     * and a {@code vkMapMemory} for every mirrored chunk, and kept the pinned
     * copy alive for as long as the chunk existed — as much pinned system
     * memory as the whole world took in VRAM. At render distance 12 that was
     * already 4321 allocations; at 64 it is tens of thousands, which is both
     * far past the 4096 the Vulkan spec guarantees and enough pinned memory to
     * matter on its own.
     *
     * Writes advance {@link #stagingHead}. When a write would run off the end,
     * the recorded copies are submitted and waited on before the head returns
     * to zero, so nothing is overwritten while the GPU is still reading it.
     */
    /**
     * Sized against the wrap, not against a single upload. Wrapping blocks the
     * render thread until the GPU has drained the ring, so the interval between
     * wraps is what matters: at roughly 50 KiB a chunk, 96 MiB is about 2000
     * uploads of headroom. The cost is host memory that is never touched
     * again once a chunk has been copied, which is far cheaper than it used to
     * be — this replaced a pinned copy per chunk, not nothing.
     */
    private static final long STAGING_RING_MIN = 96L * 1024L * 1024L;
    private long stagingBuffer;
    private long stagingMemory;
    private long stagingMappedAddress;
    private long stagingCapacity;
    private long stagingHead;
    private long stagingWraps;

    /**
     * Copy regions waiting to be recorded, all from the ring into the geometry
     * buffer.
     *
     * They used to be recorded one {@code vkCmdCopyBuffer} at a time, which
     * meant a stack frame and a native call per chunk. Turning the camera at a
     * high render distance uploads chunks in bursts of dozens per frame, and
     * one call carrying every region costs the same as one carrying a single
     * one. Off-heap and reused, so this adds no allocation of its own.
     */
    private VkBufferCopy.Buffer pendingCopies;
    private int pendingCopyCount;

    private void queueCopy(long srcOffset, long dstOffset, int size) {
        if (pendingCopies == null) {
            pendingCopies = VkBufferCopy.calloc(256);
        } else if (pendingCopyCount == pendingCopies.capacity()) {
            VkBufferCopy.Buffer grown = VkBufferCopy.calloc(pendingCopies.capacity() * 2);
            MemoryUtil.memCopy(MemoryUtil.memAddress(pendingCopies), MemoryUtil.memAddress(grown),
                    (long) pendingCopyCount * VkBufferCopy.SIZEOF);
            pendingCopies.free();
            pendingCopies = grown;
        }
        pendingCopies.get(pendingCopyCount).srcOffset(srcOffset).dstOffset(dstOffset).size(size);
        pendingCopyCount++;
    }

    /**
     * Records the queued regions. Must run before anything that submits the
     * command buffer, replaces the geometry buffer or rewinds the ring —
     * every queued region names offsets in whatever is current right now.
     */
    private void emitPendingCopies() {
        if (pendingCopyCount != 0) {
            pendingCopies.position(0).limit(pendingCopyCount);
            vkCmdCopyBuffer(uploadCommandBuffer, stagingBuffer, geometryBuffer, pendingCopies);
            pendingCopies.limit(pendingCopies.capacity());
            pendingCopyCount = 0;
        }
        if (pendingMaterialCopyCount != 0) {
            // A separate call because the destination is a different buffer,
            // not because the regions are different in kind: they come from the
            // same ring and are queued in the same breath as the geometry.
            pendingMaterialCopies.position(0).limit(pendingMaterialCopyCount);
            vkCmdCopyBuffer(uploadCommandBuffer, stagingBuffer, materialBuffer, pendingMaterialCopies);
            pendingMaterialCopies.limit(pendingMaterialCopies.capacity());
            pendingMaterialCopyCount = 0;
        }
    }

    private VkBufferCopy.Buffer pendingMaterialCopies;
    private int pendingMaterialCopyCount;

    private void queueMaterialCopy(long srcOffset, long dstOffset, int size) {
        if (pendingMaterialCopies == null) {
            pendingMaterialCopies = VkBufferCopy.calloc(256);
        } else if (pendingMaterialCopyCount == pendingMaterialCopies.capacity()) {
            VkBufferCopy.Buffer grown = VkBufferCopy.calloc(pendingMaterialCopies.capacity() * 2);
            MemoryUtil.memCopy(MemoryUtil.memAddress(pendingMaterialCopies),
                    MemoryUtil.memAddress(grown),
                    (long) pendingMaterialCopyCount * VkBufferCopy.SIZEOF);
            pendingMaterialCopies.free();
            pendingMaterialCopies = grown;
        }
        pendingMaterialCopies.get(pendingMaterialCopyCount)
                .srcOffset(srcOffset).dstOffset(dstOffset).size(size);
        pendingMaterialCopyCount++;
    }

    /** Called by the terrain renderer at the start of each frame. */
    /**
     * The thread that stamps frames, remembered so the assumption below can be
     * checked instead of believed.
     *
     * Everything the deferred release does is correct on one condition: that
     * {@code upload()} and {@code release()} run on the same thread that calls
     * this. A buffer retired here is stamped with the number of the frame being
     * recorded, and is freed once that frame's fence has passed — which is only
     * a safe statement about a buffer the render thread itself stopped using.
     * From another thread the stamp could be one frame out in either direction,
     * and the failure would be a use-after-free seen as a device loss with
     * nothing pointing at it.
     *
     * The two callers are documented "client thread only" on the bridge, and in
     * this game that is the render thread. Nothing says why it matters here,
     * and byte copying has already moved off this thread once
     * ({@code stageFromWorker}); whoever moves the rest deserves a warning
     * rather than three days.
     */
    private Thread stampingThread;
    private boolean threadWarned;

    synchronized void setFrameStamp(long stamp) {
        this.stampingThread = Thread.currentThread();
        this.frameStamp = stamp;
    }

    /** Says so, once, if the frame-stamp assumption has stopped being true. */
    private void checkStampingThread(String what) {
        if (threadWarned || stampingThread == null
                || stampingThread == Thread.currentThread()) {
            return;
        }
        threadWarned = true;
        LOGGER.error("{} came from {} while frames are stamped on {}. Deferred freeing of "
                + "geometry is stamped with the frame being recorded and is only sound from the "
                + "thread that records it; from anywhere else a buffer can be freed while a "
                + "frame still names it.", what, Thread.currentThread().getName(),
                stampingThread.getName());
    }

    /**
     * What the geometry buffer needs on top of being a vertex buffer before an
     * acceleration structure can be built from it.
     */
    private static final int RAY_TRACING_BUFFER_USAGE =
            org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                    | org.lwjgl.vulkan.KHRAccelerationStructure
                            .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;

    /** Fixed for the life of the device: the flags are creation-time only. */
    private final boolean rayTracing;

    VkChunkMirror(VulkanContextImpl ctx) {
        this.ctx = ctx;
        this.rayTracing = ctx.isRayTracingEnabled();
    }

    private VkDevice device() {
        return ctx.getDevice();
    }

    /**
     * Copies a freshly built chunk into staging from the thread that built it.
     *
     * The game hands geometry to the render thread through a queue, and the
     * render thread then spends a hard per-frame budget draining it — a quarter
     * of the frame, minus what the frame already spent. Mirroring inside that
     * budget made us spend it twice as fast as vanilla alone would: once for
     * the GL buffer, once for this copy. The copy itself needs no OpenGL and no
     * Vulkan call, only mapped memory, so it does not belong there.
     *
     * Returns false whenever the fast path is not available — no ring yet, no
     * slot yet, or the builder region is full. The caller then does nothing and
     * the render thread mirrors the chunk exactly as before, so a refusal costs
     * one missed optimisation and never correctness.
     */
    boolean stageFromWorker(int slot, ByteBuffer data) {
        int sourceSize = data.remaining();
        if (sourceSize <= 0 || slot < 0) {
            return false;
        }
        // What it will occupy once packed, which is what everything downstream
        // measures: the range reserved here, the staged record, and the copy
        // the render thread queues out of it.
        int size = VertexLayout.packedSize(sourceSize);
        long aligned = (size + 15L) & ~15L;
        long offset;
        int epoch;
        synchronized (workerLock) {
            if (stagingBuffer == 0 || workerHead + aligned > workerRegionSize) {
                workerRejected++;
                return false;
            }
            offset = workerRegionStart + workerHead;
            workerHead += aligned;
            // Held across the copy so the region cannot be reset under us.
            workerInFlight++;
            epoch = epochOf(slot);
        }
        long mapped = stagingMappedAddress;
        boolean copied = false;
        boolean published = false;
        try {
            groupedCopy(slot, MemoryUtil.memAddress(data), mapped + offset, sourceSize,
                    workerGrouping.get());
            copied = true;
        } finally {
            // No return from here: the in-flight count has to be given back
            // even if the copy threw, but returning inside a finally would
            // swallow that exception on the way out.
            synchronized (workerLock) {
                workerInFlight--;
                // A changed epoch means the slot was released while we were
                // copying. The bytes are meaningless now and the slot may
                // already belong to another chunk, so the copy is dropped.
                if (copied && epochOf(slot) == epoch) {
                    Staged entry = staged.get(slot);
                    if (entry == null) {
                        entry = new Staged();
                        staged.put(slot, entry);
                    }
                    entry.srcOffset = offset;
                    entry.size = size;
                    Grouping grouping = workerGrouping.get();
                    entry.grouped = grouping.grouped;
                    entry.materialVertices = 0;
                    if (grouping.grouped) {
                        System.arraycopy(grouping.counts, VertexLayout.DOWN_TABLE,
                                entry.shelves, 0, 34);
                        int carried = grouping.permutedVertices;
                        if (carried > 0) {
                            if (entry.material.length < carried) {
                                entry.material = new byte[Integer.highestOneBit(carried) * 2];
                            }
                            System.arraycopy(grouping.permuted, 0, entry.material, 0, carried);
                            entry.materialVertices = carried;
                        }
                    }
                    workerStaged++;
                    published = true;
                } else {
                    workerRejected++;
                }
            }
        }
        return published;
    }

    /**
     * Scratch for one grouped copy, kept per thread so a copy allocates nothing.
     *
     * One byte per quad and one byte per quad again for the material walk, both
     * grown to the largest chunk layer that thread has seen. A builder thread
     * copies one layer at a time, so there is no sharing to guard.
     */
    private static final class Grouping {
        byte[] quadShelf = new byte[4096];
        byte[] quadMaterial = new byte[4096];
        int[] quadTarget = new int[4096];
        final int[] counts = new int[VertexLayout.COUNTS];
        boolean grouped;
        /** One material a vertex, already in the order the quads were put in. */
        byte[] permuted = new byte[16384];
        int permutedVertices;

        byte[] fitMaterial(int vertices) {
            if (quadMaterial.length < vertices) {
                int length = quadMaterial.length;
                while (length < vertices) {
                    length *= 2;
                }
                quadMaterial = new byte[length];
            }
            if (permuted.length < vertices) {
                int length = permuted.length;
                while (length < vertices) {
                    length *= 2;
                }
                permuted = new byte[length];
            }
            return quadMaterial;
        }

        void fit(int quads) {
            if (quadShelf.length < quads) {
                int length = quadShelf.length;
                while (length < quads) {
                    length *= 2;
                }
                quadShelf = new byte[length];
                quadMaterial = new byte[length];
                quadTarget = new int[length];
            }
        }
    }

    private final ThreadLocal<Grouping> workerGrouping = new ThreadLocal<Grouping>() {
        @Override
        protected Grouping initialValue() {
            return new Grouping();
        }
    };

    /** The render thread's own, for the path where it does the copy itself. */
    private final Grouping renderGrouping = new Grouping();

    /**
     * Copies one chunk layer, sorting its quads by facing when that is allowed.
     *
     * Not allowed for the translucent layer, and the reason is the one thing
     * this must never break: those quads arrive sorted back to front, and the
     * order is what makes water in front of glass look like water in front of
     * glass. Nothing here can tell which layer it is holding, so the caller's
     * geometry is grouped only when the setting says so and the layer has no
     * ordering to lose — see {@link #groupingWanted(int)}.
     */
    private void groupedCopy(int slot, long source, long destination, int sourceBytes,
                             Grouping grouping) {
        grouping.grouped = false;
        if (!groupingWanted(slot)) {
            VertexLayout.copy(source, destination, sourceBytes);
            return;
        }
        int quads = sourceBytes / (VertexLayout.SOURCE_STRIDE * 4);
        grouping.fit(Math.max(1, quads));
        if (!VertexLayout.copyGrouped(source, destination, sourceBytes,
                grouping.quadShelf, grouping.quadTarget, grouping.counts)) {
            return;
        }
        grouping.grouped = true;
        permuteMaterials(slot, grouping, quads);
    }

    /**
     * Rewrites this slot's material runs into the order the quads were just put
     * in.
     *
     * The runs say "up to this vertex, this material", so a permutation of the
     * quads makes every one of them wrong. They are expanded to one material a
     * quad, walked through the same three cursors the copy used, and encoded
     * again — which splits each run into at most three, so a layer's eight or so
     * runs become at most twenty-four.
     *
     * Done here rather than where the runs are consumed because only this side
     * knows the permutation, and keeping it alive until the render thread asked
     * for it would mean an array per slot that outlives the copy.
     */
    private void permuteMaterials(int slot, Grouping grouping, int quads) {
        grouping.permutedVertices = 0;
        int vertices = quads * 4;
        byte[] material = grouping.fitMaterial(vertices);
        synchronized (materialLock) {
            if (slot >= slotRunCount.length) {
                runsAbsent++;
                return;
            }
            int count = slotRunCount[slot];
            int[] runs = slotRuns[slot];
            if (count <= 0 || runs == null) {
                runsAbsent++;
                return;
            }
            runsPermuted++;
            // Expanded per vertex, not per quad: a run boundary is stated in
            // vertices, and dividing it by four would quietly round a boundary
            // that did not land on a quad onto the wrong side of one.
            int written = 0;
            for (int r = 0; r < count && written < vertices; r++) {
                int end = Math.min(runs[r * 2], vertices);
                byte value = (byte) runs[r * 2 + 1];
                while (written < end) {
                    material[written++] = value;
                }
            }
            while (written < vertices) {
                material[written++] = 0;
            }
        }
        // Moved by the very indices the copy wrote to. Working these out again
        // from the shelves would be a second implementation of the same
        // arithmetic, and the two drifting apart would show as materials on the
        // wrong blocks — which nobody would trace back here.
        int[] target = grouping.quadTarget;
        byte[] out = grouping.permuted;
        for (int q = 0; q < quads; q++) {
            int to = target[q] * 4;
            int from = q * 4;
            out[to] = material[from];
            out[to + 1] = material[from + 1];
            out[to + 2] = material[from + 2];
            out[to + 3] = material[from + 3];
        }
        grouping.permutedVertices = vertices;
    }

    /**
     * Which slots hold geometry that may be sorted by facing.
     *
     * Everything except the translucent layer, and that exception is the whole
     * reason this exists: those quads arrive sorted back to front by the game
     * and the order is the effect. Stamped by the upload hook, which is the last
     * place that still knows which layer a slot is for.
     */
    private volatile boolean[] slotGroupable = new boolean[1024];

    void noteLayer(int slot, boolean translucent) {
        if (slot < 0) {
            return;
        }
        boolean[] flags = slotGroupable;
        if (slot >= flags.length) {
            synchronized (workerLock) {
                flags = slotGroupable;
                if (slot >= flags.length) {
                    int length = flags.length;
                    while (slot >= length) {
                        length *= 2;
                    }
                    boolean[] grown = new boolean[length];
                    System.arraycopy(flags, 0, grown, 0, flags.length);
                    slotGroupable = grown;
                    flags = grown;
                }
            }
        }
        flags[slot] = !translucent;
    }

    private boolean groupingWanted(int slot) {
        if (!GROUP_FACINGS || slot < 0) {
            return false;
        }
        boolean[] flags = slotGroupable;
        return slot < flags.length && flags[slot];
    }

    /**
     * Read once, for the same reason the packed layout is: it decides what goes
     * into the geometry buffer, and a buffer holding two orders at once is a
     * world drawn wrong in exactly the chunks that were built before the switch.
     */
    private static final boolean GROUP_FACINGS =
            Boolean.getBoolean("vulkanmod112.groupFacings");

    /** Caller must hold {@link #materialLock}. */
    private int[] materialOrderScratch = new int[4096];

    private int[] runsScratch(int quads) {
        if (materialOrderScratch.length < quads) {
            int length = materialOrderScratch.length;
            while (length < quads) {
                length *= 2;
            }
            materialOrderScratch = new int[length];
        }
        return materialOrderScratch;
    }

    /** Caller must hold {@link #workerLock}. */
    private int epochOf(int slot) {
        return slot < slotEpoch.length ? slotEpoch[slot] : 0;
    }

    /** Caller must hold {@link #workerLock}. */
    private void bumpEpoch(int slot) {
        if (slot >= slotEpoch.length) {
            int length = slotEpoch.length;
            while (slot >= length) {
                length *= 2;
            }
            int[] grown = new int[length];
            System.arraycopy(slotEpoch, 0, grown, 0, slotEpoch.length);
            slotEpoch = grown;
        }
        slotEpoch[slot]++;
    }

    /**
     * Takes the material runs for a chunk layer, before its geometry arrives.
     *
     * The array belongs to the caller and is reused, so it is copied here. Runs
     * are pairs — one past the last vertex, and what that stretch is made of —
     * and there are eight or so of them for a chunk layer, so the copy is a few
     * dozen bytes on a path that already moves tens of kilobytes.
     */
    void stageMaterials(int slot, int[] runs, int runCount) {
        if (slot < 0 || runCount <= 0) {
            return;
        }
        synchronized (materialLock) {
            if (slot >= slotRuns.length) {
                int length = slotRuns.length;
                while (slot >= length) {
                    length *= 2;
                }
                int[][] grownRuns = new int[length][];
                System.arraycopy(slotRuns, 0, grownRuns, 0, slotRuns.length);
                int[] grownCount = new int[length];
                System.arraycopy(slotRunCount, 0, grownCount, 0, slotRunCount.length);
                slotRuns = grownRuns;
                slotRunCount = grownCount;
            }
            int[] stored = slotRuns[slot];
            if (stored == null || stored.length < runCount * 2) {
                stored = new int[Math.max(32, runCount * 2)];
                slotRuns[slot] = stored;
            }
            System.arraycopy(runs, 0, stored, 0, runCount * 2);
            slotRunCount[slot] = runCount;
            materialsStaged++;
        }
    }

    /**
     * Writes this chunk's materials, preferring the ones its own copy carried.
     *
     * The runs in the slot's table are the fallback, and for anything that was
     * not sorted by facing they are the only answer. Where the copy did sort,
     * they are the <b>wrong</b> answer and quietly so: the game's upload calls
     * itself again on the render thread, our hook publishes the runs a second
     * time in the original order, and the table then describes a chunk whose
     * quads have moved. Measured, that was 0.8% of the pixels of the view that
     * paints the world by material, against 0.02% between two runs of one build.
     */
    private void writeCarriedOrRuns(int slot, int vertexCount, long address) {
        if (carriedVertices == vertexCount) {
            for (int i = 0; i < vertexCount; i++) {
                MemoryUtil.memPutByte(address + i, carriedMaterial[i]);
            }
            // The table has been answered from and must not answer again for
            // whatever chunk takes this slot next.
            dropMaterials(slot);
            materialsApplied++;
            return;
        }
        writeMaterials(slot, vertexCount, address);
    }

    /**
     * Expands this slot's runs into one byte per vertex, and consumes them.
     *
     * Everything not covered by a run is left plain, which is also what a chunk
     * with no runs at all gets. That is the safe direction: an unknown material
     * draws exactly as the terrain has always drawn.
     */
    private void writeMaterials(int slot, int vertexCount, long address) {
        int[] runs = null;
        int count = 0;
        synchronized (materialLock) {
            if (slot < slotRunCount.length) {
                count = slotRunCount[slot];
                runs = slotRuns[slot];
                slotRunCount[slot] = 0;
            }
        }
        if (count == 0 || runs == null) {
            MemoryUtil.memSet(address, 0, vertexCount);
            materialsMissing++;
            return;
        }
        int written = 0;
        for (int r = 0; r < count && written < vertexCount; r++) {
            int end = Math.min(runs[r * 2], vertexCount);
            if (end > written) {
                MemoryUtil.memSet(address + written, runs[r * 2 + 1], end - written);
                written = end;
            }
        }
        if (written < vertexCount) {
            // The runs described fewer vertices than arrived. Not expected —
            // they are counted off the same buffer — but the tail is filled
            // rather than left as whatever the last chunk in this range was.
            MemoryUtil.memSet(address + written, 0, vertexCount - written);
        }
        materialsApplied++;
    }

    /** Forgets any runs waiting for a slot that is being reused or freed. */
    private void dropMaterials(int slot) {
        synchronized (materialLock) {
            if (slot < slotRunCount.length) {
                slotRunCount[slot] = 0;
            }
        }
    }

    private boolean hasStagedMaterials(int slot) {
        synchronized (materialLock) {
            return slot < slotRunCount.length && slotRunCount[slot] > 0;
        }
    }

    /** The staged copy for this slot if it still matches, else -1. */
    /** {@link #takeStaged} plus the two group sizes the copy worked out. */
    /** Materials that came with the staged geometry, for this upload only. */
    private byte[] carriedMaterial = new byte[0];
    private int carriedVertices;

    private long takeStagedGrouped(int slot, int size, Entry entry) {
        carriedVertices = 0;
        synchronized (workerLock) {
            Staged record = staged.get(slot);
            if (record != null && record.size == size) {
                entry.grouped = record.grouped;
                if (record.grouped) {
                    System.arraycopy(record.shelves, 0, entry.shelves, 0, 34);
                }
                if (record.materialVertices > 0) {
                    if (carriedMaterial.length < record.materialVertices) {
                        carriedMaterial = new byte[Integer.highestOneBit(
                                record.materialVertices) * 2];
                    }
                    System.arraycopy(record.material, 0, carriedMaterial, 0,
                            record.materialVertices);
                    carriedVertices = record.materialVertices;
                }
            }
        }
        return takeStaged(slot, size);
    }

    private long takeStaged(int slot, int size) {
        synchronized (workerLock) {
            Staged entry = staged.remove(slot);
            // A size mismatch means the chunk was rebuilt again after staging.
            // Writing newer bytes into a range measured for the older upload
            // would overrun it, so that one goes the ordinary way.
            return entry != null && entry.size == size ? entry.srcOffset : -1L;
        }
    }

    synchronized void upload(int slot, ByteBuffer data) {
        checkStampingThread("A chunk upload");
        int sourceSize = data.remaining();
        int size = VertexLayout.packedSize(sourceSize);
        Entry entry = entries.get(slot);
        if (entry != null && entry.capacity < size) {
            retired.add(new Retired(entry, frameStamp));
            entries.remove(slot);
            entry = null;
        }
        boolean freshRange = false;
        if (entry == null) {
            // Every suballocation must begin on a vertex boundary. A 4096 B
            // reserve is not divisible by 28; without this alignment the VBO
            // following an empty/small one reads shifted UV/color attributes.
            entry = createEntry(alignVertexCapacity(Math.max(size, 4096)));
            entries.put(slot, entry);
            freshRange = true;
        }
        if (materialsStaged > 0 && geometryBuffer != 0) {
            // After the entry, never before: allocating it is what may grow the
            // geometry buffer, and this buffer is sized from that one. First use
            // and growth are both rare and both stop the GPU, which is why this
            // asks rather than being checked on every upload.
            ensureMaterialBuffer();
        }
        if (size > 0) {
            int vertexCount = size / VertexLayout.stride();
            // Nothing said about this slot means leave the materials alone: the
            // upload is the game re-sorting a translucent layer it did not
            // rebuild, and what is in the buffer already describes these very
            // vertices. The exception is a range used for the first time, where
            // "already there" is whatever chunk had it last.
            boolean materials = materialBuffer != 0 && vertexCount > 0
                    && (freshRange || hasStagedMaterials(slot));
            // Before taking the staged copy: growing the ring reallocates the
            // memory it points into, and discards the staged records with it.
            ensureStagingRing(size + (materials ? vertexCount : 0));
            beginUploads();
            long src = takeStagedGrouped(slot, size, entry);
            long materialSrc = -1L;
            if (src < 0) {
                // May submit and wait before returning 0, so it has to come
                // before the copy is recorded but after the buffer is open.
                // Both ranges are taken at once so there is only one such point.
                long range = allocateStagingRange(size + (materials ? vertexCount : 0));
                src = range;
                materialSrc = range + size;
                groupedCopy(slot, MemoryUtil.memAddress(data), stagingMappedAddress + src,
                        sourceSize, renderGrouping);
                entry.grouped = renderGrouping.grouped;
                carriedVertices = 0;
                if (renderGrouping.grouped) {
                    System.arraycopy(renderGrouping.counts, VertexLayout.DOWN_TABLE,
                            entry.shelves, 0, 34);
                    carriedVertices = renderGrouping.permutedVertices;
                    if (carriedVertices > 0) {
                        if (carriedMaterial.length < carriedVertices) {
                            carriedMaterial = new byte[Integer.highestOneBit(
                                    carriedVertices) * 2];
                        }
                        System.arraycopy(renderGrouping.permuted, 0, carriedMaterial, 0,
                                carriedVertices);
                    }
                }
            } else if (materials) {
                // The geometry is already in the ring's builder region, which
                // this cannot disturb: it only ever rewinds the render thread's
                // own half.
                materialSrc = allocateStagingRange(vertexCount);
            }
            queueCopy(src, entry.offset, size);
            if (materials) {
                writeCarriedOrRuns(slot, vertexCount, stagingMappedAddress + materialSrc);
                queueMaterialCopy(materialSrc, entry.offset / VertexLayout.stride(), vertexCount);
            } else if (materialBuffer != 0) {
                materialsKept++;
            }
        } else {
            // An empty layer: there is nothing to describe, and runs left
            // waiting would be picked up by whatever this slot holds next.
            dropMaterials(slot);
        }
        totalBytes += size - entry.size;
        entry.size = size;
        entry.version++;
        if (size > largestEntrySize) {
            largestEntrySize = size;
        }
        uploadCount++;
        if (uploadCount <= 3) {
            LOGGER.info("Mirrored VBO {} into Vulkan buffer ({} bytes)", slot, size);
        }
    }

    synchronized void release(int slot) {
        checkStampingThread("A chunk release");
        // Drop any staged copy first: its destination is about to be freed,
        // and a later chunk reusing this slot must not inherit it.
        synchronized (workerLock) {
            staged.remove(slot);
            bumpEpoch(slot);
        }
        dropMaterials(slot);
        Entry entry = entries.remove(slot);
        if (entry != null) {
            totalBytes -= entry.size;
            retired.add(new Retired(entry, frameStamp));
        }
    }

    /**
     * Frees retired buffers stamped at or before {@code completedFrame} —
     * the newest terrain frame whose GPU execution is known to be finished.
     */
    synchronized void flushRetired(long completedFrame) {
        for (int i = retired.size() - 1; i >= 0; i--) {
            if (retired.get(i).frameStamp <= completedFrame) {
                Entry entry = retired.get(i).entry;
                // Nothing to destroy any more: the range goes back to the free
                // list and the entry is ordinary garbage.
                releaseGeometryRange(entry.offset, entry.capacity);
                retired.remove(i);
            }
        }
        for (int i = retiredBuffers.size() - 1; i >= 0; i--) {
            RetiredBuffer stale = retiredBuffers.get(i);
            if (stale.frameStamp <= completedFrame) {
                vkDestroyBuffer(device(), stale.buffer, null);
                vkFreeMemory(device(), stale.memory, null);
                retiredBuffers.remove(i);
            }
        }
    }

    /** Submit all VBO uploads before the terrain command buffer is submitted. */
    synchronized void flushUploads() {
        if (!uploadsRecording) {
            return;
        }
        emitPendingCopies();
        try (MemoryStack stack = stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT);
            vkCmdPipelineBarrier(uploadCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_VERTEX_INPUT_BIT, 0, barrier, null, null);
            check(vkEndCommandBuffer(uploadCommandBuffer), "vkEndCommandBuffer(VBO uploads)");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(uploadCommandBuffer));
            check(vkQueueSubmit(ctx.getGraphicsQueue(), submit, uploadFence), "vkQueueSubmit(VBO uploads)");
            uploadsRecording = false;
            uploadsSubmitted = true;
        }
    }

    synchronized String stats() {
        long offThread;
        long onThread;
        synchronized (workerLock) {
            offThread = workerStaged;
            onThread = workerRejected;
        }
        String line = String.format("mirrored VBOs: %d (%.1f MiB VRAM of %.1f MiB buffer, %d uploads, "
                        + "staging ring %d MiB, %d wraps, %d copies off the render thread, "
                        + "%d refused)",
                entries.size(), totalBytes / (1024.0 * 1024.0),
                geometryCapacity / (1024.0 * 1024.0), uploadCount,
                stagingCapacity / (1024 * 1024), stagingWraps, offThread, onThread);
        if (markOverruns > 0 || markMovedUnderCheck > 0 || reentrantAllocations > 0) {
            line += String.format("; geometry mark found past the buffer %d time(s), "
                            + "%d limit(s) recomputed after growth moved the mark, "
                            + "%d reentrant "
                            + "allocation(s), %d of them during a growth",
                    markOverruns, markMovedUnderCheck, reentrantAllocations,
                    allocationsDuringGrowth);
        }
        if (materialsStaged == 0 && materialBuffer == 0) {
            return line;
        }
        long applied;
        long missing;
        long staged;
        long kept;
        synchronized (materialLock) {
            applied = materialsApplied;
            missing = materialsMissing;
            staged = materialsStaged;
            kept = materialsKept;
        }
        String materials = String.format("; materials: %.1f MiB buffer, %d run tables handed over, "
                        + "%d written, %d uploads left as they were, %d filled plain",
                materialCapacity / (1024.0 * 1024.0), staged, applied, kept, missing);
        if (runsPermuted + runsAbsent != 0) {
            materials += String.format("; materials reordered %d, none to reorder %d",
                    runsPermuted, runsAbsent);
        }
        return line + materials;
    }

    /**
     * Gives up every chunk whose geometry reaches past a point in the buffer.
     *
     * Only ever called when the allocator's mark has been found beyond the end
     * of the buffer it indexes — a state that should not arise and has been
     * seen once, by fifty bytes. What it protects against is not the overrun
     * itself but the shape of its consequence: an entry that names bytes which
     * were never written is indistinguishable, from every other part of this
     * renderer, from one that names real geometry.
     *
     * The ranges are not returned to the free list. They describe a buffer that
     * is being replaced in the next few lines, and handing them back would put
     * the one piece of bookkeeping known to be wrong back into circulation to
     * be trusted a second time. Losing the space until the next full reset is
     * the cheaper mistake by a wide margin.
     *
     * @return how many chunks were given up, for the message that reports it
     */
    private int dropEntriesPast(long limit) {
        Entry[] table = entries.values();
        int dropped = 0;
        for (int slot = 0; slot < table.length; slot++) {
            Entry entry = table[slot];
            if (entry != null && entry.offset + entry.capacity > limit) {
                entries.remove(slot);
                dropped++;
            }
        }
        return dropped;
    }

    private Entry createEntry(int capacity) {
        Entry entry = new Entry();
        entry.capacity = capacity;
        entry.offset = allocateGeometryRange(capacity);
        return entry;
    }

    /** Grows the staging ring if a single upload would not fit in it. */
    private void ensureStagingRing(int needed) {
        if (stagingCapacity >= needed && stagingBuffer != 0) {
            return;
        }
        long capacity = Math.max(STAGING_RING_MIN, stagingCapacity == 0 ? STAGING_RING_MIN : stagingCapacity);
        while (capacity < needed) {
            capacity *= 2;
        }
        if (stagingBuffer != 0) {
            // Everything recorded so far reads from the buffer about to go.
            flushUploads();
            waitForUploads();
            // And so does any builder still copying into it. Closing the
            // region first matters: leaving it open while waiting would let
            // new builders keep reserving ranges, and with enough of them the
            // wait need never end. Once closed, only the copies already begun
            // remain, and each is a memcpy — microseconds, bounded.
            synchronized (workerLock) {
                workerRegionSize = 0;
                staged.clear();
            }
            while (true) {
                synchronized (workerLock) {
                    if (workerInFlight == 0) {
                        break;
                    }
                }
                Thread.yield();
            }
            vkUnmapMemory(device(), stagingMemory);
            vkDestroyBuffer(device(), stagingBuffer, null);
            vkFreeMemory(device(), stagingMemory, null);
        }
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(capacity)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device(), info, null, pBuffer), "vkCreateBuffer(staging ring)");
            stagingBuffer = pBuffer.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), stagingBuffer, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(staging ring)");
            stagingMemory = pMemory.get(0);
            check(vkBindBufferMemory(device(), stagingBuffer, stagingMemory, 0),
                    "vkBindBufferMemory(staging ring)");
            PointerBuffer ppData = stack.mallocPointer(1);
            check(vkMapMemory(device(), stagingMemory, 0, capacity, 0, ppData), "vkMapMemory(staging ring)");
            stagingMappedAddress = ppData.get(0);
        }
        stagingCapacity = capacity;
        stagingHead = 0;
        // Split in half: the render thread wraps within the lower part, the
        // builder threads fill the upper one. Two independent regions mean a
        // builder can never hand out a range the render thread is about to
        // reuse, without either of them coordinating on every allocation.
        synchronized (workerLock) {
            workerRegionSize = capacity / 2;
            workerRegionStart = capacity - workerRegionSize;
            workerHead = 0;
            staged.clear();
        }
        LOGGER.info("Staging ring sized to {} MiB ({} MiB of it for builder threads)",
                capacity / (1024 * 1024), (capacity / 2) / (1024 * 1024));
    }

    /**
     * Reserves {@code size} bytes in the ring and returns their offset.
     *
     * Wrapping is where the correctness lives: the head may only return to
     * zero once the GPU has finished reading what is already there, so a wrap
     * submits the pending copies and waits for them. That wait is the price of
     * not keeping a copy per chunk, and it only happens once the ring has been
     * filled — and the ring is at least 96 MiB, split in half between the two
     * threads that fill it, so with ~50 KiB chunks that is roughly every
     * thousand uploads on either side.
     */
    private long allocateStagingRange(int size) {
        long aligned = (size + 15L) & ~15L;
        // Wraps at the builder region rather than at the end of the ring.
        if (stagingHead + aligned > workerRegionStart) {
            flushUploads();
            waitForUploads();
            stagingHead = 0;
            stagingWraps++;
            beginUploads();
        }
        long offset = stagingHead;
        stagingHead += aligned;
        return offset;
    }

    private void waitForUploads() {
        if (!uploadsSubmitted) {
            return;
        }
        check(vkWaitForFences(device(), uploadFence, true, 1_000_000_000L),
                "vkWaitForFences(staging ring)");
        vkResetFences(device(), uploadFence);
        uploadsSubmitted = false;
    }

    synchronized void destroyAll() {
        // Long.MAX_VALUE also frees every retired geometry buffer.
        flushRetired(Long.MAX_VALUE);
        entries.clear();
        totalBytes = 0;
        largestEntrySize = 0;
        if (geometryBuffer != 0) {
            vkDestroyBuffer(device(), geometryBuffer, null);
            vkFreeMemory(device(), geometryMemory, null);
            geometryBuffer = 0;
            geometryMemory = 0;
            geometryCapacity = 0;
            nextGeometryOffset = 0;
            freeRanges.clear();
        }
        if (materialBuffer != 0) {
            vkDestroyBuffer(device(), materialBuffer, null);
            vkFreeMemory(device(), materialMemory, null);
            materialBuffer = 0;
            materialMemory = 0;
            materialCapacity = 0;
        }
        if (pendingMaterialCopies != null) {
            pendingMaterialCopies.free();
            pendingMaterialCopies = null;
            pendingMaterialCopyCount = 0;
        }
        if (pendingCopies != null) {
            pendingCopies.free();
            pendingCopies = null;
            pendingCopyCount = 0;
        }
        if (stagingBuffer != 0) {
            vkUnmapMemory(device(), stagingMemory);
            vkDestroyBuffer(device(), stagingBuffer, null);
            vkFreeMemory(device(), stagingMemory, null);
            stagingBuffer = 0;
            stagingMemory = 0;
            stagingMappedAddress = 0;
            stagingCapacity = 0;
            stagingHead = 0;
        }
        if (uploadFence != 0) {
            vkDestroyFence(device(), uploadFence, null);
            uploadFence = 0;
        }
        if (uploadCommandPool != 0) {
            vkDestroyCommandPool(device(), uploadCommandPool, null);
            uploadCommandPool = 0;
            uploadCommandBuffer = null;
        }
        LOGGER.info("Chunk mirror destroyed");
    }

    /** Starts a fresh upload command buffer, waiting only when it is reused. */
    private void beginUploads() {
        ensureUploadCommands();
        if (uploadsRecording) {
            return;
        }
        if (uploadsSubmitted) {
            check(vkWaitForFences(device(), uploadFence, true, 1_000_000_000L),
                    "vkWaitForFences(VBO uploads)");
            vkResetFences(device(), uploadFence);
            uploadsSubmitted = false;
        }
        // Past this point the previous submission has finished, so everything
        // the builders staged and we already recorded has been read by the GPU
        // and its space can be handed out again. Records not yet recorded go
        // with it — those chunks simply take the ordinary path when their
        // upload arrives — so the region is only recycled once it is half
        // spent, which keeps that loss rare while stopping it filling up.
        synchronized (workerLock) {
            if (workerInFlight == 0 && workerHead * 2 >= workerRegionSize) {
                workerHead = 0;
                staged.clear();
            }
        }
        vkResetCommandBuffer(uploadCommandBuffer, 0);
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(uploadCommandBuffer, begin), "vkBeginCommandBuffer(VBO uploads)");
        }
        uploadsRecording = true;
    }

    private void ensureUploadCommands() {
        if (uploadCommandPool != 0) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(ctx.getGraphicsQueueFamily());
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(device(), poolInfo, null, pPool), "vkCreateCommandPool(VBO uploads)");
            uploadCommandPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(uploadCommandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCommand = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device(), allocInfo, pCommand), "vkAllocateCommandBuffers(VBO uploads)");
            uploadCommandBuffer = new VkCommandBuffer(pCommand.get(0), device());

            // Deliberately NOT created signalled. vkQueueSubmit requires an
            // unsignalled fence, and nothing here waits on it before the first
            // submit — uploadsSubmitted already tracks "nothing submitted yet".
            // Starting it signalled meant the first submit took a signalled
            // fence, and every wait after that returned immediately without
            // the GPU having finished anything, so the upload command buffer
            // was reset and the staging ring reused while still in use.
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pFence = stack.mallocLong(1);
            check(vkCreateFence(device(), fenceInfo, null, pFence), "vkCreateFence(VBO uploads)");
            uploadFence = pFence.get(0);
        }
    }

    /**
     * How deep this thread is inside the allocator, and what that would mean.
     *
     * "All of it is serialised under one monitor" was offered three times as
     * the reason a race is impossible here, and it is only half an argument: a
     * Java monitor is reentrant, so it stops every other thread and stops
     * nothing at all about this one. Growth calls out — it flushes uploads,
     * waits on a fence, resolves the budget, drops entries — and if any of
     * that ever reaches back into the allocator, the inner call moves the mark
     * against a state the outer call is half-way through rebuilding, and both
     * of them are holding the monitor legitimately.
     *
     * No such path is visible today. That is exactly why it is worth one
     * counter rather than one more paragraph of reasoning: three passes have
     * now argued this shape away, and the mark still moves.
     */
    private int allocatorDepth;
    private boolean expandInFlight;
    private long reentrantAllocations;
    private long allocationsDuringGrowth;
    /**
     * Times growth moved the mark out from under the limit checked for it.
     *
     * This replaced a counter that asked whether an advance had ended past its
     * own limit. That one did its job: it said the fault was here rather than
     * in the capacity, which is what pointed at these three lines. It cannot
     * say anything now, because the limit is recomputed until it belongs to
     * the mark it will be added to — so what is worth counting is how often
     * that recomputation was needed, which is how often the fault would have
     * happened.
     *
     * Expected to be small and nonzero: growth repacks chunks into the new
     * buffer through this same method, so a nested allocation is ordinary. It
     * was the limit going stale that was not.
     */
    private long markMovedUnderCheck;

    /** Whether the one stack this is worth taking has been taken. */
    private boolean markOverrunStackLogged;

    private long allocateGeometryRange(int capacity) {
        allocatorDepth++;
        if (allocatorDepth > 1) {
            reentrantAllocations++;
        }
        if (expandInFlight) {
            allocationsDuringGrowth++;
        }
        try {
            return allocateGeometryRangeInner(capacity);
        } finally {
            allocatorDepth--;
        }
    }

    private long allocateGeometryRangeInner(int capacity) {
        // Asked on the way in as well as on the way out, and the difference
        // between the two answers is the whole reason this is here twice.
        //
        // The mark has been found past the end of the buffer it indexes, and
        // three passes over this file across three weeks have not found what
        // put it there. One check, after the mark moves, cannot tell the two
        // cases apart: a call that pushed the mark over the edge itself, and a
        // call that arrived to find it already over. Those are different bugs
        // in different places, and knowing which it is halves the search.
        if (nextGeometryOffset > geometryCapacity) {
            noteMarkOverrun("was already past", capacity);
            // Once per session, who was on the stack.
            //
            // Four counters have now been added around this and every one of
            // them came back zero, which says the mark was not moved by the
            // allocator and not moved during a growth — and says nothing at
            // all about who did move it. A stack does: it is the difference
            // between "something outside this class writes the field" and
            // "the allocator wrote it when the limit had not grown yet", and
            // no counter can tell those apart. Thrown and caught on the spot
            // purely to be printed, and only the first time.
            if (!markOverrunStackLogged) {
                markOverrunStackLogged = true;
                LOGGER.error("Geometry mark was already past the buffer; this is the stack that "
                        + "found it", new Throwable("geometry mark overrun"));
            }
        }
        for (int i = 0; i < freeRanges.size(); i++) {
            FreeRange range = freeRanges.get(i);
            if (range.capacity >= capacity) {
                freeRanges.remove(i);
                if (range.capacity > capacity) {
                    // The remainder keeps the list sorted: it starts after the
                    // part just taken and before whatever followed the range.
                    freeRanges.add(i, new FreeRange(range.offset + capacity, range.capacity - capacity));
                }
                return range.offset;
            }
        }
        // The limit and the mark it belongs to, tied together.
        //
        // This is the window the mark was escaping through, and it is three
        // lines long. The limit was worked out from the mark, then growth was
        // asked for it, and only then was the mark read again — and growth can
        // allocate: repacking the chunks into the new buffer comes back
        // through this very method. A Java monitor is reentrant, so nothing
        // stops it and nothing about it looks like a race. The outer call then
        // added its size to a mark that had moved while its limit had not, and
        // ended up past a buffer that had just been made big enough for where
        // the mark used to be. Fifty bytes over, rarely, which is the size of
        // one nested allocation and not of any arithmetic mistake.
        //
        // Asked again whenever the mark moves underneath, so that the size is
        // always added to the start that was actually checked. Bounded because
        // a loop that cannot end is worse than the fault it guards: after
        // eight rounds the counters below complain and the frame goes on.
        long offset = nextGeometryOffset;
        long checked = offset + capacity;
        boolean settled = false;
        for (int attempt = 0; attempt < 8; attempt++) {
            ensureGeometryCapacity(checked);
            if (nextGeometryOffset == offset) {
                settled = true;
                break;
            }
            markMovedUnderCheck++;
            offset = nextGeometryOffset;
            checked = offset + capacity;
        }
        if (!settled) {
            // Eight rounds and the mark was still moving. The last limit
            // worked out was never asked for, and writing it here would be the
            // very fault this loop exists to close, one level further down.
            // So the mark is taken as it now stands and room is made for that.
            offset = nextGeometryOffset;
            checked = offset + capacity;
            ensureGeometryCapacity(checked);
        }
        nextGeometryOffset = checked;
        // Checked where the mark is moved, not only where growth trips over it.
        //
        // A growth copy once found the mark fifty bytes past the buffer it
        // lives in, and the clamp there stops the read but says nothing about
        // which allocation put it there — by then the buffer has already been
        // replaced. This is the only place the mark moves, so a complaint here
        // names the size that did it, and it is one comparison on a path that
        // already did a search.
        if (nextGeometryOffset > geometryCapacity) {
            noteMarkOverrun("was moved past", capacity);
        }
        return offset;
    }

    /** How many times the mark has been found past the end of its buffer. */
    private long markOverruns;

    /**
     * Says which call, on which thread, with what in hand.
     *
     * The line this replaces was fired once per session and then went quiet
     * for good, and carried the two numbers only. Once is enough to know
     * something is wrong and not enough to know what: the second occurrence is
     * where a pattern would show — same thread or a different one, same size or
     * any size, right after a growth or nowhere near one. The gate is now a
     * count rather than a flag, so the first several all speak, and the total
     * goes into the report where a tester will see it without reading a log.
     */
    private void noteMarkOverrun(String when, int capacity) {
        markOverruns++;
        if (markOverruns <= 8) {
            LOGGER.warn("Geometry mark {} the buffer it indexes: {} of {} bytes, "
                            + "while taking {} bytes on thread {} (occurrence {}; "
                            + "{} limit(s) recomputed after growth moved the mark, {} "
                            + "reentrant allocation(s), {} during a growth)",
                    when, nextGeometryOffset, geometryCapacity, capacity,
                    Thread.currentThread().getName(), markOverruns,
                    markMovedUnderCheck, reentrantAllocations, allocationsDuringGrowth);
        }
    }

    /**
     * Returns a range to the free list, merging it with its neighbours.
     *
     * Without merging, a session spent flying around leaves the buffer as
     * thousands of small adjacent holes that no rebuilt chunk fits into, so
     * the buffer grows even though the free space was there all along — and
     * growth is the expensive, stop-everything path.
     */
    private void releaseGeometryRange(long offset, long capacity) {
        int lo = 0;
        int hi = freeRanges.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (freeRanges.get(mid).offset < offset) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        long start = offset;
        long end = offset + capacity;
        if (lo < freeRanges.size()) {
            FreeRange next = freeRanges.get(lo);
            if (next.offset == end) {
                end = next.offset + next.capacity;
                freeRanges.remove(lo);
            }
        }
        if (lo > 0) {
            FreeRange previous = freeRanges.get(lo - 1);
            if (previous.offset + previous.capacity == start) {
                start = previous.offset;
                lo--;
                freeRanges.remove(lo);
            }
        }
        freeRanges.add(lo, new FreeRange(start, end - start));
    }

    private static int alignVertexCapacity(int capacity) {
        int remainder = capacity % VertexLayout.stride();
        return remainder == 0 ? capacity : capacity + VertexLayout.stride() - remainder;
    }

    /**
     * Reads the geometry budget once, from the settings screen or from the
     * hardware. The game side sets the property; the renderer lives in its own
     * classloader and cannot reach VulkanConfig directly.
     */
    private void resolveBudget() {
        if (geometryBudget != 0) {
            return;
        }
        long budgetMiB = 0;
        try {
            budgetMiB = Long.parseLong(System.getProperty("vulkanmod112.geometryBudget", "0"));
        } catch (NumberFormatException ignored) {
            // Left on automatic.
        }
        if (budgetMiB <= 0) {
            // A quarter of the card, bounded: below 256 MiB the buffer would
            // regrow constantly, and past 2 GiB there is nothing left to win.
            long vram = ctx.vramMegabytes();
            budgetMiB = vram > 0 ? Math.max(256L, Math.min(2048L, vram / 4L)) : 512L;
        }
        geometryBudget = budgetMiB * 1024L * 1024L;
        // Start at a quarter of the budget: enough to cover a normal render
        // distance without a single regrowth, without reserving it all upfront.
        initialGeometryCapacity = Math.max(64L * 1024L * 1024L, geometryBudget / 4L);
        LOGGER.info("Geometry budget {} MiB (initial buffer {} MiB, GPU reports {} MiB device-local)",
                budgetMiB, initialGeometryCapacity / (1024 * 1024), ctx.vramMegabytes());
    }

    /**
     * Rare growth path. The used part of the old buffer is copied into the new
     * one by the GPU.
     *
     * It used to be repopulated from the per-chunk staging copies, which is
     * what made those copies worth keeping in the first place. Without them a
     * device-to-device copy is both the only option and the faster one: the
     * data never leaves VRAM, where re-uploading pushed the entire world back
     * across PCIe.
     */
    private void ensureGeometryCapacity(long required) {
        if (geometryBuffer != 0 && required <= geometryCapacity) {
            return;
        }
        expandInFlight = true;
        try {
            growGeometryBuffer(required);
        } finally {
            expandInFlight = false;
        }
    }

    private void growGeometryBuffer(long required) {
        if (uploadsRecording) {
            flushUploads();
        }
        waitForUploads();
        long oldBuffer = geometryBuffer;
        long oldMemory = geometryMemory;
        long oldUsed = nextGeometryOffset;
        long oldCapacity = geometryCapacity;
        resolveBudget();
        long capacity = geometryCapacity == 0 ? initialGeometryCapacity : geometryCapacity;
        long step = Math.max(64L * 1024L * 1024L, geometryBudget / 8L);
        while (capacity < required) {
            // Double while there is budget left, then creep, so a small card
            // does not jump from 2 to 4 GiB to hold one chunk over the line.
            capacity = capacity < geometryBudget ? capacity * 2 : capacity + step;
        }
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(capacity)
                    // An acceleration structure is built from a device address,
                    // not from a bound buffer, and both of those flags have to
                    // be asked for when the buffer is created — there is no
                    // adding them to a buffer that already exists. Asked for
                    // only when ray tracing came up, so a session without it
                    // allocates exactly what it always did.
                    // TRANSFER_SRC because growth copies this buffer into its
                    // successor on the card. It was missing from the day that
                    // copy was written — every session since has issued a copy
                    // from a buffer that never said it could be copied from,
                    // and no driver refused. The validation layer did, the
                    // first time it was ever pointed at this.
                    .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                            | (rayTracing ? RAY_TRACING_BUFFER_USAGE : 0))
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device(), info, null, pBuffer), "vkCreateBuffer(chunk geometry)");
            geometryBuffer = pBuffer.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), geometryBuffer, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            if (rayTracing) {
                alloc.pNext(org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                        .flags(org.lwjgl.vulkan.VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT)
                        .address());
            }
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(chunk geometry)");
            geometryMemory = pMemory.get(0);
            check(vkBindBufferMemory(device(), geometryBuffer, geometryMemory, 0),
                    "vkBindBufferMemory(chunk geometry)");
        }
        geometryCapacity = capacity;
        if (oldBuffer != 0) {
            if (oldUsed > 0) {
                // One copy for the whole used region: the layout is identical
                // in both buffers, so per-chunk copies would gain nothing.
                beginUploads();
                try (MemoryStack stack = stackPush()) {
                    // Clamped to what the old buffer actually held. The high
                    // water mark is supposed never to pass the capacity, and
                    // the validation layer found a frame where it had by fifty
                    // bytes — a read past the end of a buffer, on the card,
                    // which is the shape of fault that ends a session rather
                    // than a frame. Both numbers are printed so the next one
                    // is a report and not another investigation.
                    long copied = Math.min(oldUsed, oldCapacity);
                    if (copied != oldUsed) {
                        // The tail the mark claims was never in this buffer.
                        //
                        // Which means the chunks living there were never
                        // uploaded into anything: there is no data to carry
                        // forward, only whatever the allocator happened to
                        // leave. Copying less and printing a warning was what
                        // stood here, and it left those chunks drawing that —
                        // triangles out of nowhere that outlive the frame they
                        // appeared in and point at nothing when reported.
                        //
                        // So the range is given up rather than carried over.
                        // The chunks in it stop being drawn until the game
                        // rebuilds them, which is a hole and not a lie, and the
                        // mark is wound back to the last byte that exists so
                        // that the allocation this growth was for lands
                        // somewhere real.
                        int dropped = dropEntriesPast(oldCapacity);
                        nextGeometryOffset = oldCapacity;
                        LOGGER.error("Geometry mark {} is past the buffer it indexes ({}); the {} "
                                        + "bytes beyond it were never uploaded, so {} chunk(s) have "
                                        + "been dropped and will return when the game rebuilds them",
                                oldUsed, oldCapacity, oldUsed - oldCapacity, dropped);
                    }
                    VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
                    copy.get(0).srcOffset(0).dstOffset(0).size(copied);
                    vkCmdCopyBuffer(uploadCommandBuffer, oldBuffer, geometryBuffer, copy);
                }
                flushUploads();
                waitForUploads();
            }
            // Not destroyed here: frames already recorded still name this
            // handle. It goes on the retired list and is freed once every
            // frame that could reference it has completed. Nothing writes the
            // old buffer any more, so those frames and the copy above are both
            // reads and can safely overlap.
            retiredBuffers.add(new RetiredBuffer(oldBuffer, oldMemory, frameStamp));
        }
        LOGGER.info("Shared Vulkan chunk geometry buffer sized to {} MiB", capacity / (1024 * 1024));
    }

    /**
     * Brings the material buffer up to one byte per vertex the geometry buffer
     * can hold, keeping what is already in it.
     *
     * Called on first use rather than alongside the geometry buffer, so a
     * session that never records materials never allocates for them. On growth
     * it follows the geometry buffer exactly: fill the new one with plain,
     * copy the used part across, retire the old one for the frames that still
     * name it.
     */
    private void ensureMaterialBuffer() {
        long required = geometryCapacity / VertexLayout.stride();
        // vkCmdFillBuffer works in whole words, and a buffer sized to a
        // multiple of four is the simplest way to be allowed to fill all of it.
        required = (required + 3L) & ~3L;
        if (materialBuffer != 0 && required <= materialCapacity) {
            return;
        }
        if (uploadsRecording) {
            flushUploads();
        }
        waitForUploads();
        long oldBuffer = materialBuffer;
        long oldMemory = materialMemory;
        // Clamped to the old buffer, not just to the vertex count.
        //
        // The amount worth keeping is worked out from how far the geometry
        // buffer has been filled, and the old material buffer was sized from
        // an older geometry capacity — so after the geometry has grown and the
        // materials have not yet, the first number is past the end of the
        // second. Copying that much reads off the end of a buffer on the card.
        long keep = oldBuffer == 0 ? 0L : (nextGeometryOffset / VertexLayout.stride()) & ~3L;
        keep = Math.min(keep, materialCapacity);
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(required)
                    .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device(), info, null, pBuffer), "vkCreateBuffer(chunk materials)");
            materialBuffer = pBuffer.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), materialBuffer, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(chunk materials)");
            materialMemory = pMemory.get(0);
            check(vkBindBufferMemory(device(), materialBuffer, materialMemory, 0),
                    "vkBindBufferMemory(chunk materials)");
        }
        materialCapacity = required;
        beginUploads();
        // Plain everywhere first. A chunk uploaded before this feature was
        // switched on has no runs of its own, and reading whatever the memory
        // came with would make a hillside water.
        vkCmdFillBuffer(uploadCommandBuffer, materialBuffer, 0, VK_WHOLE_SIZE, 0);
        if (oldBuffer != 0 && keep > 0) {
            try (MemoryStack stack = stackPush()) {
                VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
                copy.get(0).srcOffset(0).dstOffset(0).size(keep);
                vkCmdCopyBuffer(uploadCommandBuffer, oldBuffer, materialBuffer, copy);
            }
        }
        flushUploads();
        waitForUploads();
        if (oldBuffer != 0) {
            retiredBuffers.add(new RetiredBuffer(oldBuffer, oldMemory, frameStamp));
        }
        LOGGER.info("Chunk material buffer sized to {} KiB", required / 1024);
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

    private static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(call + " failed with VkResult " + result);
        }
    }

}
