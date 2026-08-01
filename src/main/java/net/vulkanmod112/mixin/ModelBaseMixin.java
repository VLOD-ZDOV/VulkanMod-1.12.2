package net.vulkanmod112.mixin;

import net.minecraft.client.model.ModelBase;
import net.minecraft.entity.Entity;
import net.vulkanmod112.client.EntityCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one place the matrix stack still has to be asked where a creature is.
 *
 * Everything below this — which bone is where — is arithmetic over fields the
 * bones already hold, so the driver is asked once for the whole model instead
 * of once for each of a dozen bones. That difference is what decides whether
 * entities can be taken into Vulkan at all: asking per bone was measured at
 * most of a millisecond a frame, against a whole Vulkan terrain pass of a
 * third of one.
 */
@Mixin(ModelBase.class)
public abstract class ModelBaseMixin {

    @Inject(method = "render(Lnet/minecraft/entity/Entity;FFFFFF)V", at = @At("HEAD"))
    private void vulkanmod112$beginModel(Entity entity, float limbSwing, float limbSwingAmount,
                                         float ageInTicks, float netHeadYaw, float headPitch,
                                         float scale, CallbackInfo ci) {
        EntityCapture.beginModel();
    }
}
