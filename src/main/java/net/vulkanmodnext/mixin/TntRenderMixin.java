package net.vulkanmodnext.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.entity.RenderTNTPrimed;
import net.vulkanmodnext.client.TntModelCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Serves the primed TNT cube from a recorded list rather than rebuilding it.
 *
 * One redirect covers all three requests in the method — the block itself, the
 * team outline and the white flash — because all three are the same cube asked
 * for at a different brightness, which is exactly what the cache is keyed on.
 *
 * The fallback is the call that was there: when the cache declines, for any
 * reason at all, the game draws it the way it always did.
 */
@Mixin(RenderTNTPrimed.class)
public abstract class TntRenderMixin {

    @Redirect(method = "doRender",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/BlockRendererDispatcher;"
                            + "renderBlockBrightness(Lnet/minecraft/block/state/IBlockState;F)V"))
    private void vulkanmodnext$cachedCube(BlockRendererDispatcher dispatcher,
                                         IBlockState state, float brightness) {
        if (!TntModelCache.draw(state, brightness)) {
            dispatcher.renderBlockBrightness(state, brightness);
        }
    }
}
