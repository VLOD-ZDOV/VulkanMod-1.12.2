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
            "shadersmodcore.transform.SMCClassTransformer"
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
    };
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
            for (String marker : System.getProperty(EXTRA_MARKERS_PROPERTY, "").split(",")) {
                String trimmed = marker.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && name.contains(trimmed)) {
                    return entry.getName();
                }
            }
        }
        return null;
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
