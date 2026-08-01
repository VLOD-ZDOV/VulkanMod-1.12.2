package net.vulkanmod112.vkimpl;

import net.vulkanmod112.Tags;
import net.vulkanmod112.VulkanBridge;
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
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Stage 1 of the Vulkan renderer for Minecraft 1.12.2: owns the VkInstance,
 * the chosen physical device and the logical device with a graphics queue.
 *
 * Lives strictly inside the isolated Vulkan classloader (see VulkanLoader):
 * here org.lwjgl.* is LWJGL 3. Only VulkanBridge methods are visible to the
 * game side. Minecraft itself still renders through LWJGL 2 / OpenGL at this
 * stage; this context is the object every later rendering subsystem will
 * hang off of.
 */
public final class VulkanContextImpl implements VulkanBridge {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Vulkan");
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    private VkInstance instance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue graphicsQueue;
    private int graphicsQueueFamily = -1;
    /** How many GPUs Vulkan enumerated; the interop check weighs its fallback on it. */
    private int physicalDeviceCount;
    private boolean initialized;

    private String gpuSummary = "Vulkan not initialized";
    /** Total device-local memory, filled in when the GPU is selected. */
    private int vramMegabytes;
    /** Whether the driver can report per-heap usage and budget (VK_EXT_memory_budget). */
    private boolean memoryBudgetSupported;
    private VkDemoRenderer demoRenderer;
    private VkInteropRenderer interopRenderer;
    /**
     * Volatile because the chunk builder threads read it without the monitor:
     * the volatile write publishes the fully constructed mirror to them.
     */
    private volatile VkChunkMirror chunkMirror;
    private VkTerrainRenderer terrainRenderer;
    private boolean interopCapable;

    @Override
    public synchronized void init() {
        if (initialized) {
            return;
        }
        long start = System.nanoTime();

        try {
            Lwjgl3Natives.setup();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot prepare LWJGL 3 natives", e);
        }

        createInstance();
        pickPhysicalDevice();
        createLogicalDevice();

        initialized = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this::destroy, "VulkanMod112-Shutdown"));
        LOGGER.info("Vulkan context ready in {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    /** Vulkan 1.1 unlocks core external-memory support needed for GL interop. */
    private int pickApiVersion() {
        int supported = VK.getInstanceVersionSupported();
        if (VK_VERSION_MAJOR(supported) > 1
                || (VK_VERSION_MAJOR(supported) == 1 && VK_VERSION_MINOR(supported) >= 1)) {
            return org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
        }
        return VK_API_VERSION_1_0;
    }

    private void createInstance() {
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Minecraft 1.12.2"))
                    .applicationVersion(VK_MAKE_VERSION(1, 12, 2))
                    .pEngineName(stack.UTF8(Tags.MOD_NAME))
                    .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(pickApiVersion());

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo);

            if (Boolean.getBoolean("vulkanmod112.validation") && isValidationLayerAvailable(stack)) {
                LOGGER.info("Enabling {}", VALIDATION_LAYER);
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
            throw new net.vulkanmod112.VulkanUnavailableException(
                    "Listing this machine's Vulkan extensions did not fit the "
                    + Lwjgl3Natives.stackSizeKb() + " KiB of scratch space reserved for it."
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
        VkLayerProperties.Buffer layers = VkLayerProperties.malloc(count.get(0), stack);
        vkEnumerateInstanceLayerProperties(count, layers);
        for (int i = 0; i < layers.capacity(); i++) {
            if (VALIDATION_LAYER.equals(layers.get(i).layerNameString())) {
                return true;
            }
        }
        return false;
    }

    private void pickPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
            int deviceCount = count.get(0);
            if (deviceCount == 0) {
                throw new IllegalStateException("No Vulkan-capable GPU found");
            }

            PointerBuffer handles = stack.mallocPointer(deviceCount);
            check(vkEnumeratePhysicalDevices(instance, count, handles), "vkEnumeratePhysicalDevices");

            VkPhysicalDevice best = null;
            int bestScore = -1;
            for (int i = 0; i < deviceCount; i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(handles.get(i), instance);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(candidate, props);

                int score = score(candidate, props, stack);
                // The extension count is here because it is what sizes the
                // scratch stack this very startup nearly ran out of, and a log
                // that reports it turns the next such report into one line
                // instead of a guess. See Lwjgl3Natives.DEFAULT_STACK_SIZE_KB.
                IntBuffer extensions = stack.mallocInt(1);
                vkEnumerateDeviceExtensionProperties(candidate, (String) null, extensions, null);
                LOGGER.info("GPU {}: {} ({}, Vulkan {}, {} extensions, score {})",
                        i, props.deviceNameString(), deviceTypeName(props.deviceType()),
                        apiVersionString(props.apiVersion()), extensions.get(0), score);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (bestScore < 0) {
                throw new IllegalStateException("No GPU with a graphics queue found");
            }

            this.physicalDevice = best;
            this.physicalDeviceCount = deviceCount;
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(best, props);
            this.graphicsQueueFamily = findGraphicsQueueFamily(best, stack);
            this.gpuSummary = props.deviceNameString() + " (Vulkan " + apiVersionString(props.apiVersion()) + ")";
            LOGGER.info("Selected GPU: {} (graphics queue family {})", props.deviceNameString(), graphicsQueueFamily);
            logMemoryHeaps(stack);
        }
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
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                return i;
            }
        }
        return -1;
    }

    VkPhysicalDevice getPhysicalDevice() {
        return physicalDevice;
    }

    private void logMemoryHeaps(MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc(stack);
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

    /** Device extensions needed to share images and semaphores with OpenGL (platform specific). */
    private static final String[] INTEROP_EXTENSIONS = Interop.deviceExtensions();

    /** Lets the driver tell us how much of each heap it considers spoken for. */
    private static final String MEMORY_BUDGET_EXTENSION =
            org.lwjgl.vulkan.EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME;

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
                org.lwjgl.vulkan.VkExtensionProperties.malloc(count.get(0), stack);
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
                org.lwjgl.vulkan.VkExtensionProperties.malloc(count.get(0), stack);
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
        try (MemoryStack stack = stackPush()) {
            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
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
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack)
                    .robustBufferAccess(true);

            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
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

    @Override
    public synchronized java.nio.ByteBuffer renderDemo(int width, int height) {
        if (!initialized) {
            throw new IllegalStateException("Vulkan context is not initialized");
        }
        if (demoRenderer == null) {
            demoRenderer = new VkDemoRenderer(this, width, height);
        }
        return demoRenderer.renderFrame();
    }

    @Override
    public synchronized boolean initInterop(int width, int height) {
        if (!initialized || !interopCapable) {
            return false;
        }
        if (interopRenderer == null) {
            try {
                VkInteropRenderer renderer = new VkInteropRenderer(this, width, height);
                renderer.init();
                interopRenderer = renderer;
            } catch (Throwable t) {
                LOGGER.error("Zero-copy GL interop initialization failed", t);
                interopCapable = false;
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized int interopTextureId() {
        return interopRenderer != null ? interopRenderer.glTextureId() : -1;
    }

    @Override
    public synchronized void renderInteropFrame(float timeSeconds) {
        if (interopRenderer != null) {
            interopRenderer.renderFrame(timeSeconds);
        }
    }

    @Override
    public synchronized void interopFrameDisplayed() {
        if (interopRenderer != null) {
            interopRenderer.frameDisplayed();
        }
    }

    @Override
    public synchronized void mirrorChunkBuffer(int slot, java.nio.ByteBuffer data) {
        if (!initialized) {
            return;
        }
        if (chunkMirror == null) {
            chunkMirror = new VkChunkMirror(this);
        }
        chunkMirror.upload(slot, data);
    }

    @Override
    public synchronized void setMaterialSprites(int[] materials, float[] rects, int count) {
        if (!initialized) {
            return;
        }
        terrainRenderer().setMaterialSprites(materials, rects, count);
    }

    @Override
    public void stageChunkMaterials(int slot, int[] runs, int runCount) {
        // Not synchronized, for the same reason as stageChunkBuffer below: this
        // arrives from the chunk builder threads, and the mirror has a lock of
        // its own for exactly this.
        if (!initialized) {
            return;
        }
        VkChunkMirror mirror = chunkMirror;
        if (mirror != null) {
            mirror.stageMaterials(slot, runs, runCount);
        }
    }

    @Override
    public boolean stageChunkBuffer(int slot, java.nio.ByteBuffer data) {
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

    @Override
    public synchronized void releaseChunkBuffer(int slot) {
        if (chunkMirror != null) {
            chunkMirror.release(slot);
        }
    }

    @Override
    public synchronized String diagnosticsReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("  gpu: ").append(gpuSummary).append('\n');
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, props);
            sb.append("  limits: maxMemoryAllocationCount ")
                    .append(props.limits().maxMemoryAllocationCount() & 0xFFFFFFFFL)
                    .append(" (the spec only guarantees 4096; the mirror uses one per chunk buffer)\n");
        }
        sb.append("  vram: ").append(vramMegabytes).append(" MiB device-local, geometry budget setting ")
                .append(System.getProperty("vulkanmod112.geometryBudget", "0"))
                .append(" (0 = auto), frames in flight setting ")
                .append(System.getProperty("vulkanmod112.framesInFlight", "2")).append('\n');
        appendMemoryBudget(sb);
        sb.append("  interop: ").append(interopCapable ? "external memory/semaphores enabled" : "UNAVAILABLE")
                .append(", handles: ").append(Interop.WINDOWS ? "win32" : "fd").append('\n');
        sb.append("  mirror: ").append(chunkMirror != null ? chunkMirror.stats() : "not created").append('\n');
        if (terrainRenderer != null) {
            terrainRenderer.appendDiagnostics(sb);
        } else {
            sb.append("  terrain: renderer not created\n");
        }
        return sb.toString();
    }

    /**
     * Per-heap "how much is spoken for" against "how much you may have", as the
     * driver sees it — which is not the same as what this process allocated,
     * because everything else on the desktop shares the card.
     *
     * This exists to answer one question a frame breakdown cannot: a session
     * dropped to 5 fps for half a minute on a scene that was not moving, with
     * GPU time per frame rising tenfold, and eviction to system memory is a
     * candidate we had no way to confirm or rule out. Usage crossing the budget
     * is what that looks like from here.
     */
    private void appendMemoryBudget(StringBuilder sb) {
        if (!memoryBudgetSupported) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            org.lwjgl.vulkan.VkPhysicalDeviceMemoryBudgetPropertiesEXT budget =
                    org.lwjgl.vulkan.VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack)
                            .sType(org.lwjgl.vulkan.EXTMemoryBudget
                                    .VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT);
            org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2 props2 =
                    org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2.calloc(stack)
                            .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_PROPERTIES_2)
                            .pNext(budget.address());
            org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceMemoryProperties2(physicalDevice, props2);

            VkPhysicalDeviceMemoryProperties memProps = props2.memoryProperties();
            for (int i = 0; i < memProps.memoryHeapCount(); i++) {
                if ((memProps.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) == 0) {
                    continue;
                }
                long usedMiB = budget.heapUsage(i) / (1024 * 1024);
                long budgetMiB = budget.heapBudget(i) / (1024 * 1024);
                sb.append("  vram heap ").append(i).append(": ").append(usedMiB)
                        .append(" MiB in use of ").append(budgetMiB).append(" MiB the driver allows");
                if (budgetMiB > 0 && usedMiB > budgetMiB) {
                    sb.append(" — OVER BUDGET, the driver may be evicting to system memory");
                }
                sb.append(" (whole system, not just this game)\n");
            }
        } catch (Throwable t) {
            // A diagnostics line is never worth failing a report over.
            sb.append("  vram heap: budget query failed (").append(t).append(")\n");
        }
    }

    @Override
    public synchronized String chunkMirrorStats() {
        return chunkMirror != null ? chunkMirror.stats() : "mirrored VBOs: 0";
    }

    private boolean glCapsReady;

    /**
     * Creates LWJGL 3's GL function table for the client thread (the game's
     * context was created by LWJGL 2, so this world never did it) and checks
     * the interop extensions. Must precede any GL11C/EXT* call in vkimpl.
     */
    synchronized void ensureGlCapabilities() {
        if (glCapsReady) {
            return;
        }
        GLCapabilities caps = GL.createCapabilities();
        if (!Interop.supportedByGL(caps)) {
            throw new IllegalStateException("OpenGL driver lacks " + Interop.glExtensionNames());
        }
        Interop.requireSameDevice(physicalDevice, physicalDeviceCount);
        Interop.logExternalSemaphoreSupport(physicalDevice);
        glCapsReady = true;
    }

    private VkTerrainRenderer terrainRenderer() {
        if (terrainRenderer == null) {
            terrainRenderer = new VkTerrainRenderer(this);
        }
        return terrainRenderer;
    }

    @Override
    public void applySceneBloom(int sceneGlTexture) {
        // Not synchronized: this runs on the render thread inside the game's
        // own world pass, and it touches only OpenGL objects this renderer
        // owns. Taking the monitor here would put it behind whatever a chunk
        // builder is doing, in the middle of a frame.
        if (!initialized || terrainRenderer == null) {
            return;
        }
        terrainRenderer.applySceneBloom(sceneGlTexture);
    }

    @Override
    public synchronized void updateAtlasRegions(int[] header, int headerCount,
                                                int[] pixels, int pixelCount) {
        if (!initialized || terrainRenderer == null) {
            return;
        }
        terrainRenderer.updateAtlasRegions(header, headerCount, pixels, pixelCount);
    }

    public synchronized void updateAtlas(int atlasGlTextureId) {
        if (!initialized || !interopCapable) {
            return;
        }
        terrainRenderer().updateAtlas(atlasGlTextureId);
    }

    @Override
    public synchronized void setLightmap(int lightmapGlTextureId) {
        if (!initialized || !interopCapable) {
            return;
        }
        terrainRenderer().setLightmap(lightmapGlTextureId);
    }

    @Override
    public synchronized void updateFogState(float[] fog) {
        if (!initialized || !interopCapable) {
            return;
        }
        terrainRenderer().setFogState(fog);
    }

    @Override
    public synchronized void updateDynamicLights(float[] lights, int count) {
        if (!initialized || !interopCapable) {
            return;
        }
        terrainRenderer().setDynamicLights(lights, count);
    }

    @Override
    public synchronized void updateLightmapData(int[] argb) {
        if (!initialized || !interopCapable) {
            return;
        }
        terrainRenderer().setLightmapData(argb);
    }

    @Override
    public synchronized boolean renderTerrainLayer(int layerOrdinal, int[] chunks, int chunkCount,
                                                   float[] mvp, double viewX, double viewY, double viewZ,
                                                   int fbWidth, int fbHeight) {
        if (!initialized || !interopCapable || chunkMirror == null) {
            return false;
        }
        return terrainRenderer().renderLayer(layerOrdinal, chunks, chunkCount, mvp,
                viewX, viewY, viewZ, fbWidth, fbHeight, chunkMirror);
    }

    @Override
    public synchronized boolean drawsTranslucent() {
        if (!initialized || !interopCapable || terrainRenderer == null) {
            return false;
        }
        return terrainRenderer.drawsTranslucent();
    }

    @Override
    public synchronized void destroy() {
        if (!initialized) {
            return;
        }
        if (terrainRenderer != null) {
            terrainRenderer.destroy();
            terrainRenderer = null;
        }
        if (chunkMirror != null) {
            vkDeviceWaitIdle(device);
            chunkMirror.destroyAll();
            chunkMirror = null;
        }
        if (interopRenderer != null) {
            vkDeviceWaitIdle(device);
            interopRenderer.destroy();
            interopRenderer = null;
        }
        if (demoRenderer != null) {
            vkDeviceWaitIdle(device);
            demoRenderer.destroy();
            demoRenderer = null;
        }
        if (device != null) {
            vkDeviceWaitIdle(device);
            vkDestroyDevice(device, null);
            device = null;
        }
        if (instance != null) {
            vkDestroyInstance(instance, null);
            instance = null;
        }
        initialized = false;
        LOGGER.info("Vulkan context destroyed");
    }

    @Override
    public synchronized boolean isInitialized() {
        return initialized;
    }

    @Override
    public String gpuSummary() {
        return gpuSummary;
    }

    @Override
    public int vramMegabytes() {
        return vramMegabytes;
    }

    public VkDevice getDevice() {
        return device;
    }

    public VkQueue getGraphicsQueue() {
        return graphicsQueue;
    }

    public int getGraphicsQueueFamily() {
        return graphicsQueueFamily;
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

}
