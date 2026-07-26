package net.vulkanmod112.mixin;

import net.minecraft.tileentity.TileEntity;
import net.vulkanmod112.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps how far block entities are drawn.
 *
 * Chests, signs and banners each cost a separate draw with their own model and
 * texture, and vanilla's default reach of 64 blocks applies to every one of
 * them. Lowering it is felt immediately in storage rooms.
 */
@Mixin(TileEntity.class)
public abstract class TileEntityRenderDistanceMixin {

    @Inject(method = "getMaxRenderDistanceSquared", at = @At("HEAD"), cancellable = true)
    private void vulkanmod112$limitDistance(CallbackInfoReturnable<Double> cir) {
        int limit = VulkanConfig.getTileEntityDistance();
        if (limit > 0) {
            cir.setReturnValue((double) limit * limit);
        }
    }
}
