package net.vulkanmodnext.mixin;

import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.renderer.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The list of chunks the game has decided are visible this frame.
 *
 * <p>It is the game's own answer, in the game's own order, and taking it rather
 * than working one out is deliberate: the first thing this port has to prove is
 * that it can draw what vanilla draws. A visibility search of our own is an
 * optimisation and comes later, with a measurement beside it.
 */
@Mixin(WorldRenderer.class)
public interface WorldRendererAccess {

    @Accessor("renderChunks")
    ObjectList<?> vulkanmodnext$visibleChunks();
}
