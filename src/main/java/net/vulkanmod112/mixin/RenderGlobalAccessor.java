package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The grid of chunk renderers, so a probe can ask about one by name. */
@Mixin(RenderGlobal.class)
public interface RenderGlobalAccessor {

    @Accessor("viewFrustum")
    ViewFrustum vulkanmod112$viewFrustum();
}
