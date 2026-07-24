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
        clientSideOnly = true
)
public class VulkanMod112 {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("{} {} starting up", Tags.MOD_NAME, Tags.VERSION);
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
