package net.vulkanmodnext.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.vulkanmodnext.client.AnimatedSprites;
import net.vulkanmodnext.client.ChunkBuildStats;
import net.vulkanmodnext.client.MaterialRuns;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
public abstract class MaterialTagMixin implements AnimatedSprites.SpriteMarked {

    /**
     * Which animated sprites this chunk's blocks use, or null when nothing was
     * recorded — which has to read as "everything", never as "none".
     */
    @Unique
    private volatile long[] vulkanmodnext$sprites;

    @Override
    public long[] vulkanmodnext$animatedSprites() {
        return vulkanmodnext$sprites;
    }

    @Override
    public void vulkanmodnext$animatedSprites(long[] mask) {
        vulkanmodnext$sprites = mask;
    }

    @Redirect(method = "rebuildChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/BlockRendererDispatcher;renderBlock"
                    + "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/world/IBlockAccess;"
                    + "Lnet/minecraft/client/renderer/BufferBuilder;)Z"))
    private boolean vulkanmodnext$tagBlock(BlockRendererDispatcher dispatcher, IBlockState state,
                                          BlockPos pos, IBlockAccess world, BufferBuilder builder) {
        boolean drew = dispatcher.renderBlock(state, pos, world, builder);
        if (VulkanConfig.isMaterialTags()) {
            // Read after the call, and for every block rather than only the
            // ones that drew: a block that added nothing still ends where the
            // one before it ended, and asking the buffer is what keeps this
            // right for a block that writes into two layers at once.
            MaterialRuns.record(state, builder, builder.getVertexCount());
        }
        if (VulkanConfig.isSmartAnimations()) {
            // Which sprites this chunk needs kept moving. Worked out once per
            // block state and then a handful of ors, because the answer is a
            // property of the state and there are a few thousand of those
            // against a few million blocks.
            AnimatedSprites.recordBlock(state);
        }
        return drew;
    }

    @Inject(method = "preRenderBlocks", at = @At("HEAD"))
    private void vulkanmodnext$beginLayer(BufferBuilder builder, BlockPos pos, CallbackInfo ci) {
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
    private void vulkanmodnext$beginRebuild(float x, float y, float z,
                                           ChunkCompileTaskGenerator generator, CallbackInfo ci) {
        ChunkBuildStats.begin(x, y, z);
        if (VulkanConfig.isSmartAnimations()) {
            AnimatedSprites.beginChunk();
        }
    }

    @Inject(method = "rebuildChunk", at = @At("RETURN"))
    private void vulkanmodnext$endRebuild(float x, float y, float z,
                                         ChunkCompileTaskGenerator generator, CallbackInfo ci) {
        ChunkBuildStats.end();
        if (VulkanConfig.isSmartAnimations()) {
            AnimatedSprites.finishChunk((RenderChunk) (Object) this);
        }
    }
}
