package net.vulkanmod112.core;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.mixin.Mixins;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Coremod entry point, and the place where this mod decides how much of itself
 * to install.
 *
 * The mixin configs are split in two. The always-on one holds settings and
 * game-side optimisations, which touch classes any renderer leaves alone. The
 * second one captures chunk geometry for the Vulkan renderer and rewrites the
 * classes OptiFine and the old shader mods replace wholesale — applying it
 * alongside them fails at class-load time, long before any runtime check in
 * TerrainHooks could step in. So when such a renderer is present, that config
 * is simply never registered: the game boots on OptiFine's renderer with this
 * mod's optimisations still in place.
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("VulkanMod112Core")
public class VulkanCorePlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {

    private static final String[] RENDERER_CLASSES = {
            "optifine.OptiFineForgeTweaker",
            "optifine.OptiFineTweaker",
            "shadersmod.client.Shaders",
            "shadersmodcore.transform.SMCClassTransformer",
            // Nothirium rewrites the chunk rendering engine, and Vulcanizator
            // is a second Vulkan renderer: it patches ChunkRenderContainer for
            // its own terrain and Display.update for its own presentation.
            // Both were found in one crash report alongside this mod. Two
            // renderers cannot own the terrain, and two of them cannot own the
            // swapchain at all.
            "meldexun.nothirium.mc.asm.NothiriumPlugin",
            "net.vulkanmod.legacy.core.VulkanCorePlugin"
    };
    /**
     * Jar-name fragments of mods that replace the terrain renderer.
     *
     * Celeritas is a Sodium port for 1.12.2 and Actinium ships it, so both
     * rewrite the same classes this mod's renderer config does. That was
     * reported from the field rather than found here: 0.6.0 added three
     * injections into {@code RenderGlobal.setupTerrain} where 0.5.0 had one,
     * and the renderer config is marked required, so an injection that cannot
     * be applied stops the game instead of degrading. Whatever the exact clash,
     * two renderers cannot both own the terrain, and the answer is the same one
     * OptiFine gets: this mod's renderer stands aside and everything else stays.
     */
    private static final String[] RENDERER_JAR_MARKERS = {
            "optifine", "shadersmod", "celeritas", "actinium",
            "nothirium", "vulcanizator",
            // The Sodium ports and their forks. Found by looking rather than by
            // waiting for the crash report: every one of these replaces the
            // chunk renderer, and a list of them that is only ever extended by
            // people whose game already broke is a list that is always one
            // release behind.
            "vintagium", "relictium", "neonium", "magnesium",
    };
    /**
     * A file beside the settings where anyone can name one more.
     *
     * The list above will always be behind — somebody forks a renderer, gives
     * it a name nobody here has heard, and the two of them fight over the
     * terrain with no way to say so short of waiting for this mod to ship
     * again. One line in a text file settles it, and it is read at the only
     * moment it could be useful, which is before either renderer has loaded.
     */
    private static final String STAND_ASIDE_FILE = "vulkanmod112-standaside.txt";
    /**
     * Comma-separated extra jar-name fragments, for renderer replacements that
     * appear after this version ships: -Dvulkanmod112.extraRendererMarkers=foo,bar
     */
    private static final String EXTRA_MARKERS_PROPERTY = "vulkanmod112.extraRendererMarkers";

    @Override
    public List<String> getMixinConfigs() {
        // Before any config, so that a patch which fails while the very first
        // one is prepared is still caught and written down.
        Mixins.registerErrorHandlerClass(VulkanMixinErrorHandler.class.getName());
        List<String> configs = new ArrayList<String>();
        configs.add("mixins.vulkanmod112.json");
        String replacement = rendererReplacement();
        if (replacement != null) {
            System.out.println("[VulkanMod112] Another renderer (" + replacement + ") is installed; "
                    + "Vulkan terrain is not loaded. Settings and the game-side optimisations stay "
                    + "available. If a renderer this build does not know about is crashing the game, "
                    + "name its jar with -Dvulkanmod112.extraRendererMarkers=part-of-its-filename. "
                    + "Set -Dvulkanmod112.allowIncompatibleRenderer=true to load ours anyway "
                    + "(unsupported).");
        } else {
            configs.add("mixins.vulkanmod112.renderer.json");
        }
        return configs;
    }

    /**
     * Runs before mod classes exist, so this looks for the tweaker classes on
     * the launch classloader and, because coremod load order is not guaranteed,
     * also for the jar itself in the mods folder.
     */
    private static String rendererReplacement() {
        if (Boolean.getBoolean("vulkanmod112.allowIncompatibleRenderer")) {
            return null;
        }
        for (String className : RENDERER_CLASSES) {
            if (Launch.classLoader.getResource(className.replace('.', '/') + ".class") != null) {
                return className;
            }
        }
        return rendererJarInModsFolder();
    }

    /** @return the offending jar's name, or null if there is none. */
    private static String rendererJarInModsFolder() {
        File home = Launch.minecraftHome;
        if (home == null) {
            return null;
        }
        File[] entries = new File(home, "mods").listFiles();
        if (entries == null) {
            return null;
        }
        for (File entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar")) {
                continue;
            }
            for (String marker : RENDERER_JAR_MARKERS) {
                if (name.contains(marker)) {
                    return entry.getName();
                }
            }
            for (String marker : namedByHand()) {
                if (name.contains(marker)) {
                    return entry.getName();
                }
            }
        }
        return null;
    }


    /** Cached: the folder is walked once per jar and this does not change. */
    private static List<String> namedByHand;

    /**
     * Markers the player added — on the command line, or in the file beside the
     * settings, which is written out the first time this runs so that it can be
     * found without being documented.
     */
    private static List<String> namedByHand() {
        if (namedByHand != null) {
            return namedByHand;
        }
        List<String> markers = new ArrayList<String>();
        for (String marker : System.getProperty(EXTRA_MARKERS_PROPERTY, "").split(",")) {
            add(markers, marker);
        }
        try {
            File home = Launch.minecraftHome;
            File config = new File(home == null ? new File(".") : home, "config");
            File file = new File(config, STAND_ASIDE_FILE);
            if (!file.isFile()) {
                writeTemplate(config, file);
            } else {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int hash = line.indexOf('#');
                        add(markers, hash < 0 ? line : line.substring(0, hash));
                    }
                } finally {
                    reader.close();
                }
            }
        } catch (Throwable failure) {
            // A file that cannot be read is the same as one that is not there.
            // This decides whether a renderer loads, and refusing to start over
            // a text file would be a far worse answer than ignoring it.
            System.out.println("[VulkanMod112] Could not read " + STAND_ASIDE_FILE + ". " + failure);
        }
        if (!markers.isEmpty()) {
            System.out.println("[VulkanMod112] Also standing aside for: " + markers);
        }
        namedByHand = markers;
        return markers;
    }

    private static void add(List<String> markers, String marker) {
        String trimmed = marker.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.isEmpty()) {
            markers.add(trimmed);
        }
    }

    /** Written empty, so that finding it is not the same as reading a manual. */
    private static void writeTemplate(File config, File file) throws java.io.IOException {
        if (!config.isDirectory() && !config.mkdirs()) {
            return;
        }
        java.io.Writer out = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file), "UTF-8");
        try {
            out.write("# One line for each mod this renderer should stand aside for.\n");
            out.write("#\n");
            out.write("# Put part of the jar's file name on a line of its own, lower case.\n");
            out.write("# When a jar in the mods folder contains that text, this mod does not\n");
            out.write("# load its Vulkan renderer at all: the settings screen and every speed\n");
            out.write("# option stay, and the world is drawn by whatever else you installed.\n");
            out.write("#\n");
            out.write("# It is for renderers that came out after this build did. These are\n");
            out.write("# already known and do not need a line: optifine, shadersmod,\n");
            out.write("# celeritas, actinium, nothirium, vulcanizator, vintagium, relictium,\n");
            out.write("# neonium, magnesium.\n");
            out.write("#\n");
            out.write("# Anything after a # is ignored. Blank lines are ignored.\n");
            out.write("# To go the other way and load this renderer anyway, start the game\n");
            out.write("# with -Dvulkanmod112.allowIncompatibleRenderer=true\n");
        } finally {
            out.close();
        }
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

}
