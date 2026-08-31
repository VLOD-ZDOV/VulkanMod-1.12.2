package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The chunk inside one entry of the visible list. */
// Named by string: the class is package private, so there is no literal to
// use, and this is the case targets = exists for.
@Mixin(targets = "net.minecraft.client.renderer.WorldRenderer$LocalRenderInformationContainer")
public interface ChunkRenderAccess {

    @Accessor("chunk")
    ChunkRenderDispatcher.ChunkRender vulkanmodnext$chunk();
}
