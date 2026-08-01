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
 * Both ends of the call, because the skeleton nests: a part's children are
 * drawn inside its frame, and without the second hook every sibling would be
 * placed inside its elder brother instead of beside him.
 *
 * Neither is cancellable. This is the point where entities would eventually be
 * taken into Vulkan, and it is also the busiest shared method in the client —
 * every creature, every armour layer, every mod that builds its models the
 * ordinary way passes through here. A hook that only looks cannot break a mod
 * that does something unusual, because that mod goes on drawing exactly as it
 * did.
 */
@Mixin(ModelRenderer.class)
public abstract class ModelPartMixin {

    @Inject(method = "render(F)V", at = @At("HEAD"))
    private void vulkanmod112$capture(float scale, CallbackInfo ci) {
        EntityCapture.beginPart((ModelRenderer) (Object) this, scale);
    }

    @Inject(method = "render(F)V", at = @At("RETURN"))
    private void vulkanmod112$leave(float scale, CallbackInfo ci) {
        EntityCapture.endPart();
    }
}
