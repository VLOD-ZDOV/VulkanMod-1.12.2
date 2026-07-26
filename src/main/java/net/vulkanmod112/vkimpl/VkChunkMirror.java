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
 * keyed by the GL buffer id, so the stage 3.3 terrain renderer can draw the
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

    /** Lookup for the terrain renderer; null when the game VBO has no mirror. */
    synchronized Entry find(int glBufferId) {
        return entries.get(glBufferId);
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
    private final EntryMap entries = new EntryMap();
    /** High-water mark behind {@link #maxEntrySize()}. */
    private int largestEntrySize;

    /**
     * Open-addressed int → Entry map.
     *
     * This replaced a {@code HashMap<Integer, Entry>}: the draw loop resolves
     * every visible chunk of every layer each frame, and boxing an Integer key
     * for each of those lookups was showing up as plain waste at high render
     * distances. GL buffer names are never 0, so 0 doubles as the empty slot.
     */
    private static final class EntryMap {
        private int[] keys = new int[1024];
        private Entry[] values = new Entry[1024];
        private int size;

        int size() {
            return size;
        }

        Entry[] values() {
            return values;
        }

        private int slotOf(int key) {
            int mask = keys.length - 1;
            // Fibonacci hashing: GL buffer names are small consecutive
            // integers, which the identity hash would cluster badly.
            int slot = (int) ((key * 0x9E3779B1L) >>> 32) & mask;
            while (keys[slot] != 0 && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            return slot;
        }

        Entry get(int key) {
            if (key == 0) {
                return null;
            }
            return values[slotOf(key)];
        }

        /** Places a key without considering growth; never reallocates. */
        private void insert(int key, Entry value) {
            int slot = slotOf(key);
            if (keys[slot] == 0) {
                keys[slot] = key;
                size++;
            }
            values[slot] = value;
        }

        void put(int key, Entry value) {
            insert(key, value);
            if (size * 2 >= keys.length) {
                grow();
            }
        }

        Entry remove(int key) {
            if (key == 0) {
                return null;
            }
            int slot = slotOf(key);
            if (keys[slot] == 0) {
                return null;
            }
            Entry previous = values[slot];
            keys[slot] = 0;
            values[slot] = null;
            size--;
            // Backward-shift deletion: anything that probed past this slot has
            // to be reinserted, or it becomes unreachable. Reinsertion cannot
            // grow the table (size never rises above what it was), so the
            // arrays stay put while this loop walks them.
            int mask = keys.length - 1;
            int next = (slot + 1) & mask;
            while (keys[next] != 0) {
                int movedKey = keys[next];
                Entry movedValue = values[next];
                keys[next] = 0;
                values[next] = null;
                size--;
                insert(movedKey, movedValue);
                next = (next + 1) & mask;
            }
            return previous;
        }

        private void grow() {
            int[] oldKeys = keys;
            Entry[] oldValues = values;
            keys = new int[oldKeys.length * 2];
            values = new Entry[oldValues.length * 2];
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != 0) {
                    insert(oldKeys[i], oldValues[i]);
                }
            }
        }

        void clear() {
            java.util.Arrays.fill(keys, 0);
            java.util.Arrays.fill(values, null);
            size = 0;
        }
    }

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

    synchronized void upload(int glBufferId, ByteBuffer data) {
        int size = data.remaining();
        Entry entry = entries.get(glBufferId);
        if (entry != null && entry.capacity < size) {
            retired.add(new Retired(entry, frameStamp));
            entries.remove(glBufferId);
            entry = null;
        }
        if (entry == null) {
            // Every suballocation must begin on a vertex boundary. A 4096 B
            // reserve is not divisible by 28; without this alignment the VBO
            // following an empty/small one reads shifted UV/color attributes.
            entry = createEntry(alignVertexCapacity(Math.max(size, 4096)));
            entries.put(glBufferId, entry);
        }
        if (size > 0) {
            ensureStagingRing(size);
            beginUploads();
            // May submit and wait before returning 0, so it has to come before
            // the copy is recorded but after the command buffer is open.
            long src = allocateStagingRange(size);
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), stagingMappedAddress + src, size);
            queueCopy(src, entry.offset, size);
        }
        totalBytes += size - entry.size;
        entry.size = size;
        if (size > largestEntrySize) {
            largestEntrySize = size;
        }
        uploadCount++;
        if (uploadCount <= 3) {
            LOGGER.info("Mirrored VBO {} into Vulkan buffer ({} bytes)", glBufferId, size);
        }
    }

    synchronized void release(int glBufferId) {
        Entry entry = entries.remove(glBufferId);
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
        return String.format("mirrored VBOs: %d (%.1f MiB VRAM of %.1f MiB buffer, %d uploads, "
                        + "staging ring %d MiB, %d wraps)",
                entries.size(), totalBytes / (1024.0 * 1024.0),
                geometryCapacity / (1024.0 * 1024.0), uploadCount,
                stagingCapacity / (1024 * 1024), stagingWraps);
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
        LOGGER.info("Staging ring sized to {} MiB", capacity / (1024 * 1024));
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
        if (stagingHead + aligned > stagingCapacity) {
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
