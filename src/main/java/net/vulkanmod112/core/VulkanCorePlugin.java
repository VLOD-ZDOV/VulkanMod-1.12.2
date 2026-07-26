package net.vulkanmod112.core;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
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
    private static final String[] RENDERER_JAR_MARKERS = {"optifine", "shadersmod"};
    /**
     * Comma-separated extra jar-name fragments, for renderer replacements that
     * appear after this version ships: -Dvulkanmod112.extraRendererMarkers=foo,bar
     */
    private static final String EXTRA_MARKERS_PROPERTY = "vulkanmod112.extraRendererMarkers";

    @Override
    public List<String> getMixinConfigs() {
        List<String> configs = new ArrayList<String>();
        configs.add("mixins.vulkanmod112.json");
        if (rendererReplacementPresent()) {
            System.out.println("[VulkanMod112] A renderer replacement (OptiFine or a shader mod) is "
                    + "installed; Vulkan terrain is not loaded. Settings and the game-side "
                    + "optimisations stay available. Set -Dvulkanmod112.allowIncompatibleRenderer=true "
                    + "to load it anyway (unsupported).");
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
    private static boolean rendererReplacementPresent() {
        if (Boolean.getBoolean("vulkanmod112.allowIncompatibleRenderer")) {
            return false;
        }
        for (String className : RENDERER_CLASSES) {
            if (Launch.classLoader.getResource(className.replace('.', '/') + ".class") != null) {
                return true;
            }
        }
        return modsFolderContainsRenderer();
    }

    private static boolean modsFolderContainsRenderer() {
        File home = Launch.minecraftHome;
        if (home == null) {
            return false;
        }
        File[] entries = new File(home, "mods").listFiles();
        if (entries == null) {
            return false;
        }
        for (File entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar")) {
                continue;
            }
            for (String marker : RENDERER_JAR_MARKERS) {
                if (name.contains(marker)) {
                    return true;
                }
            }
            for (String marker : System.getProperty(EXTRA_MARKERS_PROPERTY, "").split(",")) {
                String trimmed = marker.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && name.contains(trimmed)) {
                    return true;
                }
            }
        }
        return false;
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
