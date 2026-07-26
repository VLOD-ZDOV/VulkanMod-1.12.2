package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.vulkanmod112.VulkanBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * Displays the Vulkan-rendered demo frame as a small HUD overlay and adds a
 * VulkanMod112 line to the F3 debug screen.
 *
 * This class lives on the game side of the bridge: OpenGL here is the game's
 * own LWJGL 2. The pixels arrive from the isolated Vulkan world as a plain
 * java.nio.ByteBuffer.
 */
public final class VulkanDemoOverlay {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Overlay");
    private static final int TEXTURE_SIZE = 256;
    private static final int DRAW_SIZE = 96;
    /**
     * The demo triangle proved stages 2–3.1; now it just costs a submit and
     * two semaphore hops per frame. Off unless -Dvulkanmod112.overlay=true.
     */

    private final VulkanBridge bridge;
    private int textureId = -1;
    private boolean failed;
    /** null = not decided yet, true = zero-copy VRAM sharing, false = CPU readback demo */
    private Boolean interop;
    private final long startNanos = System.nanoTime();

    public VulkanDemoOverlay(VulkanBridge bridge) {
        this.bridge = bridge;
    }

    @SubscribeEvent
    public void onHudRendered(RenderGameOverlayEvent.Post event) {
        if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
            draw(event.getResolution());
        }
    }

    @SubscribeEvent
    public void onScreenDrawn(GuiScreenEvent.DrawScreenEvent.Post event) {
        draw(new ScaledResolution(Minecraft.getMinecraft()));
    }

    /**
     * Once-a-frame hook that exists even when the Vulkan renderer is not
     * loaded (OptiFine present), so diagnostics still work there.
     */
    @SubscribeEvent
    public void onRenderTick(net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent event) {
        if (event.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) {
            Diagnostics.tick();
        }
    }

    @SubscribeEvent
    public void onDebugText(RenderGameOverlayEvent.Text event) {
        if (Minecraft.getMinecraft().gameSettings.showDebugInfo) {
            event.getRight().add("");
            event.getRight().add("VulkanMod112: " + bridge.gpuSummary());
            event.getRight().add("VulkanMod112: " + bridge.chunkMirrorStats()
                    + (Boolean.TRUE.equals(interop) ? ", zero-copy on" : ""));
            event.getRight().add("VulkanMod112: " + TerrainHooks.stats());
        }
    }

    @SubscribeEvent
    public void onTextureStitched(net.minecraftforge.client.event.TextureStitchEvent.Post event) {
        TerrainHooks.invalidateAtlas();
    }

    private void draw(ScaledResolution resolution) {
        if (failed || !showOverlay()) {
            return;
        }
        if (interop == null) {
            decideRenderPath();
        }
        if (interop) {
            float seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0f;
            try {
                bridge.renderInteropFrame(seconds);
            } catch (Throwable t) {
                // A GPU fault (device lost) must not take the game down: the
                // world falls back to vanilla GL, the overlay just disappears.
                failed = true;
                LOGGER.error("Interop frame failed, overlay disabled", t);
                return;
            }
        } else if (textureId == -1) {
            uploadVulkanFrame();
        }
        if (textureId == -1) {
            return;
        }

        int x = resolution.getScaledWidth() - DRAW_SIZE - 4;
        int y = 4;

        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.bindTexture(textureId);
        Gui.drawModalRectWithCustomSizedTexture(x, y, 0, 0, DRAW_SIZE, DRAW_SIZE, DRAW_SIZE, DRAW_SIZE);
        GlStateManager.disableBlend();

        if (interop) {
            try {
                bridge.interopFrameDisplayed();
            } catch (Throwable t) {
                failed = true;
                LOGGER.error("Interop frame handoff failed, overlay disabled", t);
            }
        }
    }

    private static boolean showOverlay() {
        return Boolean.getBoolean("vulkanmod112.overlay") || VulkanConfig.isOverlayEnabled();
    }

    private void decideRenderPath() {
        try {
            interop = bridge.initInterop(TEXTURE_SIZE, TEXTURE_SIZE);
            if (interop) {
                textureId = bridge.interopTextureId();
                LOGGER.info("Overlay path: zero-copy VRAM sharing (GL texture {})", textureId);
            } else {
                LOGGER.info("Overlay path: CPU readback fallback");
            }
        } catch (Throwable t) {
            LOGGER.error("Interop setup failed, using CPU readback fallback", t);
            interop = false;
        }
    }

    private void uploadVulkanFrame() {
        try {
            long start = System.nanoTime();
            ByteBuffer pixels = bridge.renderDemo(TEXTURE_SIZE, TEXTURE_SIZE);
            long renderMs = (System.nanoTime() - start) / 1_000_000;

            int covered = 0;
            for (int i = 3; i < pixels.remaining(); i += 4) {
                if (pixels.get(i) != 0) {
                    covered++;
                }
            }

            textureId = GL11.glGenTextures();
            GlStateManager.bindTexture(textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, TEXTURE_SIZE, TEXTURE_SIZE, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

            LOGGER.info("Vulkan demo frame rendered in {} ms: {} of {} pixels covered, shown as GL texture {}",
                    renderMs, covered, TEXTURE_SIZE * TEXTURE_SIZE, textureId);
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("Vulkan demo frame failed, overlay disabled", t);
        }
    }

}
