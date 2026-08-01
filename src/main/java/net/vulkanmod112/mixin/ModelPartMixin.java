package net.vulkanmod112.mixin;

import net.minecraft.client.model.ModelRenderer;
import net.vulkanmod112.client.EntityCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches every model part the game draws, and changes nothing.
 *
 * At the head and not cancellable on purpose. This is the point where entities
 * would eventually be taken into Vulkan, and it is also the busiest shared
 * method in the client — every creature, every armour layer, every mod that
 * builds its models the ordinary way passes through here. Before anything is
 * taken over, what matters is how much of a real scene arrives at all and what
 * it costs to look; a hook that only looks cannot break a mod that does
 * something unusual, because that mod goes on drawing exactly as it did.
 */
@Mixin(ModelRenderer.class)
public abstract class ModelPartMixin {

    @Inject(method = "render(F)V", at = @At("HEAD"))
    private void vulkanmod112$capture(float scale, CallbackInfo ci) {
        EntityCapture.part((ModelRenderer) (Object) this, scale);
    }
}
