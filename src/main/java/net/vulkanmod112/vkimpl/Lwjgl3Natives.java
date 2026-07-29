package net.vulkanmod112.vkimpl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * LWJGL 2 (Minecraft's) and LWJGL 3 (ours) both name their native library
 * "lwjgl" (liblwjgl.so / lwjgl.dll), and the game's library path only contains
 * the LWJGL 2 one. This extracts the bundled LWJGL 3 native into a private
 * directory and points LWJGL 3's loader there explicitly.
 *
 * Must run before the first touch of any class that initializes
 * org.lwjgl.system.Library (MemoryStack, MemoryUtil, VK10, ...).
 */
final class Lwjgl3Natives {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Natives");

    private static boolean done;

    /** The size asked for, decided one layer up. See {@link net.vulkanmod112.VulkanLoader#stackSizeKb()}. */
    static int stackSizeKb() {
        return net.vulkanmod112.VulkanLoader.stackSizeKb();
    }

    private Lwjgl3Natives() {
    }

    static synchronized void setup() throws IOException {
        if (done) {
            return;
        }
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");

        String resource;
        String fileName;
        String openglResource;
        String openglFileName;
        if (os.contains("linux") && x64) {
            resource = "linux/x64/org/lwjgl/liblwjgl.so";
            fileName = "liblwjgl.so";
            openglResource = "linux/x64/org/lwjgl/opengl/liblwjgl_opengl.so";
            openglFileName = "liblwjgl_opengl.so";
        } else if (os.contains("windows") && x64) {
            resource = "windows/x64/org/lwjgl/lwjgl.dll";
            fileName = "lwjgl.dll";
            openglResource = "windows/x64/org/lwjgl/opengl/lwjgl_opengl.dll";
            openglFileName = "lwjgl_opengl.dll";
        } else {
            throw new IOException("No bundled LWJGL 3 natives for " + os + "/" + arch);
        }

        Path dir = Files.createTempDirectory("vulkanmod112-lwjgl3");
        extract(resource, dir.resolve(fileName));
        // GL.createCapabilities() needs this companion library.  Loading only
        // liblwjgl works for Vulkan startup but fails later during GL/VK image
        // interop with an opaque UnsatisfiedLinkError.
        extract(openglResource, dir.resolve(openglFileName));
        dir.toFile().deleteOnExit();

        Configuration.LIBRARY_PATH.set(dir.toAbsolutePath().toString());
        // Belt and braces. The size is really set through the system property
        // before this classloader exists, because by the time anything here
        // runs, MemoryStack can already have read it — see VulkanLoader. This
        // line still helps on the paths where it has not.
        Configuration.STACK_SIZE.set(stackSizeKb());
        if (Boolean.getBoolean("vulkanmod112.debugLoader")) {
            Configuration.DEBUG.set(true);
            Configuration.DEBUG_LOADER.set(true);
        }
        done = true;
        // What we asked for and what we got, because they are not the same
        // question. LWJGL reads the setting above once, into a static final,
        // the first time MemoryStack is initialized; anything that touched it
        // earlier leaves the request silently ignored and the stack at LWJGL's
        // own 64 KiB. Reading the size back is the only way to tell a budget
        // that is too small from a budget that never arrived — and touching it
        // here also pins that initialization to this moment, right after the
        // setting, where it is known to be correct.
        int actualKb = org.lwjgl.system.MemoryStack.stackGet().getSize() / 1024;
        LOGGER.info("LWJGL 3 natives extracted to {} (stack budget {} KiB, in effect {} KiB)",
                dir, stackSizeKb(), actualKb);
        if (actualKb < stackSizeKb()) {
            // Both numbers, because they answer different questions: what LWJGL
            // holds as the setting, and what it built before reading it.
            LOGGER.warn("The stack setting did not take — LWJGL holds {}, MemoryStack was already built at {} KiB",
                    Configuration.STACK_SIZE.get(-1), actualKb);
        }
    }

    private static void extract(String resource, Path target) throws IOException {
        try (InputStream in = Lwjgl3Natives.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Bundled native " + resource + " not found on classpath");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
    }

}
