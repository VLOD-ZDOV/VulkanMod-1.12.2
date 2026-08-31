package net.vulkanmodnext.mixin;

import net.minecraft.entity.Entity;
import net.vulkanmodnext.client.DynamicLights;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets dynamic light fall on entities too, not only on the ground under them.
 *
 * The terrain shader adds carried light while it shades a block face, which
 * reaches everything this renderer draws and nothing it does not — so a mob
 * standing in the pool of light from a dropped torch stayed as dark as if the
 * torch were not there, with the lit ground visible around its feet.
 *
 * Entities are drawn by the game from a single light map coordinate per entity,
 * which {@code getBrightnessForRender} produces by asking the world what light
 * reaches them. Raising the block-light half of that answer is the whole of it:
 * the entity is then drawn from the same warm row of the light map the ground
 * beneath it is using, by the game's own renderer, with nothing else changed.
 *
 * The value is packed as {@code sky << 20 | block << 4}, both halves being
 * levels of 0 to 15.
 */
@Mixin(Entity.class)
public abstract class EntityBrightnessMixin {

    @Inject(method = "getBrightnessForRender", at = @At("RETURN"), cancellable = true)
    private void vulkanmodnext$addDynamicLight(CallbackInfoReturnable<Integer> cir) {
        if (!VulkanConfig.isDynamicLights()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        int packed = cir.getReturnValueI();
        int raised = DynamicLights.applyTo(packed, self.posX,
                self.posY + self.getEyeHeight() * 0.5, self.posZ);
        if (raised != packed) {
            cir.setReturnValue(raised);
        }
    }
}
