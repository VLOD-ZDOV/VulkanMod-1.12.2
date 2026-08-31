package net.vulkanmodnext;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.nio.IntBuffer;

/**
 * Asks, once, whether there is a Vulkan driver reachable from inside the game.
 *
 * <h2>Why this exists before anything else</h2>
 *
 * The port rests on one assumption that no amount of reading can settle: that
 * the LWJGL the game has already started — version 3.2.2, its own, loaded by
 * its own loader, with the game's natives — can reach a Vulkan driver when
 * handed nothing but the bindings jar. On 1.12.2 it could not, and the whole
 * two-classloader construction exists because of it. If it can here, that
 * construction does not get ported at all.
 *
 * <p>So this is the first thing written and the first thing run. It creates an
 * instance, counts the cards and says what it found, and it is written to fail
 * with a sentence rather than a stack trace: "no driver" and "the bindings did
 * not load" need opposite fixes, and a caught throwable that says neither is
 * how a week goes missing.
 */
public final class VulkanProbe {

    private VulkanProbe() {
    }

    public static String describe() {
        try {
            return probe();
        } catch (UnsatisfiedLinkError missing) {
            return "the bindings could not reach a native library: " + missing.getMessage()
                    + " — this is LWJGL, not the driver";
        } catch (Throwable failed) {
            // Where it failed, not only that it did. The first attempt at this
            // returned "OutOfMemoryError: Out of stack space" and nothing else,
            // and that one line fits three unrelated causes.
            StackTraceElement[] where = failed.getStackTrace();
            StringBuilder at = new StringBuilder();
            for (int i = 0; i < Math.min(6, where.length); i++) {
                at.append(" <- ").append(where[i]);
            }
            return "failed: " + failed.getClass().getSimpleName() + ": "
                    + failed.getMessage() + " [" + VkStack.describe() + "]" + at;
        }
    }

    private static String probe() {
        // Before the first LWJGL Vulkan call, not merely before ours.
        VkStack.ensure();
        try (MemoryStack stack = VkStack.push()) {
            VkApplicationInfo application = VkApplicationInfo.callocStack(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("VulkanMod 1.16.5 probe"))
                    .apiVersion(VK10.VK_API_VERSION_1_0);
            VkInstanceCreateInfo create = VkInstanceCreateInfo.callocStack(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(application);

            PointerBuffer pInstance = stack.mallocPointer(1);
            int created = VK10.vkCreateInstance(create, null, pInstance);
            if (created != VK10.VK_SUCCESS) {
                return "no instance, vkCreateInstance returned " + created
                        + " — the bindings work, the driver does not";
            }
            VkInstance instance = new VkInstance(pInstance.get(0), create);

            IntBuffer count = stack.mallocInt(1);
            VK10.vkEnumeratePhysicalDevices(instance, count, null);
            int cards = count.get(0);
            if (cards == 0) {
                VK10.vkDestroyInstance(instance, null);
                return "an instance, but no cards behind it";
            }
            PointerBuffer devices = stack.mallocPointer(cards);
            VK10.vkEnumeratePhysicalDevices(instance, count, devices);

            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.callocStack(stack);
            VK10.vkGetPhysicalDeviceProperties(
                    new VkPhysicalDevice(devices.get(0), instance), properties);
            int api = properties.apiVersion();
            String summary = cards + " card(s), the first speaks Vulkan "
                    + VK10.VK_VERSION_MAJOR(api) + "." + VK10.VK_VERSION_MINOR(api)
                    + "." + VK10.VK_VERSION_PATCH(api)
                    + " — from the game's own LWJGL, no second class loader ["
                    + VkStack.describe() + "]";
            VK10.vkDestroyInstance(instance, null);
            return summary;
        }
    }
}
