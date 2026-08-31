package net.vulkanmodnext.vkimpl;

import net.vulkanmodnext.VkStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static net.vulkanmodnext.VkStack.push;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Owns the Vulkan instance, the chosen card and the logical device with a
 * graphics queue. Everything drawn later hangs off this.
 *
 * <h2>Where this came from</h2>
 *
 * This is the setup half of the 1.12.2 mod's {@code VulkanContextImpl}, lifted
 * member by member with its comments, because those comments are the record of
 * what went wrong on somebody's machine and why the code is shaped as it is —
 * the hybrid-laptop device choice below is the clearest example. The other half
 * of that class is a facade over a renderer that does not exist here yet, and
 * it is not here.
 *
 * <h2>What changed on the way across, and nothing else did</h2>
 *
 * <ul>
 * <li>There is no isolated class loader and no bridge interface. The game is
 *     LWJGL 3 already, so this is an ordinary class in the mod.</li>
 * <li>The scratch stack is enlarged per thread by {@link VkStack} rather than
 *     requested through LWJGL's configuration, which on this version is read
 *     before any mod exists.</li>
 * <li>Vulkan 1.2 and ray tracing are not asked for: the bindings the game
 *     brings are older than both. See {@code pickApiVersion}.</li>
 * <li>The OpenGL function table is the game's, not one built here.</li>
 * </ul>
 */
public final class VkContext {

    private static final Logger LOGGER = LogManager.getLogger("VulkanModNext/Vulkan");

    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    private boolean validationWanted;

    private long debugMessenger;

    private org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT debugCallback;

    /**
     * Routes what the validation layer finds into this mod's own log.
     *
     * A lost device is reported by whatever call happens to notice, which is
     * never the call that caused it — the card is told to do something illegal
     * and dies some frames later, in a wait. The layer is the only thing that
     * sees the illegal command at the moment it is recorded, and it names it.
     */
    private void createDebugMessenger() {
        try (MemoryStack stack = push()) {
            debugCallback = org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT.create(
                    new org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXTI() {
                        @Override
                        public int invoke(int severity, int types, long pCallbackData, long pUserData) {
                            org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT data =
                                    org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                            String message = data.pMessageString();
                            if ((severity & org.lwjgl.vulkan.EXTDebugUtils
                                    .VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                                LOGGER.error("Vulkan validation: {}", message);
                            } else {
                                LOGGER.warn("Vulkan validation: {}", message);
                            }
                            return VK_FALSE;
                        }
                    });
            org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT info =
                    org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT.callocStack(stack)
                            .sType(org.lwjgl.vulkan.EXTDebugUtils
                                    .VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                            .messageSeverity(org.lwjgl.vulkan.EXTDebugUtils
                                            .VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                                    | org.lwjgl.vulkan.EXTDebugUtils
                                            .VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                            .messageType(org.lwjgl.vulkan.EXTDebugUtils
                                            .VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                    | org.lwjgl.vulkan.EXTDebugUtils
                                            .VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                            .pfnUserCallback(debugCallback);
            java.nio.LongBuffer pMessenger = stack.mallocLong(1);
            int result = org.lwjgl.vulkan.EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(
                    instance, info, null, pMessenger);
            if (result == VK_SUCCESS) {
                debugMessenger = pMessenger.get(0);
                LOGGER.info("Validation findings will appear in this log");
            } else {
                LOGGER.warn("Could not attach the validation reporter ({})", result);
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not attach the validation reporter", t);
        }
    }

    private VkInstance instance;

    private VkPhysicalDevice physicalDevice;

    private VkDevice device;

    private VkQueue graphicsQueue;

    private int graphicsQueueFamily = -1;

    /** How many GPUs Vulkan enumerated; named in the interop mismatch message. */
    private int physicalDeviceCount;

    private boolean initialized;

    private String gpuSummary = "Vulkan not initialized";

    /** Every GPU seen at startup, one per line, for the diagnostics report. */
    private StringBuilder gpuList = new StringBuilder();

    /** Total device-local memory, filled in when the GPU is selected. */
    private int vramMegabytes;

    /** Whether the driver can report per-heap usage and budget (VK_EXT_memory_budget). */
    private boolean memoryBudgetSupported;

    private boolean interopCapable;

    private boolean multiDrawIndirect;

    private boolean glCapsReady;

    private boolean glTableReady;

    /** Whether one indirect draw may carry more than one command. */
    public boolean canMultiDrawIndirect() {
        return multiDrawIndirect;
    }

    /**
     * The newest core version this loader will admit to, which here is 1.1.
     *
     * 1.1 is the floor and always has been: external memory, the thing this
     * whole renderer is built on, is core there. On 1.12.2 this also asked for
     * 1.2, because buffer device addresses are core in it and an acceleration
     * structure is built out of addresses rather than bound buffers.
     *
     * <p>Here it cannot: the game brings LWJGL 3.2.2, which predates Vulkan 1.2
     * and has neither the constant nor the ray tracing extensions. That is the
     * entire price of using the game's own bindings instead of shipping newer
     * ones, and it is a price in ray tracing only — which is not what this port
     * is for.
     */
    private int pickApiVersion() {
        int supported = VK.getInstanceVersionSupported();
        int major = VK_VERSION_MAJOR(supported);
        int minor = VK_VERSION_MINOR(supported);
        if (major > 1 || (major == 1 && minor >= 1)) {
            return org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
        }
        return VK_API_VERSION_1_0;
    }

    private void createInstance() {
        try (MemoryStack stack = push()) {
            VkApplicationInfo appInfo = VkApplicationInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Minecraft 1.12.2"))
                    .applicationVersion(VK_MAKE_VERSION(1, 12, 2))
                    .pEngineName(stack.UTF8("VulkanMod 1.16.5"))
                    .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(pickApiVersion());

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo);

            boolean validationAsked = Boolean.getBoolean("vulkanmodnext.validation");
            if (validationAsked && !isValidationLayerAvailable(stack)) {
                // Said out loud because the alternative is what already
                // happened: a whole session run to read an answer that was
                // never going to be written. A capability this mod asked for
                // and did not get is a log line, always.
                LOGGER.warn("{} was asked for and is not installed on this machine — nothing will"
                        + " be validated. Install the Vulkan validation layers and run again.",
                        VALIDATION_LAYER);
            }
            if (validationAsked && isValidationLayerAvailable(stack)) {
                LOGGER.info("Enabling {}", VALIDATION_LAYER);
                // Without this the layer writes its findings to the process
                // output, where a modded 1.12 client buries them under
                // everything else. They belong in our own log, next to the
                // frame they are about.
                PointerBuffer extensions = stack.mallocPointer(1);
                extensions.put(0, memAddress(stack.UTF8(
                        org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)));
                createInfo.ppEnabledExtensionNames(extensions);
                validationWanted = true;
                // put(long) + memAddress instead of put(ByteBuffer): compiles against
                // both LWJGL 2's and LWJGL 3's org.lwjgl.PointerBuffer
                // put(int, long): the only PointerBuffer write API whose descriptor is
                // identical in LWJGL 2 (compile classpath) and LWJGL 3 (runtime)
                PointerBuffer layers = stack.mallocPointer(1);
                layers.put(0, memAddress(stack.UTF8(VALIDATION_LAYER)));
                createInfo.ppEnabledLayerNames(layers);
            }

            PointerBuffer pInstance = stack.mallocPointer(1);
            check(vkCreateInstance(createInfo, null, pInstance), "vkCreateInstance");
            this.instance = wrapInstance(pInstance.get(0), createInfo);
            LOGGER.info("VkInstance created (loader reports Vulkan {})", apiVersionString(VK.getInstanceVersionSupported()));
            if (validationWanted) {
                createDebugMessenger();
            }
        }
    }

    /**
     * Wraps the raw instance handle, translating the one failure this step has.
     *
     * Constructing a VkInstance is not the cheap wrapper it looks like: LWJGL
     * lists the extensions of every physical device on the thread's scratch
     * stack to work out which entry points exist. When that stack is too small
     * the throw is an OutOfMemoryError reading "Out of stack space", from a
     * line inside LWJGL, and it says nothing about which knob is short or that
     * the amount of heap the player gave the game has anything to do with it —
     * it does not. Reported as-is it sends people to raise their allocated
     * memory, which cannot help.
     */
    private VkInstance wrapInstance(long handle, VkInstanceCreateInfo createInfo) {
        try {
            return new VkInstance(handle, createInfo);
        } catch (OutOfMemoryError e) {
            throw new net.vulkanmodnext.VulkanUnavailableException(
                    "Listing this machine's Vulkan extensions did not fit the "
                    + net.vulkanmodnext.VkStack.sizeKb() + " KiB of scratch space reserved for it."
                    + measureExtensions(handle)
                    + " This is a limit inside the mod, not the memory given to the game.", e);
        }
    }

    /**
     * Asks the same two questions LWJGL just choked on, and answers them where
     * they can do some good: how many devices Vulkan sees and how many
     * extensions each of them lists.
     *
     * Raising the budget fourfold changed nothing on the machine this was
     * written for, which rules out a budget that is merely too small and leaves
     * three possibilities that no amount of guessing separates — a machine
     * genuinely listing that many devices, a driver answering with a nonsense
     * count, or a setting that never reached LWJGL at all. One number tells
     * them apart, and the number was never printed anywhere.
     *
     * Deliberately allocated on the heap and called through the loader's own
     * exported entry points rather than the typed API: we are here precisely
     * because the scratch stack is exhausted, and a VkInstance — the thing the
     * typed calls need — is what could not be constructed.
     */
    private static String measureExtensions(long handle) {
        java.nio.IntBuffer count = null;
        java.nio.LongBuffer devices = null;
        try {
            org.lwjgl.system.FunctionProvider loader = VK.getFunctionProvider();
            long listDevices = loader.getFunctionAddress("vkEnumeratePhysicalDevices");
            long listExtensions = loader.getFunctionAddress("vkEnumerateDeviceExtensionProperties");
            if (listDevices == 0L || listExtensions == 0L) {
                return "";
            }
            count = org.lwjgl.system.MemoryUtil.memAllocInt(1);
            count.put(0, 0);
            org.lwjgl.system.JNI.callPPPI(handle, org.lwjgl.system.MemoryUtil.memAddress(count), 0L, listDevices);
            int deviceCount = count.get(0);
            if (deviceCount <= 0) {
                return " Vulkan reports " + deviceCount + " devices.";
            }
            // Pointers, held as longs: this build exists only for 64-bit.
            devices = org.lwjgl.system.MemoryUtil.memAllocLong(deviceCount);
            count.put(0, deviceCount);
            org.lwjgl.system.JNI.callPPPI(handle, org.lwjgl.system.MemoryUtil.memAddress(count),
                    org.lwjgl.system.MemoryUtil.memAddress(devices), listDevices);

            StringBuilder each = new StringBuilder();
            long total = 0;
            for (int i = 0; i < deviceCount; i++) {
                count.put(0, 0);
                org.lwjgl.system.JNI.callPPPPI(devices.get(i), 0L,
                        org.lwjgl.system.MemoryUtil.memAddress(count), 0L, listExtensions);
                int listed = count.get(0);
                total += listed;
                if (i < 8) {
                    each.append(i == 0 ? "" : ", ").append(listed);
                }
            }
            return " Vulkan lists " + deviceCount + " device(s) with " + total + " extensions in all ("
                    + each + (deviceCount > 8 ? ", ..." : "") + "), which needs "
                    + (total * VK_EXTENSION_ENTRY_BYTES / 1024) + " KiB.";
        } catch (Throwable t) {
            // A diagnostic must never replace the failure it is describing.
            return " Counting them failed as well (" + t + ").";
        } finally {
            if (devices != null) {
                org.lwjgl.system.MemoryUtil.memFree(devices);
            }
            if (count != null) {
                org.lwjgl.system.MemoryUtil.memFree(count);
            }
        }
    }

    /** VkExtensionProperties: 256 bytes of name and a version. */
    private static final int VK_EXTENSION_ENTRY_BYTES = 260;

    private boolean isValidationLayerAvailable(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateInstanceLayerProperties(count, null);
        if (count.get(0) == 0) {
            return false;
        }
        VkLayerProperties.Buffer layers = VkLayerProperties.mallocStack(count.get(0), stack);
        vkEnumerateInstanceLayerProperties(count, layers);
        for (int i = 0; i < layers.capacity(); i++) {
            if (VALIDATION_LAYER.equals(layers.get(i).layerNameString())) {
                return true;
            }
        }
        return false;
    }

    private void pickPhysicalDevice() {
        try (MemoryStack stack = push()) {
            IntBuffer count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
            int deviceCount = count.get(0);
            if (deviceCount == 0) {
                throw new IllegalStateException("No Vulkan-capable GPU found");
            }

            PointerBuffer handles = stack.mallocPointer(deviceCount);
            check(vkEnumeratePhysicalDevices(instance, count, handles), "vkEnumeratePhysicalDevices");

            // Which cards the game's OpenGL context can name. Asked once, up
            // front, because it changes what "best" means: the fastest GPU in
            // the machine is worth nothing here if it is not the one OpenGL is
            // already on — sharing memory across two devices is refused later
            // anyway, and the refusal is total.
            java.util.List<byte[]> glDevices = glDeviceUuidsQuietly();
            int requested = requestedDeviceIndex();

            VkPhysicalDevice best = null;
            int bestScore = -1;
            int bestIndex = -1;
            this.gpuList = new StringBuilder();
            for (int i = 0; i < deviceCount; i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(handles.get(i), instance);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.mallocStack(stack);
                vkGetPhysicalDeviceProperties(candidate, props);

                int score = score(candidate, props, stack);
                boolean matchesGl = score >= 0 && matchesAnyGlDevice(stack, candidate, glDevices);
                if (matchesGl) {
                    // Larger than any gap the device type can produce, because
                    // this is not a preference between two usable choices. The
                    // other card cannot be used at all.
                    score += 5000;
                }
                if (requested >= 0) {
                    // An explicit choice overrides both, and is still refused a
                    // device with no graphics queue.
                    score = score < 0 ? -1 : (i == requested ? 100000 : 0);
                }
                // The extension count is here because it is what sizes the
                // scratch stack this very startup nearly ran out of, and a log
                // that reports it turns the next such report into one line
                // instead of a guess. See net.vulkanmodnext.VkStack.
                IntBuffer extensions = stack.mallocInt(1);
                vkEnumerateDeviceExtensionProperties(candidate, (String) null, extensions, null);
                LOGGER.info("GPU {}: {} ({}, Vulkan {}, {} extensions, score {}{})",
                        i, props.deviceNameString(), deviceTypeName(props.deviceType()),
                        apiVersionString(props.apiVersion()), extensions.get(0), score,
                        matchesGl ? ", the one OpenGL is on" : "");
                gpuList.append(i).append(": ").append(props.deviceNameString())
                        .append(" (").append(deviceTypeName(props.deviceType()))
                        .append(matchesGl ? ", OpenGL is here" : "")
                        .append(score < 0 ? ", no graphics queue" : "")
                        .append(")\n");
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                    bestIndex = i;
                }
            }
            if (bestScore < 0) {
                throw new IllegalStateException("No GPU with a graphics queue found");
            }
            if (requested >= 0 && requested != bestIndex) {
                LOGGER.warn("GPU {} was asked for and is not usable; falling back to GPU {}",
                        requested, bestIndex);
            }
            if (!glDevices.isEmpty() && bestScore < 5000 && requested < 0) {
                // Said here rather than left to the import failure, because at
                // this point it is still a sentence about which card to launch
                // the game on, and forty lines later it is a crash.
                LOGGER.warn("No Vulkan GPU matches the one OpenGL is running on. Terrain will stay "
                        + "on OpenGL. On a hybrid machine, launch the game with the same card "
                        + "selected for OpenGL, or pick another GPU in the mod's settings.");
            }

            this.physicalDevice = best;
            this.physicalDeviceCount = deviceCount;
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.mallocStack(stack);
            vkGetPhysicalDeviceProperties(best, props);
            this.graphicsQueueFamily = findGraphicsQueueFamily(best, stack);
            this.gpuSummary = props.deviceNameString() + " (Vulkan " + apiVersionString(props.apiVersion()) + ")";
            LOGGER.info("Selected GPU: {} (graphics queue family {})", props.deviceNameString(), graphicsQueueFamily);
            logMemoryHeaps(stack);
        }
    }

    /**
     * Which GPU the settings screen asked for, or -1 for automatic.
     *
     * Read once at startup because that is the only moment it can matter: the
     * device is chosen before anything else exists, and changing it later would
     * mean tearing down every resource in this class.
     */
    private static int requestedDeviceIndex() {
        try {
            return Integer.parseInt(System.getProperty("vulkanmodnext.vulkanDevice", "-1"));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * The GL side's device list, or an empty one when it cannot be had.
     *
     * Quiet about failure on purpose. This runs before the mod knows whether
     * interop is possible at all, so a driver without the extension is an
     * ordinary case and not worth a warning — it only means the choice falls
     * back to scoring by device type, which is what it always did.
     */
    private java.util.List<byte[]> glDeviceUuidsQuietly() {
        try {
            ensureGlFunctionTable();
            try (MemoryStack stack = push()) {
                java.util.List<byte[]> found = Interop.glDeviceUuids(stack, false);
                LOGGER.info("OpenGL names {} device(s) before the Vulkan device is chosen", found.size());
                return found;
            }
        } catch (Throwable t) {
            // Said out loud rather than swallowed. A silent empty answer here
            // looks exactly like "no match exists", and the difference between
            // those two decides whether a hybrid laptop renders or refuses —
            // this mod has lost five settings to checks that failed quietly.
            LOGGER.info("Could not ask OpenGL which GPUs it has before choosing one ({}); "
                    + "falling back to choosing by device type", t.toString());
            return java.util.Collections.emptyList();
        }
    }

    private static boolean matchesAnyGlDevice(MemoryStack stack, VkPhysicalDevice candidate,
                                              java.util.List<byte[]> glDevices) {
        if (glDevices.isEmpty()) {
            return false;
        }
        byte[] uuid = Interop.deviceUuid(stack, candidate);
        if (uuid == null) {
            return false;
        }
        for (byte[] gl : glDevices) {
            if (java.util.Arrays.equals(uuid, gl)) {
                return true;
            }
        }
        return false;
    }

    private int score(VkPhysicalDevice device, VkPhysicalDeviceProperties props, MemoryStack stack) {
        if (findGraphicsQueueFamily(device, stack) < 0) {
            return -1; // unusable for rendering
        }
        switch (props.deviceType()) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return 1000;
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return 500;
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return 250;
            default: return 100;
        }
    }

    private int findGraphicsQueueFamily(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.mallocStack(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                return i;
            }
        }
        return -1;
    }

    public VkPhysicalDevice getPhysicalDevice() {
        return physicalDevice;
    }

    private void logMemoryHeaps(MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.mallocStack(stack);
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);
        long deviceLocalMiB = 0;
        for (int i = 0; i < memProps.memoryHeapCount(); i++) {
            long sizeMiB = memProps.memoryHeaps(i).size() / (1024 * 1024);
            boolean deviceLocal = (memProps.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0;
            if (deviceLocal) {
                deviceLocalMiB += sizeMiB;
            }
            LOGGER.info("Memory heap {}: {} MiB{}", i, sizeMiB, deviceLocal ? " (VRAM)" : "");
        }
        // On integrated GPUs every heap is device-local and this is really
        // shared system RAM, which is exactly why the budget defaults to a
        // fraction of it rather than to a fixed size.
        this.vramMegabytes = (int) Math.min(Integer.MAX_VALUE, deviceLocalMiB);
    }

    /** Lets the driver tell us how much of each heap it considers spoken for. */
    private static final String MEMORY_BUDGET_EXTENSION =
            org.lwjgl.vulkan.EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME;

    /** Device extensions needed to share images and semaphores with OpenGL (platform specific). */
    private static final String[] INTEROP_EXTENSIONS = Interop.deviceExtensions();

    /** Whatever of the above the driver actually offers; missing ones are simply not asked for. */
    private String[] deviceExtensionsToEnable() {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (interopCapable) {
            java.util.Collections.addAll(names, INTEROP_EXTENSIONS);
        }
        if (memoryBudgetSupported) {
            names.add(MEMORY_BUDGET_EXTENSION);
        }
        return names.toArray(new String[0]);
    }

    private boolean hasDeviceExtension(MemoryStack stack, String name) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, count, null);
        org.lwjgl.vulkan.VkExtensionProperties.Buffer available =
                org.lwjgl.vulkan.VkExtensionProperties.mallocStack(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, count, available);
        for (int i = 0; i < available.capacity(); i++) {
            if (name.equals(available.get(i).extensionNameString())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasInteropExtensions(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, count, null);
        org.lwjgl.vulkan.VkExtensionProperties.Buffer available =
                org.lwjgl.vulkan.VkExtensionProperties.mallocStack(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, count, available);
        int found = 0;
        for (int i = 0; i < available.capacity(); i++) {
            for (String wanted : INTEROP_EXTENSIONS) {
                if (wanted.equals(available.get(i).extensionNameString())) {
                    found++;
                }
            }
        }
        return found == INTEROP_EXTENSIONS.length;
    }

    private void createLogicalDevice() {
        try (MemoryStack stack = push()) {
            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.callocStack(1, stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(graphicsQueueFamily)
                    .pQueuePriorities(stack.floats(1.0f));

            // Out-of-bounds reads return zero instead of taking the GPU down.
            //
            // This renderer mirrors a buffer the game built and indexes a second
            // buffer beside it by vertex number, and both are addressed through
            // an indirect draw's vertexOffset rather than through anything this
            // code can bounds-check at the point of use. Without this, one wrong
            // offset anywhere in that chain is an MMU fault, and an MMU fault is
            // not a wrong pixel: the device is lost, every queue with it, and
            // the session ends — which is what happened, once, with a page
            // faulted at an address no buffer here reaches.
            //
            // It is a core Vulkan feature rather than an extension, so it is
            // always available. What it costs is a bounds check the hardware was
            // built to do; what it buys is that the worst case becomes terrain
            // shaded as though it were made of nothing, which can be seen,
            // reported and found.
            // multiDrawIndirect, and this renderer has been drawing without it.
            //
            // Every terrain layer is one vkCmdDrawIndexedIndirect with as many
            // commands as there are chunks, and a draw count above one is only
            // allowed with this feature enabled. It was never asked for. No
            // driver refused — they all simply did it — which is precisely the
            // sort of thing that works everywhere until the card it does not
            // work on is somebody else's.
            VkPhysicalDeviceFeatures available = VkPhysicalDeviceFeatures.mallocStack(stack);
            vkGetPhysicalDeviceFeatures(physicalDevice, available);
            this.multiDrawIndirect = available.multiDrawIndirect();
            if (!multiDrawIndirect) {
                LOGGER.warn("This driver cannot draw more than one indirect command at a time; "
                        + "terrain chunks will be drawn one command each");
            }
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.callocStack(stack)
                    .robustBufferAccess(true)
                    .multiDrawIndirect(multiDrawIndirect);

            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueInfo)
                    .pEnabledFeatures(features);

            this.interopCapable = hasInteropExtensions(stack);
            if (!interopCapable) {
                LOGGER.warn("Driver lacks {} — zero-copy GL interop unavailable",
                        java.util.Arrays.toString(INTEROP_EXTENSIONS));
            }
            // Purely diagnostic, and only worth asking for on Vulkan 1.1, where
            // the query it feeds is core. Knowing how close the driver thinks it
            // is to the edge is the difference between diagnosing an eviction
            // stall and guessing at one.
            this.memoryBudgetSupported = pickApiVersion() >= org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1
                    && hasDeviceExtension(stack, MEMORY_BUDGET_EXTENSION);
            // Ray tracing is asked for at this point on 1.12.2. It is not asked
            // for here and cannot be: see pickApiVersion. When it is wanted on
            // this version the whole of it comes back, together with a newer
            // LWJGL and the class loader that has to keep that apart from the
            // game's own.

            String[] wanted = deviceExtensionsToEnable();
            if (wanted.length != 0) {
                PointerBuffer extensions = stack.mallocPointer(wanted.length);
                for (int i = 0; i < wanted.length; i++) {
                    extensions.put(i, memAddress(stack.UTF8(wanted[i])));
                }
                deviceInfo.ppEnabledExtensionNames(extensions);
            }
            if (interopCapable) {
                LOGGER.info("External memory extensions enabled for OpenGL interop");
            }

            PointerBuffer pDevice = stack.mallocPointer(1);
            check(vkCreateDevice(physicalDevice, deviceInfo, null, pDevice), "vkCreateDevice");
            this.device = new VkDevice(pDevice.get(0), physicalDevice, deviceInfo);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsQueueFamily, 0, pQueue);
            this.graphicsQueue = new VkQueue(pQueue.get(0), device);
            LOGGER.info("Logical device and graphics queue created");
        }
    }

    /**
     * Creates LWJGL 3's GL function table for the client thread (the game's
     * context was created by LWJGL 2, so this world never did it) and checks
     * the interop extensions. Must precede any GL11C/EXT* call in vkimpl.
     */
    public synchronized void ensureGlCapabilities() {
        if (glCapsReady) {
            return;
        }
        ensureGlFunctionTable();
        Interop.requireSameDevice(physicalDevice, physicalDeviceCount);
        Interop.logExternalSemaphoreSupport(physicalDevice);
        glCapsReady = true;
    }

    /**
     * The GL function table alone, without the checks that need a chosen
     * device.
     *
     * Split out because the device is chosen partly from what OpenGL says, and
     * the full check asks whether the chosen device matches — a question with
     * no answer yet at the point the choice is being made. Asking it anyway
     * threw a null pointer that the caller swallowed, and the whole feature
     * silently did nothing: the mod picked by device type as it always had, and
     * the log line saying so was the only sign.
     */
    private synchronized void ensureGlFunctionTable() {
        if (glTableReady) {
            return;
        }
        // Asked for rather than created. On 1.12.2 the game's context was made
        // by LWJGL 2, so nothing in this half had ever built a function table
        // and it had to call createCapabilities itself. Here the game is LWJGL
        // 3 and built the table when it opened its window; building a second
        // one would be a second answer to a question that already has one.
        GLCapabilities caps = GL.getCapabilities();
        if (!Interop.supportedByGL(caps)) {
            throw new IllegalStateException("OpenGL driver lacks " + Interop.glExtensionNames());
        }
        glTableReady = true;
    }


    private static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(call + " failed with VkResult " + result);
        }
    }

    private static String apiVersionString(int version) {
        return VK_VERSION_MAJOR(version) + "." + VK_VERSION_MINOR(version) + "." + VK_VERSION_PATCH(version);
    }

    private static String deviceTypeName(int type) {
        switch (type) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "discrete";
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "virtual";
            case VK_PHYSICAL_DEVICE_TYPE_CPU: return "software";
            default: return "other";
        }
    }

    /**
     * Brings Vulkan up. Safe to call from anywhere; does its work once.
     *
     * Shorter than its 1.12.2 twin by exactly the thing this version does not
     * need: there are no natives of ours to unpack, because we use the ones the
     * game already loaded.
     */
    public synchronized void init() {
        if (initialized) {
            return;
        }
        long start = System.nanoTime();
        // Before the first LWJGL Vulkan call on this thread, not merely before
        // ours: the allocation that overflows is made inside LWJGL.
        VkStack.ensure();

        createInstance();
        pickPhysicalDevice();
        createLogicalDevice();

        // Built here rather than on the first upload. On 1.12.2 the render
        // thread mirrored the first chunk and created it on the way; here every
        // chunk arrives on a builder thread, and those must never be the ones
        // constructing it — two of them would build two.
        chunkMirror = new VkChunkMirror(this);
        chunkMirror.prime();

        initialized = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this::destroy, "VulkanModNext-Shutdown"));
        LOGGER.info("Vulkan context ready in {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    public synchronized boolean isReady() {
        return initialized;
    }

    public String gpuSummary() {
        return gpuSummary;
    }

    public String gpuList() {
        return gpuList.toString();
    }

    public boolean isInteropCapable() {
        return interopCapable;
    }

    public int vramMegabytes() {
        return vramMegabytes;
    }

    /**
     * Spelled the way the 1.12.2 mod spells them.
     *
     * Not a preference: every file of the Vulkan half that arrives later calls
     * these by these names, and a rename here would turn a mechanical copy into
     * a hand edit for each one of them.
     */
    public VkDevice getDevice() {
        return device;
    }

    public VkQueue getGraphicsQueue() {
        return graphicsQueue;
    }

    public int getGraphicsQueueFamily() {
        return graphicsQueueFamily;
    }

    /**
     * Volatile because the chunk builder threads read it without the monitor:
     * the volatile write publishes the fully constructed mirror to them.
     */
    private volatile VkChunkMirror chunkMirror;

        public synchronized void mirrorChunkBuffer(int slot, ByteBuffer data) {
        if (!initialized) {
            return;
        }
        if (chunkMirror == null) {
            chunkMirror = new VkChunkMirror(this);
        chunkMirror.prime();
        }
        chunkMirror.upload(slot, data);
    }

        public boolean stageChunkBuffer(int slot, ByteBuffer data) {
        // Deliberately not synchronized: this runs on the game's chunk builder
        // threads, and taking the context monitor would serialise them against
        // the render thread — the very cost this exists to remove. The mirror
        // guards the little state involved with a lock of its own.
        VkChunkMirror mirror = chunkMirror;
        if (!initialized || !interopCapable || mirror == null) {
            return false;
        }
        return mirror.stageFromWorker(slot, data);
    }

        public synchronized void releaseChunkBuffer(int slot) {
        if (chunkMirror != null) {
            chunkMirror.release(slot);
        }
    }

        public void noteChunkLayer(int slot, boolean translucent) {
        // Not synchronized and deliberately so: this runs on the chunk builder
        // threads, once per upload, ahead of geometry that is copied without
        // this monitor either. What it writes is one boolean in an array the
        // copy reads, and a stale read costs a chunk that was not sorted --
        // which is the safe direction, since an unsorted chunk simply draws
        // whole.
        VkChunkMirror mirror = chunkMirror;
        if (mirror != null) {
            mirror.noteLayer(slot, translucent);
        }
    }

        public synchronized String chunkMirrorStats() {
        return chunkMirror != null ? chunkMirror.stats() : "mirrored VBOs: 0";
    }


    /**
     * Ray tracing, answered once and the same way every time.
     *
     * Not "off in the settings" and not "the driver refused" — neither of those
     * is true. The bindings the game brings are older than the extensions, so
     * the question was never put to the driver at all. The status says exactly
     * that, because a report that blames somebody's card for our own build is
     * how a week of their time goes missing.
     */
    public boolean isRayTracingEnabled() {
        return false;
    }

    public boolean isRayQuerySupported() {
        return false;
    }

    public String rayTracingStatus() {
        return "not asked for — the LWJGL this version of Minecraft ships (3.2.2) has neither "
                + "Vulkan 1.2 nor KHR_acceleration_structure";
    }

        public synchronized boolean renderTerrainLayer(int layerOrdinal, int[] chunks, int chunkCount,
                                                   float[] mvp, double viewX, double viewY, double viewZ,
                                                   int fbWidth, int fbHeight) {
        if (!initialized || !interopCapable || chunkMirror == null) {
            return false;
        }
        return terrainRenderer().renderLayer(layerOrdinal, chunks, chunkCount, mvp,
                viewX, viewY, viewZ, fbWidth, fbHeight, chunkMirror);
    }

    public synchronized void updateAtlas(int atlasGlTextureId) {
        if (!initialized || !interopCapable) {
            return;
        }
        terrainRenderer().updateAtlas(atlasGlTextureId);
    }

        public synchronized void setLightmap(int lightmapGlTextureId) {
        if (!initialized || !interopCapable) {
            return;
        }
        terrainRenderer().setLightmap(lightmapGlTextureId);
    }

    private VkTerrainRenderer terrainRenderer;

    private VkTerrainRenderer terrainRenderer() {
        if (terrainRenderer == null) {
            terrainRenderer = new VkTerrainRenderer(this);
        }
        return terrainRenderer;
    }

    public synchronized String terrainDiagnostics() {
        StringBuilder sb = new StringBuilder();
        if (terrainRenderer != null) {
            terrainRenderer.appendDiagnostics(sb);
        }
        return sb.toString();
    }

    /**
     * The per-frame numbers the terrain shader needs beyond geometry.
     *
     * Each one guards on the same three things and forwards. They are separate
     * rather than one call because the game hands them over at different
     * moments and from different places, and bundling them would mean holding
     * a value from one part of the frame until another part is ready.
     */
    public synchronized void setSunDirection(float[] sun) {
        if (initialized && interopCapable) {
            terrainRenderer().setSunDirection(sun);
        }
    }

    public synchronized void setFogState(float[] fog) {
        if (initialized && interopCapable) {
            terrainRenderer().setFogState(fog);
        }
    }

    public synchronized void setRainStrength(float rain) {
        if (initialized && interopCapable) {
            terrainRenderer().setRainStrength(rain);
        }
    }

    public synchronized void updateCameraOffset(float[] offset) {
        if (initialized && interopCapable) {
            terrainRenderer().setCameraOffset(offset);
        }
    }

    public synchronized int sharedColourTexture() {
        return terrainRenderer == null ? 0 : terrainRenderer.sharedColourTexture();
    }

    private VkInteropRenderer interopRenderer;

    /**
     * Brings up the shared image, or says why it could not.
     *
     * Lifted from the 1.12.2 mod with its failure path intact. The awkward
     * shape — the renderer declared outside the try — is the point of it: a
     * renderer whose construction throws is never stored anywhere, so this is
     * the only moment anything can still reach the exported image, the GL
     * texture built on it and two imported semaphores. After it they belong to
     * nobody until the process ends.
     */
    public synchronized boolean initInterop(int width, int height) {
        if (!initialized || !interopCapable) {
            return false;
        }
        if (interopRenderer == null) {
            VkInteropRenderer renderer = null;
            try {
                renderer = new VkInteropRenderer(this, width, height);
                renderer.init();
                interopRenderer = renderer;
            } catch (Throwable t) {
                LOGGER.error("Zero-copy GL interop initialization failed", t);
                interopCapable = false;
                try {
                    if (renderer != null) {
                        renderer.destroy();
                    }
                } catch (Throwable ignored) {
                    // A failure while cleaning up must not replace the failure
                    // being reported: that one says why interop is off.
                }
                return false;
            }
        }
        return true;
    }

    public synchronized int interopTextureId() {
        return interopRenderer != null ? interopRenderer.glTextureId() : -1;
    }

    public synchronized void renderInteropFrame(float timeSeconds) {
        if (interopRenderer != null) {
            interopRenderer.renderFrame(timeSeconds);
        }
    }

    public synchronized void interopFrameDisplayed() {
        if (interopRenderer != null) {
            interopRenderer.frameDisplayed();
        }
    }

    public synchronized void destroy() {
        if (!initialized) {
            return;
        }
        initialized = false;
        if (terrainRenderer != null) {
            terrainRenderer.destroy();
            terrainRenderer = null;
        }
        if (chunkMirror != null) {
            chunkMirror.destroyAll();
            chunkMirror = null;
        }
        if (interopRenderer != null) {
            interopRenderer.destroy();
            interopRenderer = null;
        }
        if (device != null) {
            vkDeviceWaitIdle(device);
            vkDestroyDevice(device, null);
            device = null;
        }
        if (debugMessenger != 0L) {
            org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(
                    instance, debugMessenger, null);
            debugMessenger = 0L;
        }
        if (debugCallback != null) {
            debugCallback.free();
            debugCallback = null;
        }
        if (instance != null) {
            vkDestroyInstance(instance, null);
            instance = null;
        }
        LOGGER.info("Vulkan context torn down");
    }
}
