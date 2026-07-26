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
    /** Vanilla's VboRenderList uses pos3f|color4ub|uv2f|light2s. */
    private static final int BLOCK_VERTEX_STRIDE = 28;

    /**
     * A chunk's slice of the shared geometry buffer. Nothing else: uploads go
     * through one shared staging ring, so a mirrored chunk costs a range in
     * VRAM and this object, not a Vulkan allocation of its own.
     */
    static final class Entry {
        long offset;
        int capacity;
        int size;
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
    private long uploadCount;

    private long geometryBuffer;
    private long geometryMemory;
    private long geometryCapacity;
    private long nextGeometryOffset;
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
        if (pendingCopyCount == 0) {
            return;
        }
        pendingCopies.position(0).limit(pendingCopyCount);
        vkCmdCopyBuffer(uploadCommandBuffer, stagingBuffer, geometryBuffer, pendingCopies);
        pendingCopies.limit(pendingCopies.capacity());
        pendingCopyCount = 0;
    }

    /** Called by the terrain renderer at the start of each frame. */
    synchronized void setFrameStamp(long stamp) {
        this.frameStamp = stamp;
    }

    VkChunkMirror(VulkanContextImpl ctx) {
        this.ctx = ctx;
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
        int size = data.remaining();
        if (size <= 0 || slot < 0) {
            return false;
        }
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
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), mapped + offset, size);
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
                    workerStaged++;
                    published = true;
                } else {
                    workerRejected++;
                }
            }
        }
        return published;
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

    /** The staged copy for this slot if it still matches, else -1. */
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
        int size = data.remaining();
        Entry entry = entries.get(slot);
        if (entry != null && entry.capacity < size) {
            retired.add(new Retired(entry, frameStamp));
            entries.remove(slot);
            entry = null;
        }
        if (entry == null) {
            // Every suballocation must begin on a vertex boundary. A 4096 B
            // reserve is not divisible by 28; without this alignment the VBO
            // following an empty/small one reads shifted UV/color attributes.
            entry = createEntry(alignVertexCapacity(Math.max(size, 4096)));
            entries.put(slot, entry);
        }
        if (size > 0) {
            // Before taking the staged copy: growing the ring reallocates the
            // memory it points into, and discards the staged records with it.
            ensureStagingRing(size);
            beginUploads();
            long src = takeStaged(slot, size);
            if (src < 0) {
                // May submit and wait before returning 0, so it has to come
                // before the copy is recorded but after the buffer is open.
                src = allocateStagingRange(size);
                MemoryUtil.memCopy(MemoryUtil.memAddress(data), stagingMappedAddress + src, size);
            }
            queueCopy(src, entry.offset, size);
        }
        totalBytes += size - entry.size;
        entry.size = size;
        if (size > largestEntrySize) {
            largestEntrySize = size;
        }
        uploadCount++;
        if (uploadCount <= 3) {
            LOGGER.info("Mirrored VBO {} into Vulkan buffer ({} bytes)", slot, size);
        }
    }

    synchronized void release(int slot) {
        // Drop any staged copy first: its destination is about to be freed,
        // and a later chunk reusing this slot must not inherit it.
        synchronized (workerLock) {
            staged.remove(slot);
            bumpEpoch(slot);
        }
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
        return String.format("mirrored VBOs: %d (%.1f MiB VRAM of %.1f MiB buffer, %d uploads, "
                        + "staging ring %d MiB, %d wraps, %d copies off the render thread, "
                        + "%d refused)",
                entries.size(), totalBytes / (1024.0 * 1024.0),
                geometryCapacity / (1024.0 * 1024.0), uploadCount,
                stagingCapacity / (1024 * 1024), stagingWraps, offThread, onThread);
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
     * filled — with 32 MiB and ~50 KiB chunks, roughly every 600 uploads.
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

    private long allocateGeometryRange(int capacity) {
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
        ensureGeometryCapacity(nextGeometryOffset + capacity);
        long offset = nextGeometryOffset;
        nextGeometryOffset += capacity;
        return offset;
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
        int remainder = capacity % BLOCK_VERTEX_STRIDE;
        return remainder == 0 ? capacity : capacity + BLOCK_VERTEX_STRIDE - remainder;
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
        if (uploadsRecording) {
            flushUploads();
        }
        waitForUploads();
        long oldBuffer = geometryBuffer;
        long oldMemory = geometryMemory;
        long oldUsed = nextGeometryOffset;
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
                    .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT)
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
                    VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
                    copy.get(0).srcOffset(0).dstOffset(0).size(oldUsed);
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
