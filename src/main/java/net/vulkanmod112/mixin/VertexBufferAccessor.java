package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.vertex.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the GL buffer id so the terrain renderer can look up its Vulkan mirror. */
@Mixin(VertexBuffer.class)
public interface VertexBufferAccessor {

    @Accessor("glBufferId")
    int vulkanmod112$getGlBufferId();

}
