package net.vulkanmodnext.vkimpl;

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
import org.lwjgl.vulkan.VkExternalSemaphoreProperties;
import org.lwjgl.vulkan.VkMemoryGetFdInfoKHR;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceExternalSemaphoreInfo;
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

    private static final Logger LOGGER = LogManager.getLogger("VulkanModNext/Interop");

    static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /**
     * Whether an exported Windows semaphore is carried as a D3D12 fence handle
     * instead of an "opaque" Win32 handle.
     *
     * Both are legal per VK_KHR_external_semaphore_win32 and GL_EXT_semaphore_win32,
     * and until now this renderer used the opaque one everywhere. Opaque Win32
     * semaphore handles exist almost solely for Vulkan-to-Vulkan sharing across
     * processes; next to nothing exercises a graphics driver's GL-side consumer
     * of one. D3D12 fence handles are the interop primitive every DXGI-based
     * cross-API path actually uses (ANGLE, DXVK, wined3d, hybrid engines), so a
     * driver's fence-handle path gets tested by far more software than its
     * opaque one does. On a driver that accepts the opaque import, reports no
     * error, and then never signals the GPU-side wait — which is what this
     * looked like on the one machine it failed on — the fence path is the one
     * card left to try before assuming the extension itself is unusable there.
     *
     * -Dvulkanmodnext.d3d12FenceSemaphores=false restores the opaque path for
     * comparison.
     */
    static boolean D3D12_FENCE_SEMAPHORES;

    /** Vulkan handle type the renderers must request when exporting memory. */
    static final int MEMORY_HANDLE_TYPE = WINDOWS
            ? VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
            : VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

    /**
     * Vulkan handle type the renderers request when exporting semaphores.
     *
     * Not a constant, and that is the whole point. Creating a semaphore with an
     * export structure is not required to fail on a handle type the driver
     * cannot actually export — it may hand back an object that simply does not
     * work, and then the export itself returns success and produces a handle
     * the operating system does not recognise. That is not a hypothesis: asking
     * for a D3D12 fence on a card that answers "exportable=false" did exactly
     * that, four times, once per semaphore, and the only trace of it was
     * CloseHandle refusing all four.
     *
     * So the driver picks. {@link #logExternalSemaphoreSupport} asks it before
     * anything is created and writes the answer here.
     */
    private static int semaphoreHandleType = WINDOWS
            ? VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT
            : VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;

    static int semaphoreHandleType() {
        return semaphoreHandleType;
    }

    /** Both GL_DEVICE_UUID_EXT and VkPhysicalDeviceIDProperties::deviceUUID are 16 bytes. */
    private static final int UUID_BYTES = 16;

    /** Windows GENERIC_ALL: every right the importing side could want. */
    private static final int GENERIC_ALL = 0x10000000;

    /**
     * Puts the Win32 access rights in front of an export structure, and hands
     * back whatever should now start the chain.
     *
     * On anything but Windows this is the export structure unchanged, because
     * a file descriptor carries no rights to ask for. On Windows the rights are
     * only defaulted when the structure is missing, and a driver may default
     * them to nothing: the handle then imports cleanly and never signals, which
     * is indistinguishable from a hang until the card is reset.
     */
    /**
     * The same rights, for an exported memory handle.
     *
     * Separate structure, identical reasoning: without it the rights on the
     * handle are whatever the driver defaults to, and a handle imported without
     * the right to read is not refused — OpenGL takes it, samples it, and the
     * card stops on work it is not allowed to do.
     */
    static long appendWin32MemoryRights(MemoryStack stack, long exportInfo) {
        if (!WINDOWS) {
            return exportInfo;
        }
        org.lwjgl.vulkan.VkExportMemoryWin32HandleInfoKHR rights =
                org.lwjgl.vulkan.VkExportMemoryWin32HandleInfoKHR.calloc(stack)
                        .sType(KHRExternalMemoryWin32
                                .VK_STRUCTURE_TYPE_EXPORT_MEMORY_WIN32_HANDLE_INFO_KHR)
                        .pNext(exportInfo)
                        .pAttributes(null)
                        .dwAccess(GENERIC_ALL);
        return rights.address();
    }

    static long appendWin32SemaphoreRights(MemoryStack stack, long exportInfo) {
        if (!WINDOWS) {
            return exportInfo;
        }
        org.lwjgl.vulkan.VkExportSemaphoreWin32HandleInfoKHR rights =
                org.lwjgl.vulkan.VkExportSemaphoreWin32HandleInfoKHR.calloc(stack)
                        .sType(KHRExternalSemaphoreWin32
                                .VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_WIN32_HANDLE_INFO_KHR)
                        .pNext(exportInfo)
                        .pAttributes(null)
                        .dwAccess(GENERIC_ALL);
        return rights.address();
    }

    private static final String[] DEVICE_EXTENSIONS_FD = {
            "VK_KHR_external_memory_fd",
            "VK_KHR_external_semaphore_fd"
    };
    private static final String[] DEVICE_EXTENSIONS_WIN32 = {
            "VK_KHR_external_memory_win32",
            "VK_KHR_external_semaphore_win32"
    };

    /**
     * Looked up on first use rather than when this class loads.
     *
     * As a static field this was the very first thing that happened when the
     * Vulkan side was touched at all, and asking LWJGL for kernel32 asks LWJGL
     * for its own native library — which on Windows is not there yet at that
     * moment. The whole renderer fell over on
     * {@code UnsatisfiedLinkError: Failed to locate library: lwjgl.dll}, before
     * a single Vulkan call, and reported itself as "Vulkan never started on
     * this machine": an address needed once per exported handle decided whether
     * the mod ran at all.
     *
     * Linux never saw it, because the condition is false there and the ternary
     * has nothing to evaluate — which is exactly the shape of a fault that
     * cannot be found on the machine it is written on.
     *
     * Zero means "not looked up yet"; a failed lookup is remembered as -1 so
     * that a driver without the function is asked once rather than per handle.
     */
    private static long closeHandleAddress;

    private static long closeHandleFunction() {
        if (!WINDOWS) {
            return 0L;
        }
        if (closeHandleAddress == 0L) {
            try {
                closeHandleAddress = Kernel32.getLibrary().getFunctionAddress("CloseHandle");
            } catch (Throwable t) {
                LOGGER.warn("CloseHandle could not be looked up: {}", t.toString());
                closeHandleAddress = -1L;
            }
            if (closeHandleAddress == 0L) {
                closeHandleAddress = -1L;
            }
        }
        return closeHandleAddress == -1L ? 0L : closeHandleAddress;
    }

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
     * Logs whether this Vulkan driver actually admits an exported semaphore of
     * {@link #semaphoreHandleType} can leave the process and come back in
     * ({@code EXPORTABLE}/{@code IMPORTABLE}), instead of assuming it because
     * the device extension is present and every call along the chain returns
     * {@code VK_SUCCESS}.
     *
     * Nothing here has ever failed this query, on any driver — a Win32
     * semaphore handle that a driver cannot actually export or that a
     * different API cannot import back is exactly the situation this project
     * hit on one machine and could not explain from error codes alone,
     * because none of the calls involved are required to fail when the
     * combination is unsupported. This is the one place the driver is asked
     * outright, before the first frame ever depends on the answer.
     */
    static void logExternalSemaphoreSupport(VkPhysicalDevice physicalDevice) {
        if (!WINDOWS) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            int[] handleTypes = {
                    VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT,
                    VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT
            };
            String[] names = {"OPAQUE_WIN32", "D3D12_FENCE"};
            boolean[] usable = new boolean[2];
            for (int i = 0; i < handleTypes.length; i++) {
                VkPhysicalDeviceExternalSemaphoreInfo info =
                        VkPhysicalDeviceExternalSemaphoreInfo.calloc(stack)
                                .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO)
                                .handleType(handleTypes[i]);
                VkExternalSemaphoreProperties props = VkExternalSemaphoreProperties.calloc(stack)
                        .sType(VK11.VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES);
                VK11.vkGetPhysicalDeviceExternalSemaphoreProperties(physicalDevice, info, props);
                int features = props.externalSemaphoreFeatures();
                boolean exportable = (features & VK11.VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) != 0;
                boolean importable = (features & VK11.VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT) != 0;
                LOGGER.info("External semaphore support for {}: exportable={}, importable={}, "
                                + "compatibleHandleTypes=0x{}, exportFromImportedHandleTypes=0x{}",
                        names[i], exportable, importable,
                        Integer.toHexString(props.compatibleHandleTypes()),
                        Integer.toHexString(props.exportFromImportedHandleTypes()));
                usable[i] = exportable && importable;
            }

            // A fence is the better-worn road where it exists, but only where
            // the driver says it can hand one out. Preference never outranks
            // the answer: a type that cannot be exported produces a handle
            // that is not one, and nothing downstream notices until the wait
            // that never returns.
            boolean preferFence = !"false".equals(
                    System.getProperty("vulkanmodnext.d3d12FenceSemaphores"));
            if (preferFence && usable[1]) {
                semaphoreHandleType = VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT;
                D3D12_FENCE_SEMAPHORES = true;
            } else {
                semaphoreHandleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
                D3D12_FENCE_SEMAPHORES = false;
            }
            LOGGER.info("Exporting semaphores as {}", D3D12_FENCE_SEMAPHORES
                    ? "D3D12 fences" : "opaque Win32 handles");
            if (!usable[0] && !usable[1]) {
                LOGGER.warn("This driver can neither export nor import a semaphore OpenGL could "
                        + "wait on. Sharing frames by semaphore cannot work here — start with "
                        + "-Dvulkanmodnext.interopSemaphores=false.");
            }
        }
    }

    /**
     * True when one of OpenGL's device UUIDs is the Vulkan one. Appends every
     * UUID the driver actually returned to {@code seen}, so an empty {@code seen}
     * on a false result means the query gave nothing rather than gave a mismatch.
     */
    private static boolean matchesGlDevice(MemoryStack stack, byte[] vulkanUuid, StringBuilder seen) {
        for (byte[] glBytes : glDeviceUuids(stack, true)) {
            if (java.util.Arrays.equals(vulkanUuid, glBytes)) {
                return true;
            }
            seen.append(seen.length() == 0 ? "" : ", ").append(hex(glBytes));
        }
        return false;
    }

    /**
     * Every GPU the game's OpenGL context can name, by UUID.
     *
     * The one question worth asking before a device is chosen rather than
     * after. Which card the game runs on was decided by the driver long before
     * this mod loaded, and zero-copy sharing only works on that same card — so
     * picking the fastest GPU in the machine and then discovering it is not the
     * one OpenGL is on is a refusal where a match was available.
     *
     * @param verbose whether to write the version banner; wanted once per
     *                session, not once per candidate device
     */
    static java.util.List<byte[]> glDeviceUuids(MemoryStack stack, boolean verbose) {
        java.util.List<byte[]> found = new java.util.ArrayList<byte[]>();
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
        if (verbose) {
            LOGGER.info("Interop check on LWJGL {} / GL {} ({}): {} GL device(s), glGetError 0x{}, {} stale error(s)",
                    org.lwjgl.Version.getVersion(), GL11C.glGetString(GL11C.GL_VERSION),
                    GL11C.glGetString(GL11C.GL_RENDERER), glDeviceCount,
                    Integer.toHexString(countError), drained);
        }

        // Zeroed before each query so "the driver wrote nothing" is
        // distinguishable from "the driver wrote a different UUID".
        ByteBuffer glUuid = stack.calloc(UUID_BYTES);
        for (int i = 0; i < glDeviceCount; i++) {
            byte[] glBytes = new byte[UUID_BYTES];
            glUuid.clear();
            MemoryUtil.memSet(glUuid, 0);
            GL11C.glGetError();
            EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, i, glUuid);
            int glError = GL11C.glGetError();
            glUuid.get(glBytes);
            if (glError != GL11C.GL_NO_ERROR || isAllZero(glBytes)) {
                if (verbose) {
                    LOGGER.info("GL device {} of {}: query unusable (glGetError 0x{})",
                            i, glDeviceCount, Integer.toHexString(glError));
                }
                continue;
            }
            found.add(glBytes);
        }
        return found;
    }

    /** The 16-byte identity a Vulkan device shares with OpenGL, or null. */
    static byte[] deviceUuid(MemoryStack stack, VkPhysicalDevice device) {
        try {
            VkPhysicalDeviceIDProperties idProps = VkPhysicalDeviceIDProperties.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES);
            VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
                    .pNext(idProps.address());
            VK11.vkGetPhysicalDeviceProperties2(device, props);
            byte[] uuid = new byte[UUID_BYTES];
            idProps.deviceUUID().get(uuid);
            return isAllZero(uuid) ? null : uuid;
        } catch (Throwable t) {
            // A driver without Vulkan 1.1 has no identity to compare, which is
            // not an error here — it only means the choice falls back to
            // scoring, exactly as it did before this existed.
            return null;
        }
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
            retainHandle(pHandle.get(0));
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
                    .handleType(semaphoreHandleType());
            PointerBuffer pHandle = stack.mallocPointer(1);
            check(KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR(device, info, pHandle),
                    "vkGetSemaphoreWin32HandleKHR");
            int glHandleType = D3D12_FENCE_SEMAPHORES
                    ? EXTSemaphoreWin32.GL_HANDLE_TYPE_D3D12_FENCE_EXT
                    : EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(glSem, glHandleType, pHandle.get(0));
            // Checked here and nowhere else, because a refused import has no
            // other symptom: the semaphore object exists, the wait on it is
            // accepted, and it simply never returns. Better to fail loudly at
            // startup and fall back to vanilla than to hang the card.
            int error = org.lwjgl.opengl.GL11C.glGetError();
            if (error != 0) {
                throw new IllegalStateException("glImportSemaphoreWin32HandleEXT refused the "
                        + "exported semaphore (GL error 0x" + Integer.toHexString(error)
                        + "). Waiting on it from OpenGL would never return.");
            }
            retainHandle(pHandle.get(0));
        } else {
            VkSemaphoreGetFdInfoKHR info = VkSemaphoreGetFdInfoKHR.calloc(stack)
                    .sType(KHRExternalSemaphoreFd.VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR)
                    .semaphore(semaphore)
                    .handleType(semaphoreHandleType());
            IntBuffer pFd = stack.mallocInt(1);
            check(KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR(device, info, pFd),
                    "vkGetSemaphoreFdKHR");
            EXTSemaphoreFD.glImportSemaphoreFdEXT(glSem,
                    EXTSemaphoreFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, pFd.get(0));
        }
        // The driver's own opinion of what it just took. glGenSemaphoresEXT
        // alone does not make a semaphore real — the name only becomes an
        // object on a successful import — so a driver that quietly refused
        // says so here, and nowhere else until the wait that never returns.
        boolean real = EXTSemaphore.glIsSemaphoreEXT(glSem);
        LOGGER.info("Imported semaphore {}: the driver {} it a semaphore (glGetError 0x{})",
                glSem, real ? "calls" : "does NOT call",
                Integer.toHexString(org.lwjgl.opengl.GL11C.glGetError()));
        return glSem;
    }

    /** Exported Win32 handles still held open, closed when the device goes. */
    private static final java.util.List<Long> RETAINED = new java.util.ArrayList<Long>();

    /**
     * Restores the old behaviour — close the handle the instant the import
     * returns — with -Dvulkanmodnext.closeHandlesEarly=true, so the difference
     * can be measured rather than argued about.
     */
    private static final boolean CLOSE_EARLY =
            "true".equals(System.getProperty("vulkanmodnext.closeHandlesEarly"));

    /**
     * Holds an exported Win32 handle open until the Vulkan device is destroyed.
     *
     * Both extensions say to release ownership "when the handle is no longer
     * needed", and this used to close it the moment the import returned, on the
     * reading that the driver duplicates whatever it keeps. That reading is
     * true of every driver this was written against and is not guaranteed by
     * anything: a driver that stores the handle instead is left holding a
     * closed one, and then the object it names is dead while still looking
     * alive — a wait that never returns, memory that faults when read. Nothing
     * distinguishes that from the driver simply not supporting the import.
     *
     * The handle is genuinely no longer needed once the semaphore or memory it
     * names is gone, which is at device teardown. Holding it until then costs
     * a handful of handles and takes the ambiguity out.
     */
    private static void retainHandle(long handle) {
        if (handle == 0L) {
            return;
        }
        if (CLOSE_EARLY) {
            closeHandle(handle);
            return;
        }
        synchronized (RETAINED) {
            RETAINED.add(Long.valueOf(handle));
            retainedEver++;
        }
    }

    /** How many handles were ever retained, so growth can be seen from a log. */
    private static long retainedEver;

    /**
     * What this side of the interop is holding, for the diagnostics report.
     *
     * The open question this answers is whether a long session grows the
     * process's handle count. These are the handles the driver was given and
     * we deliberately did not close, so if the number climbs while nothing is
     * being created, something is re-importing per frame and every one of them
     * is a handle held until the game exits. A number that sits still is the
     * answer; a number that climbs is the bug, and it is not visible any other
     * way from inside the process.
     */
    static String handleSummary() {
        int held;
        long ever;
        synchronized (RETAINED) {
            held = RETAINED.size();
            // Read under the same lock it is written under. A long read
            // outside it is not even guaranteed to be one read, and the whole
            // point of this line is to be trusted when it says a number is
            // climbing.
            ever = retainedEver;
        }
        return held + " held, " + ever + " retained since start"
                + (CLOSE_EARLY ? " (closing early, for the Windows/AMD experiment)" : "");
    }

    /** Closes everything {@link #retainHandle} kept. Called at device teardown. */
    static void releaseImportedHandles() {
        synchronized (RETAINED) {
            if (!RETAINED.isEmpty()) {
                LOGGER.info("Closing {} exported handle(s) held for the driver", RETAINED.size());
            }
            for (Long handle : RETAINED) {
                closeHandle(handle.longValue());
            }
            RETAINED.clear();
        }
    }

    /**
     * Unlike a file descriptor on Linux, which GL takes ownership of, a Win32
     * handle stays ours after the import: every one we forget to close leaks
     * for the lifetime of the process. LWJGL has no CloseHandle binding, so it
     * is called through the kernel32 the loader already holds.
     */
    private static void closeHandle(long handle) {
        if (handle == 0L) {
            return;
        }
        long closeHandle = closeHandleFunction();
        if (closeHandle == 0L) {
            LOGGER.warn("CloseHandle unavailable; exported handle {} leaked", handle);
            return;
        }
        if (JNI.callPI(handle, closeHandle) == 0) {
            LOGGER.warn("CloseHandle failed for exported handle {}", handle);
        }
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + result);
        }
    }

}
