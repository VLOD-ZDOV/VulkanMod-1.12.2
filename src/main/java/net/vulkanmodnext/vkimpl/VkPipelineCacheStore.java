package net.vulkanmodnext.vkimpl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineCache;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineCache;
import static org.lwjgl.vulkan.VK10.vkGetPipelineCacheData;

/**
 * A {@code VkPipelineCache} that survives between runs.
 *
 * Compiling a pipeline means handing the driver SPIR-V and having it produce
 * machine code for the installed GPU. That happens on the render thread while
 * the terrain renderer comes up, and the driver has no way to know it did the
 * same work yesterday unless it is given somewhere to remember it.
 *
 * A cache is that somewhere: passed to {@code vkCreateGraphicsPipelines} it is
 * consulted first, and read back afterwards it is a blob that can be written to
 * a file and handed straight back next time. What it buys is startup time and
 * nothing else — it cannot make a frame faster, only make the first one arrive
 * sooner, and it matters more the more pipelines there are.
 *
 * <h2>Everything here is optional and nothing here may throw</h2>
 *
 * A cache is a hint. The blob is written by one driver version for one device
 * and is meaningless to any other, so it is routinely stale — after a driver
 * update, on a different GPU, or if the file is truncated by a crash. Vulkan
 * handles that itself: the header carries the vendor, device and a UUID, and a
 * mismatch makes the driver ignore the contents rather than fail. A corrupt
 * file is therefore a slower start, not a broken one, and every failure below
 * ends in "carry on without it".
 */
final class VkPipelineCacheStore {

    private static final Logger LOGGER = LogManager.getLogger("VulkanModNext/Pipelines");
    /** Enough for a great many pipelines; a blob larger than this is not ours. */
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    private long handle = VK_NULL_HANDLE;
    private File file;

    /**
     * Creates the cache, seeded from disk when a usable file is there.
     *
     * @return the handle to pass to pipeline creation, or {@code VK_NULL_HANDLE}
     *         when a cache could not be created at all — which is a valid
     *         argument meaning "no cache", so callers need no branch
     */
    long create(VkDevice device, String path) {
        this.file = path == null || path.isEmpty() ? null : new File(path);
        ByteBuffer seed = read();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);
            if (seed != null) {
                info.pInitialData(seed);
            }
            java.nio.LongBuffer pCache = stack.mallocLong(1);
            int result = vkCreatePipelineCache(device, info, null, pCache);
            if (result != VK_SUCCESS) {
                LOGGER.info("Pipeline cache unavailable ({}); compiling from scratch", result);
                handle = VK_NULL_HANDLE;
            } else {
                handle = pCache.get(0);
                LOGGER.info("Pipeline cache {}", seed == null
                        ? "created empty" : "seeded with " + seed.remaining() + " bytes");
            }
        } catch (Throwable t) {
            LOGGER.warn("Pipeline cache could not be created; carrying on without one", t);
            handle = VK_NULL_HANDLE;
        } finally {
            if (seed != null) {
                memFree(seed);
            }
        }
        return handle;
    }

    /** Writes the cache back out and destroys it. Safe to call twice. */
    void destroy(VkDevice device) {
        if (handle == VK_NULL_HANDLE) {
            return;
        }
        write(device);
        vkDestroyPipelineCache(device, handle, null);
        handle = VK_NULL_HANDLE;
    }

    private ByteBuffer read() {
        if (file == null || !file.isFile()) {
            return null;
        }
        long length = file.length();
        if (length <= 0L || length > MAX_BYTES) {
            return null;
        }
        ByteBuffer buffer = memAlloc((int) length);
        try (InputStream in = new FileInputStream(file)) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.put(chunk, 0, read);
            }
            buffer.flip();
            return buffer;
        } catch (IOException | RuntimeException e) {
            LOGGER.info("Pipeline cache could not be read; compiling from scratch");
            memFree(buffer);
            return null;
        }
    }

    private void write(VkDevice device) {
        if (file == null) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            if (vkGetPipelineCacheData(device, handle, size, null) != VK_SUCCESS) {
                return;
            }
            int bytes = (int) size.get(0);
            if (bytes <= 0) {
                return;
            }
            ByteBuffer data = memAlloc(bytes);
            try {
                if (vkGetPipelineCacheData(device, handle, size, data) != VK_SUCCESS) {
                    return;
                }
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    return;
                }
                // Written to a neighbour and moved into place: a half-written
                // file left by a crash mid-save would otherwise be read back on
                // the next run, and the point of this is to make starting up
                // faster rather than to add a way for it to go wrong.
                File temporary = new File(file.getPath() + ".tmp");
                byte[] copy = new byte[bytes];
                data.get(copy);
                try (OutputStream out = new FileOutputStream(temporary)) {
                    out.write(copy);
                }
                if (!temporary.renameTo(file) && !(file.delete() && temporary.renameTo(file))) {
                    temporary.delete();
                    return;
                }
                LOGGER.info("Pipeline cache saved, {} bytes", bytes);
            } finally {
                memFree(data);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.info("Pipeline cache could not be saved; next start compiles from scratch");
        }
    }
}
