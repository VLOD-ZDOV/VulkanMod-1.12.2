package net.vulkanmod112;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the isolated classloader for the Vulkan side of the mod.
 *
 * Minecraft 1.12.2 ships a sealed LWJGL 2 jar, so LWJGL 3 classes can never
 * be defined in the same classloader (sealing violation on org.lwjgl).
 * This loader defines org.lwjgl.* (from the bundled LWJGL 3) and
 * net.vulkanmod112.vkimpl.* child-first in its own space; everything else
 * (the VulkanBridge interface, log4j, later Minecraft classes) is delegated
 * to the game's classloader, so both sides share those types.
 */
public final class VulkanLoader {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Loader");
    private static final Pattern LWJGL3_JAR = Pattern.compile("lwjgl(-[a-z]+)?-3\\..*\\.jar");
    private static final String IMPL_CLASS = "net.vulkanmod112.vkimpl.VulkanContextImpl";

    private static VulkanBridge bridge;

    private VulkanLoader() {
    }

    /** Non-constructing accessor for very early callers (mixins). */
    public static synchronized VulkanBridge bridgeIfReady() {
        return bridge;
    }

    /**
     * Highest Java feature release the bundled LWJGL 3.3 understands.
     *
     * LWJGL installs its GL function tables by patching the JVM's JNI function
     * table, and it recognises the layout only for JVMs it knows. On a newer
     * one it warns and carries on with a corrupted table: OpenGL calls then
     * return nonsense — the device UUID query answers with its own arguments —
     * and the JVM segfaults soon after, in our observation inside a JIT
     * compiler thread, far from anything this mod wrote. Vulkan is unaffected,
     * because its entry points hang off the instance and device objects rather
     * than that table, so the failure looks like a driver problem rather than
     * what it is.
     *
     * Minecraft 1.12.2 runs on Java 8, so this only ever triggers on a
     * modernised platform such as Cleanroom, where we decline cleanly and the
     * game keeps its own renderer.
     */
    private static final int MAX_SUPPORTED_JAVA = 21;

    /** LWJGL's own name for the setting, read whenever its Configuration first initializes. */
    private static final String LWJGL_STACK_PROPERTY = "org.lwjgl.system.stackSize";

    /**
     * Per-thread scratch space LWJGL hands out, in kilobytes.
     *
     * The number that matters is not ours but the driver's. Constructing a
     * VkInstance makes LWJGL list the extensions of every physical device on
     * one stack frame it does not release between devices, and each entry is a
     * VkExtensionProperties — 256 bytes of name plus a version, 260 in all. So
     * the frame costs 260 bytes times the extensions of every GPU in the
     * machine added together.
     *
     * LWJGL's own default of 64 KiB holds 252 entries, and current drivers list
     * around 270 for a single card — machines have failed to start over a
     * shortfall of three kilobytes. 2 MiB is 8064 entries, twenty-nine drivers'
     * worth, which is room for whatever a multi-GPU machine with overlay layers
     * turns out to list.
     *
     * The cost is paid per thread that ever touches the stack, and there is no
     * way to ask for it thread by thread: LWJGL reads this once, into a static
     * final, the first time MemoryStack is initialized. Our chunk mirror runs
     * on the game's builder threads, so the real cost is this times about ten.
     */
    private static final int DEFAULT_STACK_SIZE_KB = 2048;

    /**
     * Overridable because what it has to cover belongs to the driver: an
     * extension list longer than anything foreseen here, or an implicit layer
     * answering with a nonsense count.
     */
    public static int stackSizeKb() {
        Integer override = Integer.getInteger("vulkanmod112.stackSizeKb");
        return override == null || override <= 0 ? DEFAULT_STACK_SIZE_KB : override;
    }

    /**
     * Puts the stack size where LWJGL cannot miss it, before the classloader
     * that will read it exists.
     *
     * Setting it through Configuration once the Vulkan side is running was too
     * late on some machines, and silently: LWJGL reads the setting the first
     * time MemoryStack is initialized, and by then something had already done
     * that, so the request was ignored and the stack stayed at 64 KiB. One
     * report showed a machine listing 265 extensions — 67 KiB — failing to
     * start against those 64, while the log said the budget was 2 MiB.
     *
     * A system property set here cannot be too late, because the isolated
     * loader is created on the next line and nothing in it has run yet. It is
     * left alone if it is already set, so a launcher flag still wins.
     */
    private static void reserveStackSpace() {
        if (System.getProperty(LWJGL_STACK_PROPERTY) == null) {
            System.setProperty(LWJGL_STACK_PROPERTY, Integer.toString(stackSizeKb()));
        }
    }

    public static synchronized VulkanBridge bridge() {
        if (bridge == null) {
            // The fallback is the hardest state to reach on a machine where
            // everything works, and the hardest to get a report about from one
            // where it does not. This reaches it on demand, so what a player
            // sees when the renderer is off can be checked rather than assumed.
            if (Boolean.getBoolean("vulkanmod112.forceFallback")) {
                throw new VulkanUnavailableException("Vulkan renderer switched off by vulkanmod112.forceFallback");
            }
            int java = javaFeatureVersion();
            if (java > MAX_SUPPORTED_JAVA) {
                throw new VulkanUnavailableException("Java " + java + " is newer than the bundled LWJGL 3.3 supports"
                        + " (up to " + MAX_SUPPORTED_JAVA + "). Loading it here would corrupt the JVM's JNI"
                        + " function table and crash the process, so the Vulkan renderer stays off and the"
                        + " game renders on OpenGL.");
            }
            reserveStackSpace();
            try {
                URLClassLoader loader = new IsolatingLoader(collectUrls(), VulkanLoader.class.getClassLoader());
                Class<?> impl = Class.forName(IMPL_CLASS, true, loader);
                if (impl.getClassLoader() != loader) {
                    throw new IllegalStateException(IMPL_CLASS + " leaked into " + impl.getClassLoader()
                            + " instead of the isolated loader — LWJGL 3 would clash with LWJGL 2");
                }
                bridge = (VulkanBridge) impl.getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot bootstrap the Vulkan classloader", e);
            }
        }
        return bridge;
    }

    /**
     * The Java feature release, read without Runtime.version(), which does not
     * exist on the Java 8 this mod normally runs on.
     */
    private static int javaFeatureVersion() {
        String spec = System.getProperty("java.specification.version", "1.8");
        try {
            // "1.8" on Java 8, plain "21" and up afterwards
            return spec.startsWith("1.") ? Integer.parseInt(spec.substring(2)) : Integer.parseInt(spec);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    private static URL[] collectUrls() {
        List<URL> urls = new ArrayList<>();
        // Our own classes (in production the LWJGL 3 classes are shaded in here too)
        URL own = ownClassesRoot();
        urls.add(own);
        // In the dev environment LWJGL 3 sits on the classpath as separate jars
        // and has to be picked up from there. In production it is already
        // shaded into our jar, and scanning must be skipped: on a platform that
        // runs the game itself on LWJGL 3 — Cleanroom — the game's own jars
        // match this pattern, and pulling a second, differently versioned LWJGL
        // into the isolated loader is exactly the clash the loader exists to
        // prevent.
        if (!bundlesLwjgl3(own)) {
            for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                String name = new File(entry).getName();
                if (LWJGL3_JAR.matcher(name).matches()) {
                    try {
                        urls.add(new File(entry).toURI().toURL());
                    } catch (java.net.MalformedURLException ignored) {
                    }
                }
            }
        }
        // File names only. What this line is for is which jars were found, and
        // the directories they sit in name the person running the game — this
        // log is something players are asked to send.
        StringBuilder names = new StringBuilder();
        for (URL url : urls) {
            String path = url.getPath();
            int slash = path.lastIndexOf('/');
            names.append(names.length() == 0 ? "" : ", ")
                    .append(slash < 0 ? path : path.substring(slash + 1));
        }
        LOGGER.info("Vulkan classloader roots: {}", names);
        return urls.toArray(new URL[0]);
    }

    /**
     * Whether LWJGL 3 is shaded into our own root. Probed against that root
     * alone rather than through the game's classloader, which on an LWJGL 3
     * platform would answer yes for the game's copy as well.
     */
    private static boolean bundlesLwjgl3(URL own) {
        try (URLClassLoader probe = new URLClassLoader(new URL[]{own}, null)) {
            return probe.findResource("org/lwjgl/system/MemoryStack.class") != null;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * Root URL (jar or classes directory) our own classes live in.
     * CodeSource locations vary by launcher ("jar:...!/Foo.class", plain jar
     * path, classes dir), so derive the root from a resource lookup instead.
     */
    private static URL ownClassesRoot() {
        String resourcePath = VulkanLoader.class.getName().replace('.', '/') + ".class";
        URL url = VulkanLoader.class.getClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new IllegalStateException("Cannot locate own classes root");
        }
        String s = url.toExternalForm();
        try {
            if (s.startsWith("jar:")) {
                // jar:file:/path/mod.jar!/net/... -> file:/path/mod.jar
                return new URL(s.substring(4, s.lastIndexOf("!/")));
            }
            // file:/path/classes/net/... -> file:/path/classes/
            return new URL(s.substring(0, s.length() - resourcePath.length()));
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("Cannot derive classes root from " + s, e);
        }
    }

    private static final class IsolatingLoader extends URLClassLoader {

        static {
            ClassLoader.registerAsParallelCapable();
        }

        IsolatingLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        private static boolean childFirst(String name) {
            return name.startsWith("org.lwjgl.") || name.startsWith("net.vulkanmod112.vkimpl.");
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null && childFirst(name)) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        // e.g. org.lwjgl.opengl.* is not bundled: fall through to the parent (LWJGL 2)
                    }
                }
                if (c == null) {
                    return super.loadClass(name, resolve);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

    }

}
