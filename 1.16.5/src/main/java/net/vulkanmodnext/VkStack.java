package net.vulkanmodnext;

import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Field;

/**
 * A scratch stack big enough for Vulkan, given that the game decided the size
 * before this mod existed.
 *
 * <h2>The problem</h2>
 *
 * LWJGL gives each thread a 64 KiB scratch buffer, which is plenty for almost
 * everything and not plenty for bringing up Vulkan. Constructing a
 * {@code VkInstance} makes LWJGL list the driver's device extensions on one
 * frame it does not release as it goes, and each entry is a
 * {@code VkExtensionProperties} — 260 bytes. A current driver lists around 270.
 * Sixty-four kilobytes holds 252.
 *
 * <p>Measured here, not assumed: with the game's default the instance fails
 * with {@code OutOfMemoryError: Out of stack space} inside
 * {@code VkInstance.getAvailableDeviceExtensions}; with 2 MiB the card answers
 * "Vulkan 1.4.341".
 *
 * <h2>Why the obvious fix does not work on this version</h2>
 *
 * LWJGL reads the size from a system property — once, into a static final, the
 * first time it is touched. The 1.12.2 mod set that property and it worked,
 * because its renderer lived on a class loader of its own that had not been
 * created yet: there was a "before".
 *
 * <p>Here there is not, and this was measured rather than reasoned about. The
 * earliest hook a mod has on 1.16.5 is a mixin config plugin, which runs on the
 * main thread before Minecraft is even launched. Setting the property there
 * still loses: the report read <em>"property 2048, LWJGL heard 64"</em>. Something
 * in Forge's own startup touches LWJGL first, and no mod hook is earlier than
 * that. This is the price of the single class loader that otherwise makes this
 * version so much easier.
 *
 * <h2>What is done instead</h2>
 *
 * The size is not requested. The stack itself is replaced, for the current
 * thread only, through the thread-local LWJGL keeps them in. That works
 * whenever it is done, because it does not depend on load order at all — and it
 * has to be this and not a stack of our own, because the allocation that
 * overflows is made by <em>LWJGL's</em> code, on the thread's own stack, where
 * a private one of ours is never looked at.
 *
 * <p>It costs one reflective field read per class load. If that read ever fails
 * — a different LWJGL, a stricter runtime — nothing is patched and Vulkan fails
 * to start with the message above, which is a great deal better than failing
 * somewhere else. The failure is logged once, in those words.
 */
public final class VkStack {

    /**
     * Room for about 8000 extension entries — twenty-nine drivers' worth.
     *
     * The number to be covered belongs to the driver, not to us, which is why
     * it is generous and why it can be overridden: an implicit overlay layer
     * answering with a nonsense count is not something to learn about from a
     * crash on somebody else's machine.
     */
    private static final int DEFAULT_KB = 2048;

    /**
     * LWJGL's own per-thread stacks. Read, never written — the field is final
     * and stays that way; what is replaced is the value held for one thread.
     */
    private static final ThreadLocal<MemoryStack> LWJGL_STACKS = findStacks();

    private static boolean complained;

    private VkStack() {
    }

    public static int sizeKb() {
        Integer override = Integer.getInteger("vulkanmodnext.stackSizeKb");
        return override == null || override <= 0 ? DEFAULT_KB : override;
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<MemoryStack> findStacks() {
        try {
            Field field = MemoryStack.class.getDeclaredField("TLS");
            field.setAccessible(true);
            return (ThreadLocal<MemoryStack>) field.get(null);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /**
     * Makes this thread's scratch stack big enough before Vulkan is touched.
     *
     * Call it before any call that goes into LWJGL's own Vulkan code, not only
     * before {@link #push()}: the overflow happens inside LWJGL, and it uses
     * the thread's stack whether or not we have pushed a frame on it.
     */
    public static void ensure() {
        if (LWJGL_STACKS == null) {
            complainOnce("LWJGL's thread stacks could not be reached, so they stay at their "
                    + "default size. Vulkan will fail to start on any machine whose driver "
                    + "lists more than about 250 extensions.");
            return;
        }
        int wanted = sizeKb() * 1024;
        MemoryStack current = LWJGL_STACKS.get();
        if (current.getSize() >= wanted) {
            return;
        }
        if (current.getFrameIndex() != 0) {
            // Replacing it now would strand whatever is already on it. This
            // only happens if we are called from inside somebody else's frame,
            // which our own entry points are not.
            complainOnce("asked to enlarge a stack with frames already on it; left alone");
            return;
        }
        LWJGL_STACKS.set(MemoryStack.create(wanted));
    }

    /** Use in a try-with-resources, exactly like {@code MemoryStack.stackPush()}. */
    public static MemoryStack push() {
        ensure();
        return MemoryStack.stackPush();
    }

    private static void complainOnce(String what) {
        if (!complained) {
            complained = true;
            VulkanModNext.LOGGER.warn("Scratch stack: {}", what);
        }
    }

    /**
     * The three numbers that fail apart.
     *
     * What we asked for, what LWJGL heard when it was listening, and what this
     * thread actually has. The middle one being 64 while the first says 2048 is
     * the whole reason this class does what it does, and printing them folded
     * together once cost an hour.
     */
    public static String describe() {
        String mine = LWJGL_STACKS == null ? "unreachable"
                : (LWJGL_STACKS.get().getSize() / 1024) + " KiB";
        return "wanted " + sizeKb() + " KiB, LWJGL heard " + Configuration.STACK_SIZE.get(64)
                + " KiB, this thread has " + mine;
    }
}
