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
        // Vanilla exposes 32 chunks on a sufficiently large 64-bit heap. The
        // renderer and ViewFrustum themselves support higher values; expose
        // the 64-chunk option in our video-settings page.
        net.minecraft.client.settings.GameSettings.Options.RENDER_DISTANCE.setValueMax(64.0F);
        try {
            VulkanBridge vulkan = VulkanLoader.bridge();
            vulkan.init();
            LOGGER.info("Vulkan renderer foundation active on: {}", vulkan.gpuSummary());
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
        }
    }

}
