package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Drives the frame-time graph.
 *
 * Registered whether or not Vulkan came up, because the graph is a measuring
 * instrument and the case where it is most wanted is the one where the renderer
 * fell back to OpenGL and something is wrong.
 */
public final class FrameGraphHandler {

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FrameGraph.record();
        }
    }

    @SubscribeEvent
    public void onHudRendered(RenderGameOverlayEvent.Post event) {
        if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
            FrameGraph.draw(event.getResolution());
        }
    }

    /** So the graph does not vanish the moment a menu is opened to read it. */
    @SubscribeEvent
    public void onScreenDrawn(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (Minecraft.getMinecraft().world != null) {
            return;
        }
        FrameGraph.draw(new ScaledResolution(Minecraft.getMinecraft()));
    }
}
