package net.vulkanmod112.vkimpl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectFD;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreFD;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.Kernel32;
import org.lwjgl.vulkan.KHRExternalMemoryFd;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.KHRExternalSemaphoreFd;
import org.lwjgl.vulkan.KHRExternalSemaphoreWin32;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryGetFdInfoKHR;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreGetFdInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreGetWin32HandleInfoKHR;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Locale;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;

/**
 * Platform layer for zero-copy Vulkan↔OpenGL sharing.
 *
 * The mechanism is identical everywhere — export the Vulkan allocation, import
 * it into GL — but the operating system decides what an "exported allocation"
 * is: a file descriptor on Linux, a HANDLE on Windows. Everything that differs
 * between the two lives here, so the renderers never mention either.
 */
final class Interop {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Interop");

    static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Vulkan handle type the renderers must request when exporting memory. */
    static final int MEMORY_HANDLE_TYPE = WINDOWS
            ? VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
            : VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

    /** Vulkan handle type the renderers must request when exporting semaphores. */
    static final int SEMAPHORE_HANDLE_TYPE = WINDOWS
            ? VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT
            : VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;

    /** Both GL_DEVICE_UUID_EXT and VkPhysicalDeviceIDProperties::deviceUUID are 16 bytes. */
    private static final int UUID_BYTES = 16;

    private static final String[] DEVICE_EXTENSIONS_FD = {
            "VK_KHR_external_memory_fd",
            "VK_KHR_external_semaphore_fd"
    };
    private static final String[] DEVICE_EXTENSIONS_WIN32 = {
            "VK_KHR_external_memory_win32",
            "VK_KHR_external_semaphore_win32"
    };

    private static final long CLOSE_HANDLE = WINDOWS
            ? Kernel32.getLibrary().getFunctionAddress("CloseHandle")
            : 0L;

    private Interop() {
    }

    /** Device extensions that must be present and enabled for interop to work. */
    static String[] deviceExtensions() {
        return WINDOWS ? DEVICE_EXTENSIONS_WIN32 : DEVICE_EXTENSIONS_FD;
    }

    /** True when the OpenGL driver exposes the matching import extensions. */
    static boolean supportedByGL(GLCapabilities caps) {
        if (!caps.GL_EXT_memory_object || !caps.GL_EXT_semaphore) {
            return false;
        }
        return WINDOWS
                ? caps.GL_EXT_memory_object_win32 && caps.GL_EXT_semaphore_win32
                : caps.GL_EXT_memory_object_fd && caps.GL_EXT_semaphore_fd;
    }

    static String glExtensionNames() {
        return WINDOWS
                ? "EXT_memory_object_win32/EXT_semaphore_win32"
                : "EXT_memory_object_fd/EXT_semaphore_fd";
    }

    /**
     * Fails unless OpenGL and Vulkan are driving the same physical GPU.
     *
     * Sharing memory between two different devices is not merely unsupported:
     * the importing driver dereferences a handle that means nothing to it and
     * takes the whole process down with a segfault, past any Java catch block.
     * On a hybrid machine this is the default outcome — the game's GL context
     * lands on the integrated GPU or on a software renderer while we pick the
     * discrete card — so the check has to happen before the first import.
     *
     * Both APIs expose the same 16-byte device UUID for exactly this purpose.
     */
    static void requireSameDevice(VkPhysicalDevice physicalDevice, int vulkanDeviceCount) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceIDProperties idProps = VkPhysicalDeviceIDProperties.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES);
            VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
                    .pNext(idProps.address());
            VK11.vkGetPhysicalDeviceProperties2(physicalDevice, props);
            byte[] vulkanUuid = new byte[UUID_BYTES];
            idProps.deviceUUID().get(vulkanUuid);
            byte[] vulkanDriverUuid = new byte[UUID_BYTES];
            idProps.driverUUID().get(vulkanDriverUuid);

            StringBuilder seen = new StringBuilder();
            if (matchesGlDevice(stack, vulkanUuid, seen)) {
                return;
            }
            if (seen.length() > 0) {
                // The driver answered, and answered with a different GPU.
                throw new IllegalStateException("OpenGL and Vulkan are on different GPUs — "
                        + "Vulkan device " + hex(vulkanUuid) + ", OpenGL device(s) " + seen
                        + " (GL renderer: " + GL11C.glGetString(GL11C.GL_RENDERER) + "). "
                        + "Zero-copy sharing would crash the process. On a hybrid system, launch the game "
                        + "with the discrete GPU selected for OpenGL too "
                        + "(on Linux: __NV_PRIME_RENDER_OFFLOAD=1 __GLX_VENDOR_LIBRARY_NAME=nvidia).");
            }

            // The extension is advertised but its queries return nothing. The
            // tempting fallback is the driver UUID, which the same extension
            // exposes without an index — but a query that answers with garbage
            // is not evidence of anything, and an OpenGL side broken enough to
            // fail here is not an OpenGL side to hand exported memory to. It
            // is logged for bug reports and nothing more.
            byte[] glDriverUuid = queryDriverUuid(stack);
            throw new IllegalStateException("Cannot confirm OpenGL and Vulkan are on the same GPU — "
                    + "the device UUID query returned nothing, and the driver UUID "
                    + (glDriverUuid == null ? "is unavailable too" : "reads " + hex(glDriverUuid)
                            + " against Vulkan's " + hex(vulkanDriverUuid))
                    + ", with " + vulkanDeviceCount + " GPU(s) visible to Vulkan "
                    + "(GL renderer: " + GL11C.glGetString(GL11C.GL_RENDERER) + "). "
                    + "Sharing memory across two devices would crash the process, so terrain stays on OpenGL.");
        }
    }

    /**
     * True when one of OpenGL's device UUIDs is the Vulkan one. Appends every
     * UUID the driver actually returned to {@code seen}, so an empty {@code seen}
     * on a false result means the query gave nothing rather than gave a mismatch.
     */
    private static boolean matchesGlDevice(MemoryStack stack, byte[] vulkanUuid, StringBuilder seen) {
        // Drain, not pop: GL keeps a queue of errors and returns one at a
        // time, so another mod leaving two behind would read as our failure.
        int drained = 0;
        while (GL11C.glGetError() != GL11C.GL_NO_ERROR && drained < 32) {
            drained++;
        }
        IntBuffer countBuf = stack.callocInt(1);
        GL11C.glGetIntegerv(EXTMemoryObject.GL_NUM_DEVICE_UUIDS_EXT, countBuf);
        int countError = GL11C.glGetError();
        int glDeviceCount = countBuf.get(0);
        LOGGER.info("Interop check on LWJGL {} / GL {} ({}): {} GL device(s), glGetError 0x{}, {} stale error(s)",
                org.lwjgl.Version.getVersion(), GL11C.glGetString(GL11C.GL_VERSION),
                GL11C.glGetString(GL11C.GL_RENDERER), glDeviceCount,
                Integer.toHexString(countError), drained);

        // Zeroed before each query so "the driver wrote nothing" is
        // distinguishable from "the driver wrote a different UUID".
        ByteBuffer glUuid = stack.calloc(UUID_BYTES);
        byte[] glBytes = new byte[UUID_BYTES];
        for (int i = 0; i < glDeviceCount; i++) {
            glUuid.clear();
            MemoryUtil.memSet(glUuid, 0);
            GL11C.glGetError();
            EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, i, glUuid);
            int glError = GL11C.glGetError();
            glUuid.get(glBytes);
            if (glError != GL11C.GL_NO_ERROR || isAllZero(glBytes)) {
                LOGGER.info("GL device {} of {}: query unusable (glGetError 0x{})",
                        i, glDeviceCount, Integer.toHexString(glError));
                continue;
            }
            if (java.util.Arrays.equals(vulkanUuid, glBytes)) {
                return true;
            }
            seen.append(seen.length() == 0 ? "" : ", ").append(hex(glBytes));
        }
        return false;
    }

    /** The driver UUID, or null when that query is unusable as well. */
    private static byte[] queryDriverUuid(MemoryStack stack) {
        ByteBuffer buf = stack.calloc(UUID_BYTES);
        MemoryUtil.memSet(buf, 0);
        GL11C.glGetError();
        EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObject.GL_DRIVER_UUID_EXT, buf);
        int glError = GL11C.glGetError();
        byte[] bytes = new byte[UUID_BYTES];
        buf.get(bytes);
        LOGGER.info("GL driver UUID: {} (glGetError 0x{})", hex(bytes), Integer.toHexString(glError));
        return glError == GL11C.GL_NO_ERROR && !isAllZero(bytes) ? bytes : null;
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Exports a device allocation and imports it as a GL memory object.
     *
     * {@code dedicated} must mirror whether the Vulkan allocation used
     * VkMemoryDedicatedAllocateInfo: GL assumes a different memory layout
     * otherwise and silently samples garbage.
     */
    static int importMemoryToGL(MemoryStack stack, VkDevice device, long memory, long size,
                                boolean dedicated) {
        IntBuffer pMemObj = stack.mallocInt(1);
        EXTMemoryObject.glCreateMemoryObjectsEXT(pMemObj);
        int memObj = pMemObj.get(0);
        if (dedicated) {
            EXTMemoryObject.glMemoryObjectParameterivEXT(memObj,
                    EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, stack.ints(GL11C.GL_TRUE));
        }
        if (WINDOWS) {
            VkMemoryGetWin32HandleInfoKHR info = VkMemoryGetWin32HandleInfoKHR.calloc(stack)
                    .sType(KHRExternalMemoryWin32.VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR)
                    .memory(memory)
                    .handleType(MEMORY_HANDLE_TYPE);
            PointerBuffer pHandle = stack.mallocPointer(1);
            check(KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR(device, info, pHandle),
                    "vkGetMemoryWin32HandleKHR");
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memObj, size,
                    EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, pHandle.get(0));
            closeHandle(pHandle.get(0));
        } else {
            VkMemoryGetFdInfoKHR info = VkMemoryGetFdInfoKHR.calloc(stack)
                    .sType(KHRExternalMemoryFd.VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR)
                    .memory(memory)
                    .handleType(MEMORY_HANDLE_TYPE);
            IntBuffer pFd = stack.mallocInt(1);
            check(KHRExternalMemoryFd.vkGetMemoryFdKHR(device, info, pFd), "vkGetMemoryFdKHR");
            // GL takes ownership of the descriptor and closes it with the object.
            EXTMemoryObjectFD.glImportMemoryFdEXT(memObj, size,
                    EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, pFd.get(0));
        }
        return memObj;
    }

    /** Exports a Vulkan semaphore and imports it as a GL semaphore. */
    static int importSemaphoreToGL(MemoryStack stack, VkDevice device, long semaphore) {
        IntBuffer pSem = stack.mallocInt(1);
        EXTSemaphore.glGenSemaphoresEXT(pSem);
        int glSem = pSem.get(0);
        if (WINDOWS) {
            VkSemaphoreGetWin32HandleInfoKHR info = VkSemaphoreGetWin32HandleInfoKHR.calloc(stack)
                    .sType(KHRExternalSemaphoreWin32.VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR)
                    .semaphore(semaphore)
                    .handleType(SEMAPHORE_HANDLE_TYPE);
            PointerBuffer pHandle = stack.mallocPointer(1);
            check(KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR(device, info, pHandle),
                    "vkGetSemaphoreWin32HandleKHR");
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(glSem,
                    EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, pHandle.get(0));
            closeHandle(pHandle.get(0));
        } else {
            VkSemaphoreGetFdInfoKHR info = VkSemaphoreGetFdInfoKHR.calloc(stack)
                    .sType(KHRExternalSemaphoreFd.VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR)
                    .semaphore(semaphore)
                    .handleType(SEMAPHORE_HANDLE_TYPE);
            IntBuffer pFd = stack.mallocInt(1);
            check(KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR(device, info, pFd),
                    "vkGetSemaphoreFdKHR");
            EXTSemaphoreFD.glImportSemaphoreFdEXT(glSem,
                    EXTSemaphoreFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, pFd.get(0));
        }
        return glSem;
    }

    /**
     * Releases an exported Win32 handle once it has been imported.
     *
     * Unlike a file descriptor on Linux, which GL takes ownership of, a Win32
     * handle stays ours after the import: every one we forget to close leaks
     * for the lifetime of the process. LWJGL has no CloseHandle binding, so it
     * is called through the kernel32 the loader already holds.
     */
    private static void closeHandle(long handle) {
        if (handle == 0L) {
            return;
        }
        if (CLOSE_HANDLE == 0L) {
            LOGGER.warn("CloseHandle unavailable; exported handle {} leaked", handle);
            return;
        }
        if (JNI.callPI(handle, CLOSE_HANDLE) == 0) {
            LOGGER.warn("CloseHandle failed for exported handle {}", handle);
        }
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + result);
        }
    }

}
