package net.vulkanmodnext.vkimpl;

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

    private static final Logger LOGGER = LogManager.getLogger("VulkanModNext/Natives");

    private static boolean done;

    /** The size asked for, decided one layer up. See {@link net.vulkanmodnext.VulkanLoader#stackSizeKb()}. */
    static int stackSizeKb() {
        return net.vulkanmodnext.VulkanLoader.stackSizeKb();
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

        Path dir = Files.createTempDirectory("vulkanmodnext-lwjgl3");
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
        if (Boolean.getBoolean("vulkanmodnext.debugLoader")) {
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
        // Folder name without its parents: which temporary directory it is has
        // never mattered, and the full path names the person running the game.
        LOGGER.info("LWJGL 3 natives extracted to {} (stack budget {} KiB, in effect {} KiB)",
                dir.getFileName(), stackSizeKb(), actualKb);
        // The number that actually decides whether this mod is safe on a given
        // JVM, printed where the next person to ask will find it.
        //
        // LWJGL patches the JNI function table and switches on the JNI version
        // to know its layout — not on the Java version, which is what the
        // startup check has to guess from because it runs before any of this
        // exists. The two move at completely different rates: the ceiling sat
        // at Java 21 for a year while the bundled LWJGL knew everything up to
        // JNI 24, which Java 25 and 26 both still report. Every Cleanroom
        // instance on a modern JVM was refused over that gap.
        LOGGER.info("JNI version reported by this JVM: {} (LWJGL {})",
                org.lwjgl.system.jni.JNINativeInterface.GetVersion() >>> 16,
                org.lwjgl.Version.getVersion());
        if (actualKb < stackSizeKb()) {
            // Both numbers, because they answer different questions: what LWJGL
            // holds as the setting, and what it built before reading it.
            LOGGER.warn("The stack setting did not take — LWJGL holds {}, MemoryStack was already built at {} KiB",
                    Configuration.STACK_SIZE.get(-1), actualKb);
        }
    }

    /**
     * Where the bundled natives live inside this jar.
     *
     * Not the path LWJGL searches. Shipping them at "<os>/<arch>/org/lwjgl/..."
     * — which is where they arrive from their own jars — puts an older LWJGL's
     * libraries on the classpath in front of any host that has its own, and a
     * host that does prints an error about it: Cleanroom runs LWJGL 3.4.1,
     * found this mod's 3.3.6 checksums, and said so twice a session in a game
     * this mod was not rendering. These are private to this mod and are
     * extracted by hand, so they are kept somewhere nobody else looks. Set in
     * the shadowJar task; the two have to agree.
     */
    private static final String PRIVATE_PREFIX = "vulkanmodnext-lwjgl/";

    private static void extract(String resource, Path target) throws IOException {
        ClassLoader loader = Lwjgl3Natives.class.getClassLoader();
        // The private location first, then the plain one — so a jar built
        // before the natives were moved still works, and so does the
        // development classpath, where they come straight from LWJGL's own
        // jars and are not relocated at all.
        if (loader.getResource(PRIVATE_PREFIX + resource) != null) {
            resource = PRIVATE_PREFIX + resource;
        }
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Bundled native " + resource + " not found on classpath");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
    }

}
