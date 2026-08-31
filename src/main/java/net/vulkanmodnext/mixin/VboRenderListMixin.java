package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.VboRenderList;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import net.vulkanmodnext.client.TerrainHooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The chunk-drawing chokepoint: when this fires, the game has already built
 * the exact visible chunk list for the layer. If the Vulkan terrain renderer
 * takes the layer, the vanilla GL draw is skipped entirely.
 */
@Mixin(VboRenderList.class)
public abstract class VboRenderListMixin extends net.minecraft.client.renderer.ChunkRenderContainer {

    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$renderViaVulkan(BlockRenderLayer layer, CallbackInfo ci) {
        List<RenderChunk> chunks = this.renderChunks;
        if (TerrainHooks.renderChunkLayer(layer, chunks)) {
            chunks.clear();
            ci.cancel();
            return;
        }
        // Vanilla is about to draw this one. Timing it is how we learn what the
        // layers we do not take actually cost — over an ocean that is the whole
        // frame, and it decides whether moving translucent into Vulkan pays.
        TerrainHooks.beginVanillaLayer(layer, chunks.size());
    }

    @Inject(method = "renderChunkLayer", at = @At("RETURN"))
    private void vulkanmodnext$timeVanillaLayer(BlockRenderLayer layer, CallbackInfo ci) {
        TerrainHooks.endVanillaLayer();
    }

}
