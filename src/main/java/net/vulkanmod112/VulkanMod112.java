package net.vulkanmod112;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = Tags.MOD_ID,
        name = Tags.MOD_NAME,
        version = Tags.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        // Any MixinBooter from 10.7 up works; naming it here turns a missing
        // dependency into Forge's own error screen instead of a mixin crash.
        dependencies = "required-after:mixinbooter@[10.7,)",
        clientSideOnly = true
)
public class VulkanMod112 {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("{} {} starting up", Tags.MOD_NAME, Tags.VERSION);
        net.vulkanmod112.client.VulkanConfig.load(event.getModConfigurationDirectory());
        // Immediately after the setting that turns it on and before anything
        // that can fail: from here every line this mod logs is kept, so the
        // diagnostics file is worth asking for on its own rather than always
        // alongside the game's log.
        net.vulkanmod112.client.Diagnostics.startCapture();
        // Independent of Vulkan: the zoom must work even where the renderer
        // falls back to OpenGL, so it is registered before anything can fail.
        net.vulkanmod112.client.Zoom.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                new net.vulkanmod112.client.Zoom.Handler());
        // Same reason, and more so: the settings screen is where a fallback is
        // diagnosed, so the shortcut to it has to exist on exactly the machines
        // where the renderer did not come up.
        net.vulkanmod112.client.SettingsKey.register();
        net.vulkanmod112.client.ProfileKey.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                new net.vulkanmod112.client.SettingsKey.Handler());
        // Unbound by default: it answers one question, three complaints have
        // been waiting on it for weeks, and it costs a keypress. Binding it to
        // something would claim a third key for a tool most people never need.
        net.vulkanmod112.client.ChunkProbe.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                new net.vulkanmod112.client.ChunkProbe.Handler());
        // Also independent of Vulkan: a frame-time graph is wanted most in the
        // case where the renderer did not come up.
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                new net.vulkanmod112.client.FrameGraphHandler());
        // Also independent of Vulkan: what marks the diagnostics log with where
        // the camera was and what put it there. It writes nothing unless ultra
        // logging is on.
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                new net.vulkanmod112.client.SessionLog.Handler());
        // Vanilla exposes 32 chunks on a sufficiently large 64-bit heap. The
        // renderer and ViewFrustum themselves support higher values; 64, or 128
        // with Extreme Render Distance turned on.
        net.vulkanmod112.client.RenderDistanceLimit.apply();
        try {
            VulkanBridge vulkan = VulkanLoader.bridge();
            vulkan.init();
            LOGGER.info("Vulkan renderer foundation active on: {}", vulkan.gpuSummary());
            // Immediately after it, because that line above has now been read
            // twice as "Vulkan is drawing the world". It is not: it says a
            // Vulkan device came up. Whether the world goes through it is a
            // separate switch, and every effect in this mod is on the far side
            // of it — so the two facts belong next to each other, in the log a
            // report arrives with, and not only in a diagnostics file.
            //
            // Only what is settled at this point is claimed. Whether another
            // mod is drawing the world is decided at the first frame and not
            // here, so this says what the setting says and leaves the rest to
            // SettingsHealth, which watches it for the whole session.
            if (net.vulkanmod112.client.VulkanConfig.isTerrainEnabled()) {
                LOGGER.info("Vulkan terrain rendering is on in the settings — "
                        + "this mod's effects can act once the world is drawn");
            } else {
                LOGGER.info("Vulkan terrain rendering is OFF in the settings, so the world will "
                        + "be drawn by OpenGL as usual. Every effect in this mod lives inside "
                        + "the Vulkan renderer and will do nothing until it is switched on "
                        + "(Options -> Video Settings -> VulkanMod112 Settings, or F6)");
            }
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                    new net.vulkanmod112.client.VulkanDemoOverlay(vulkan));
            // The geometry mirror hooks VertexBuffer uploads, which only exist with VBOs on
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (!mc.gameSettings.useVbo) {
                LOGGER.info("Enabling VBOs (required for the Vulkan geometry mirror)");
                mc.gameSettings.useVbo = true;
                mc.gameSettings.saveOptions();
            }
        } catch (Throwable t) {
            // Vulkan is optional at this stage: the game must stay playable on OpenGL
            LOGGER.error("Vulkan initialization failed, falling back to vanilla OpenGL renderer", t);
            // Say so in the game as well. Without this the mod is at its most
            // silent in the one case a player cannot diagnose: settings present,
            // every effect in them doing nothing, and no line on F3 to explain
            // it, because the line comes from the overlay above.
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                    new net.vulkanmod112.client.FallbackNotice(t));
        }
    }

}
