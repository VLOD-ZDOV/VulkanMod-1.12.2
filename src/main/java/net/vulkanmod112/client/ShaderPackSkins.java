package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Borrows the pictures a shader pack ships, and none of its code.
 *
 * <h2>What can and cannot be taken from a pack, and why the difference matters</h2>
 *
 * A shader pack is not written in plain GLSL. It is written against the API a
 * shader loader provides: programs with fixed names, hundreds of expected
 * uniforms, its own vertex attributes, its own colour buffers, a shadow map and
 * an order of passes. One program cannot be lifted out of that — a pack's cloud
 * shader expects to be handed vanilla's cloud geometry inside a pipeline that
 * does not exist here. Running one would mean implementing that whole API,
 * which is a project the size of this mod.
 *
 * Its <em>images</em> are a different matter entirely. A sun is a PNG. Reading
 * a PNG out of a pack the player already has, on the player's own machine,
 * copies nothing into this mod and redistributes nothing — the same thing the
 * game does with a resource pack. So that is what this does, and it is honest
 * about the limit: a pack that ships no sun has no sun to lend.
 *
 * <h2>Why the search is loose</h2>
 *
 * There is no standard place for these. Packs that ship them put them under
 * whatever folder they felt like. So any file with the right name, anywhere
 * inside the pack, counts — and if there are several, the shallowest wins,
 * because a file at the top of a pack is more likely to be the one it means.
 */
public final class ShaderPackSkins {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Skins");

    /** What is worth looking for, by the name vanilla gives it. */
    public static final String SUN = "sun.png";
    public static final String MOON = "moon_phases.png";

    private static String[] packs;
    private static final Map<String, ResourceLocation> LOADED =
            new HashMap<String, ResourceLocation>();
    private static final Map<String, Boolean> MISSING = new HashMap<String, Boolean>();

    private ShaderPackSkins() {
    }

    /**
     * The packs on disk, newest listing first asked for.
     *
     * "Off" is always the first entry, so the setting reads as an index into
     * this array and zero always means the game's own.
     */
    public static String[] names() {
        if (packs != null) {
            return packs;
        }
        List<String> found = new ArrayList<String>();
        found.add("Off");
        String folderState = "no shaderpacks folder";
        try {
            File dir = new File(Minecraft.getMinecraft().gameDir, "shaderpacks");
            if (dir.isDirectory()) {
                folderState = "folder present";
            }
            File[] entries = dir.listFiles();
            if (entries != null) {
                Arrays.sort(entries);
                for (File entry : entries) {
                    if (entry.isDirectory() || entry.getName().toLowerCase().endsWith(".zip")) {
                        found.add(entry.getName());
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not list the shader pack folder", t);
        }
        String[] result = found.toArray(new String[found.size()]);
        // An empty answer is not cached. The first thing that asks may be the
        // settings screen being built before the game has its folders, and a
        // list of nothing kept for the session would look exactly like a folder
        // with nothing in it — which is the report that came back, from a
        // machine that had a pack sitting right there.
        if (result.length > 1) {
            packs = result;
        }
        LOGGER.info("Shader packs available to borrow pictures from: {} ({})",
                result.length - 1, folderState);
        return result;
    }

    /** Forgets the listing, so a pack added while the game runs can be seen. */
    public static void rescan() {
        packs = null;
        LOADED.clear();
        MISSING.clear();
    }

    public static int indexOf(String name) {
        String[] all = names();
        for (int i = 0; i < all.length; i++) {
            if (all[i].equals(name)) {
                return i;
            }
        }
        return 0;
    }

    public static String nameAt(int index) {
        String[] all = names();
        return index > 0 && index < all.length ? all[index] : "";
    }

    /**
     * The named picture out of the named pack, or null when it has none.
     *
     * A pack asked once and found wanting is remembered as such: opening a
     * hundred-megabyte archive every frame to be told no again is not a
     * search, it is a stall.
     */
    public static ResourceLocation texture(String pack, String wanted) {
        if (pack == null || pack.isEmpty() || "Off".equals(pack)) {
            return null;
        }
        String key = pack + "|" + wanted;
        ResourceLocation known = LOADED.get(key);
        if (known != null) {
            return known;
        }
        if (Boolean.TRUE.equals(MISSING.get(key))) {
            return null;
        }
        ResourceLocation made = null;
        try {
            File dir = new File(Minecraft.getMinecraft().gameDir, "shaderpacks");
            File entry = new File(dir, pack);
            byte[] bytes = entry.isDirectory() ? readFromFolder(entry, wanted)
                    : readFromZip(entry, wanted);
            if (bytes != null) {
                BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                if (image != null) {
                    made = Minecraft.getMinecraft().getTextureManager()
                            .getDynamicTextureLocation("vulkanmod112_pack_"
                                    + Integer.toHexString(key.hashCode()),
                                    new DynamicTexture(image));
                    LOGGER.info("Borrowed {} from shader pack {} ({}x{})",
                            wanted, pack, image.getWidth(), image.getHeight());
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not read {} out of shader pack {}", wanted, pack, t);
        }
        if (made == null) {
            MISSING.put(key, Boolean.TRUE);
            LOGGER.info("Shader pack {} ships no {} — the game's own is used", pack, wanted);
            return null;
        }
        LOADED.put(key, made);
        return made;
    }

    /** Shallowest match wins: a file at the top of a pack is the one it means. */
    private static byte[] readFromFolder(File root, String wanted) throws Exception {
        File best = null;
        int bestDepth = Integer.MAX_VALUE;
        List<File> stack = new ArrayList<File>();
        stack.add(root);
        int depth = 0;
        while (!stack.isEmpty() && depth < 8) {
            List<File> next = new ArrayList<File>();
            for (File dir : stack) {
                File[] children = dir.listFiles();
                if (children == null) {
                    continue;
                }
                for (File child : children) {
                    if (child.isDirectory()) {
                        next.add(child);
                    } else if (child.getName().equalsIgnoreCase(wanted) && depth < bestDepth) {
                        best = child;
                        bestDepth = depth;
                    }
                }
            }
            stack = next;
            depth++;
        }
        if (best == null) {
            return null;
        }
        java.io.FileInputStream in = new java.io.FileInputStream(best);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static byte[] readFromZip(File archive, String wanted) throws Exception {
        ZipFile zip = new ZipFile(archive);
        try {
            ZipEntry best = null;
            int bestDepth = Integer.MAX_VALUE;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                int slash = name.lastIndexOf('/');
                String leaf = slash < 0 ? name : name.substring(slash + 1);
                if (!leaf.equalsIgnoreCase(wanted)) {
                    continue;
                }
                int depth = 0;
                for (int i = 0; i < name.length(); i++) {
                    if (name.charAt(i) == '/') {
                        depth++;
                    }
                }
                if (depth < bestDepth) {
                    bestDepth = depth;
                    best = entry;
                }
            }
            if (best == null) {
                return null;
            }
            InputStream in = zip.getInputStream(best);
            try {
                return readAll(in);
            } finally {
                in.close();
            }
        } finally {
            zip.close();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }
}
