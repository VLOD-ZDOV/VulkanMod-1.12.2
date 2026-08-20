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

    /**
     * Draws the graph on the screens where the game's overlay never runs.
     *
     * That event fires from inside the branch the game takes only with a world
     * loaded, so on the main menu nothing would draw this at all. Filling that
     * gap is what the guard below is for, and it is why it is the way round it
     * is rather than the other.
     *
     * What it does not do — and what the line that stood here claimed it did —
     * is keep the graph alive under a menu opened over a world. That case is
     * excluded here, and a screen paints its own background over the copy the
     * overlay already drew. Whether it should be excluded is an open question
     * and is written up in TESTING.md; the guard has not been touched, because
     * inverting it is a change to what is drawn and nobody has looked yet.
     */
    @SubscribeEvent
    public void onScreenDrawn(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (Minecraft.getMinecraft().world != null) {
            return;
        }
        FrameGraph.draw(new ScaledResolution(Minecraft.getMinecraft()));
    }
}
