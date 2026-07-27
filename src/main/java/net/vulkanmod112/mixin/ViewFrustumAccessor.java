package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code getRenderChunk} is protected and this mixin lives outside the game's
 * package, so the call needs an accessor even though it is legal at runtime.
 */
@Mixin(ViewFrustum.class)
public interface ViewFrustumAccessor {

    @Invoker("getRenderChunk")
    RenderChunk vulkanmod112$getRenderChunk(BlockPos pos);

    /**
     * The grid's shape. The replacement visibility search steps through slots
     * by index rather than looking each one up by world position, so it needs
     * the dimensions the index is built from; all three are protected.
     */
    @Accessor("countChunksX")
    int vulkanmod112$countX();

    @Accessor("countChunksY")
    int vulkanmod112$countY();

    @Accessor("countChunksZ")
    int vulkanmod112$countZ();
}
