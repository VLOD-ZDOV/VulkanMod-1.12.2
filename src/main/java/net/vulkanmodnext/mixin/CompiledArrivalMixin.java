package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.vulkanmodnext.client.CompiledArrivals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes a chunk that has just finished building with something in it.
 *
 * This is the one moment the shortened lists cannot work out for themselves:
 * they are gathered while the visibility search walks, and a section already on
 * that list can gain its blocks, or a chest, a hundred frames later. A chunk
 * that came out empty is not reported, which is most of the air in the world.
 *
 * A chunk losing what it had is not reported either, and does not need to be: a
 * stale entry names a section whose compiled chunk answers "nothing here" to
 * every question the game asks about it.
 */
@Mixin(RenderChunk.class)
public abstract class CompiledArrivalMixin {

    @Inject(method = "setCompiledChunk", at = @At("RETURN"))
    private void vulkanmodnext$noteArrival(CompiledChunk compiled, CallbackInfo ci) {
        if (compiled != null && (!compiled.isEmpty() || !compiled.getTileEntities().isEmpty())) {
            CompiledArrivals.arrived((RenderChunk) (Object) this);
        }
    }
}
