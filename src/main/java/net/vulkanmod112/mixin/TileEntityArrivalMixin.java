package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.vulkanmod112.client.TileEntityArrivals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes a chunk that has just finished building with block entities in it.
 *
 * This is the one moment the shortened block-entity list cannot work out for
 * itself: the list is gathered while the visibility search walks, and a section
 * already on that list can gain a chest a hundred frames later. Only chunks
 * that carry block entities are reported, so the ordinary build — a chunk of
 * stone and air — costs one comparison against an empty list and nothing else.
 *
 * A chunk losing its block entities is not reported and does not need to be: a
 * stale entry names a section whose compiled chunk hands back an empty list,
 * and the game draws nothing from it.
 */
@Mixin(RenderChunk.class)
public abstract class TileEntityArrivalMixin {

    @Inject(method = "setCompiledChunk", at = @At("RETURN"))
    private void vulkanmod112$noteBlockEntities(CompiledChunk compiled, CallbackInfo ci) {
        if (compiled != null && !compiled.getTileEntities().isEmpty()) {
            TileEntityArrivals.arrived((RenderChunk) (Object) this);
        }
    }
}
