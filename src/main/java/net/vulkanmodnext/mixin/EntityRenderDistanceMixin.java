package net.vulkanmodnext.mixin;

import net.minecraft.entity.Entity;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps how far entities are drawn.
 *
 * Vanilla decides per entity from its bounding box, which for large mobs
 * reaches much further than a player can make out. RenderGlobal asks this
 * method before drawing anything, so a distance limit here removes the whole
 * cost of an entity — model, texture binds and all.
 */
@Mixin(Entity.class)
public abstract class EntityRenderDistanceMixin {

    @Inject(method = "isInRangeToRender3d", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$limitDistance(double x, double y, double z,
                                            CallbackInfoReturnable<Boolean> cir) {
        int limit = VulkanConfig.getEntityDistance();
        if (limit <= 0) {
            return;
        }
        Entity self = (Entity) (Object) this;
        double dx = self.posX - x;
        double dy = self.posY - y;
        double dz = self.posZ - z;
        if (dx * dx + dy * dy + dz * dz > (double) limit * limit) {
            cir.setReturnValue(false);
        }
    }
}
