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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * VRAM mirror of the game's world geometry (stage 3.2).
 *
 * Every VBO upload the game performs is copied into a Vulkan vertex buffer
 * keyed by the GL buffer id, so the stage 3.3 terrain renderer can draw the
 * exact same geometry without touching OpenGL. The draw buffers are device
 * local; each has a persistently mapped system-memory staging buffer. Uploads
 * made while Minecraft rebuilds chunks are recorded together and submitted
 * once immediately before the terrain frame. This avoids the very expensive
 * PCIe reads caused by using host-visible memory as a vertex buffer.
 */
final class VkChunkMirror {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/ChunkMirror");
    /** Vanilla's VboRenderList uses pos3f|color4ub|uv2f|light2s. */
    private static final int BLOCK_VERTEX_STRIDE = 28;

    static final class Entry {
        long offset;
        long stagingBuffer;
        long stagingMemory;
        long stagingMappedAddress;
        int capacity;
        int size;
    }

    /** Lookup for the terrain renderer; null when the game VBO has no mirror. */
    synchronized Entry find(int glBufferId) {
        return entries.get(glBufferId);
    }

    /** Largest mirrored payload in bytes; sizes the shared quad index buffer. */
    synchronized int maxEntrySize() {
        int max = 0;
        for (Entry entry : entries.values()) {
            max = Math.max(max, entry.size);
        }
        return max;
    }

    synchronized long geometryBuffer() {
        return geometryBuffer;
    }

    private final VulkanContextImpl ctx;
    private final Map<Integer, Entry> entries = new HashMap<Integer, Entry>();
    private static final class Retired {
        final Entry entry;
        final long frameStamp;

        Retired(Entry entry, long frameStamp) {
            this.entry = entry;
            this.frameStamp = frameStamp;
        }
    }

    private static final class FreeRange {
        final long offset;
        final int capacity;

        FreeRange(long offset, int capacity) {
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
    private static final long INITIAL_GEOMETRY_CAPACITY = 128L * 1024L * 1024L;

    private long uploadCommandPool;
    private VkCommandBuffer uploadCommandBuffer;
    private long uploadFence;
    private boolean uploadsRecording;
    private boolean uploadsSubmitted;

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
            beginUploads();
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), entry.stagingMappedAddress, size);
            try (MemoryStack stack = stackPush()) {
                VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
                copy.get(0).srcOffset(0).dstOffset(entry.offset).size(size);
                vkCmdCopyBuffer(uploadCommandBuffer, entry.stagingBuffer, geometryBuffer, copy);
            }
        }
        totalBytes += size - entry.size;
        entry.size = size;
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
                destroyEntry(entry);
                freeRanges.add(new FreeRange(entry.offset, entry.capacity));
                retired.remove(i);
            }
        }
    }

    /** Submit all VBO uploads before the terrain command buffer is submitted. */
    synchronized void flushUploads() {
        if (!uploadsRecording) {
            return;
        }
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
        return String.format("mirrored VBOs: %d (%.1f MiB VRAM, %d uploads)",
                entries.size(), totalBytes / (1024.0 * 1024.0), uploadCount);
    }

    private Entry createEntry(int capacity) {
        try (MemoryStack stack = stackPush()) {
            Entry entry = new Entry();
            entry.capacity = capacity;

            entry.offset = allocateGeometryRange(capacity);

            VkBufferCreateInfo stagingInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(capacity)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device(), stagingInfo, null, pBuffer), "vkCreateBuffer(mirror staging)");
            entry.stagingBuffer = pBuffer.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), entry.stagingBuffer, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(mirror staging)");
            entry.stagingMemory = pMemory.get(0);
            check(vkBindBufferMemory(device(), entry.stagingBuffer, entry.stagingMemory, 0),
                    "vkBindBufferMemory(mirror staging)");
            PointerBuffer ppData = stack.mallocPointer(1);
            check(vkMapMemory(device(), entry.stagingMemory, 0, capacity, 0, ppData),
                    "vkMapMemory(mirror staging)");
            entry.stagingMappedAddress = ppData.get(0);
            return entry;
        }
    }

    private void destroyEntry(Entry entry) {
        vkUnmapMemory(device(), entry.stagingMemory);
        vkDestroyBuffer(device(), entry.stagingBuffer, null);
        vkFreeMemory(device(), entry.stagingMemory, null);
    }

    synchronized void destroyAll() {
        flushRetired(Long.MAX_VALUE);
        for (Entry entry : entries.values()) {
            destroyEntry(entry);
        }
        entries.clear();
        totalBytes = 0;
        if (geometryBuffer != 0) {
            vkDestroyBuffer(device(), geometryBuffer, null);
            vkFreeMemory(device(), geometryMemory, null);
            geometryBuffer = 0;
            geometryMemory = 0;
            geometryCapacity = 0;
            nextGeometryOffset = 0;
            freeRanges.clear();
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

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
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
                    freeRanges.add(new FreeRange(range.offset + capacity, range.capacity - capacity));
                }
                return range.offset;
            }
        }
        ensureGeometryCapacity(nextGeometryOffset + capacity);
        long offset = nextGeometryOffset;
        nextGeometryOffset += capacity;
        return offset;
    }

    private static int alignVertexCapacity(int capacity) {
        int remainder = capacity % BLOCK_VERTEX_STRIDE;
        return remainder == 0 ? capacity : capacity + BLOCK_VERTEX_STRIDE - remainder;
    }

    /** Rare growth path; persistent staging copies repopulate the new VRAM buffer. */
    private void ensureGeometryCapacity(long required) {
        if (geometryBuffer != 0 && required <= geometryCapacity) {
            return;
        }
        if (uploadsRecording) {
            flushUploads();
        }
        if (uploadsSubmitted) {
            check(vkWaitForFences(device(), uploadFence, true, 1_000_000_000L),
                    "vkWaitForFences(geometry grow)");
            vkResetFences(device(), uploadFence);
            uploadsSubmitted = false;
        }
        if (geometryBuffer != 0) {
            vkDeviceWaitIdle(device());
            vkDestroyBuffer(device(), geometryBuffer, null);
            vkFreeMemory(device(), geometryMemory, null);
        }
        long capacity = geometryCapacity == 0 ? INITIAL_GEOMETRY_CAPACITY : geometryCapacity;
        while (capacity < required) {
            capacity *= 2;
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
        if (!entries.isEmpty()) {
            beginUploads();
            try (MemoryStack stack = stackPush()) {
                VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
                for (Entry entry : entries.values()) {
                    if (entry.size == 0) continue;
                    copy.get(0).srcOffset(0).dstOffset(entry.offset).size(entry.size);
                    vkCmdCopyBuffer(uploadCommandBuffer, entry.stagingBuffer, geometryBuffer, copy);
                }
            }
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
