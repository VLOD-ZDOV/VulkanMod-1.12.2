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
        // Default 64 KiB MemoryStack overflows while VkInstance enumerates the
        // hundreds of extensions modern drivers expose
        Configuration.STACK_SIZE.set(512);
        if (Boolean.getBoolean("vulkanmod112.debugLoader")) {
            Configuration.DEBUG.set(true);
            Configuration.DEBUG_LOADER.set(true);
        }
        done = true;
        LOGGER.info("LWJGL 3 natives extracted to {}", dir);
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
