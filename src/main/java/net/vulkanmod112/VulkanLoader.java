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

    public static synchronized VulkanBridge bridge() {
        if (bridge == null) {
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
        LOGGER.info("Vulkan classloader roots: {}", urls);
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
