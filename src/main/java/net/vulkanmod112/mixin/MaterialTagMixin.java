package net.vulkanmod112.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.vulkanmod112.client.ChunkBuildStats;
import net.vulkanmod112.client.MaterialRuns;
import net.vulkanmod112.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records what each stretch of a chunk's geometry is made of, while the chunk
 * is being built and the answer is still knowable.
 *
 * See {@link MaterialRuns} for why the information exists nowhere else. There
 * are two hooks and both are on the one loop:
 *
 * - the single {@code renderBlock} call, whose block state says what is being
 *   drawn and whose buffer says how much of it was drawn;
 * - {@code preRenderBlocks}, which the loop calls the first time it writes into
 *   a layer, and which is therefore where a buffer stops belonging to the
 *   chunk before it.
 *
 * The redirect calls straight through when the setting is off, which is a
 * predicted branch on a call that already dispatches through a block model.
 */
@Mixin(RenderChunk.class)
public abstract class MaterialTagMixin {

    @Redirect(method = "rebuildChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/BlockRendererDispatcher;renderBlock"
                    + "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/world/IBlockAccess;"
                    + "Lnet/minecraft/client/renderer/BufferBuilder;)Z"))
    private boolean vulkanmod112$tagBlock(BlockRendererDispatcher dispatcher, IBlockState state,
                                          BlockPos pos, IBlockAccess world, BufferBuilder builder) {
        boolean drew = dispatcher.renderBlock(state, pos, world, builder);
        if (VulkanConfig.isMaterialTags()) {
            // Read after the call, and for every block rather than only the
            // ones that drew: a block that added nothing still ends where the
            // one before it ended, and asking the buffer is what keeps this
            // right for a block that writes into two layers at once.
            MaterialRuns.record(state, builder, builder.getVertexCount());
        }
        return drew;
    }

    @Inject(method = "preRenderBlocks", at = @At("HEAD"))
    private void vulkanmod112$beginLayer(BufferBuilder builder, BlockPos pos, CallbackInfo ci) {
        if (VulkanConfig.isMaterialTags()) {
            MaterialRuns.begin(builder);
        }
    }

    /**
     * Times the whole rebuild, on both sides of the switch.
     *
     * This is the number that answers whether the recording costs anything:
     * fly the same ground with the setting on and off and compare what a chunk
     * takes to build. Timing the recording itself per block was tried and
     * measured mostly the clock.
     */
    @Inject(method = "rebuildChunk", at = @At("HEAD"))
    private void vulkanmod112$beginRebuild(float x, float y, float z,
                                           ChunkCompileTaskGenerator generator, CallbackInfo ci) {
        ChunkBuildStats.begin();
    }

    @Inject(method = "rebuildChunk", at = @At("RETURN"))
    private void vulkanmod112$endRebuild(float x, float y, float z,
                                         ChunkCompileTaskGenerator generator, CallbackInfo ci) {
        ChunkBuildStats.end();
    }
}
