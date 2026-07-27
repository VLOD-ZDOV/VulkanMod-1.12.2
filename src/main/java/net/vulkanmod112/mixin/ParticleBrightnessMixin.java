package net.vulkanmod112.mixin;

import net.minecraft.client.particle.Particle;
import net.vulkanmod112.client.DynamicLights;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets dynamic light reach particles.
 *
 * Breaking a block underground with a torch in hand produced a cloud of
 * completely black specks against lit stone: the terrain shader had the light
 * and the particle did not. A particle asks for its own light map coordinate
 * rather than borrowing an entity's, so it needs its own hook.
 */
@Mixin(Particle.class)
public abstract class ParticleBrightnessMixin {

    @Shadow
    public double posX;
    @Shadow
    public double posY;
    @Shadow
    public double posZ;

    @Inject(method = "getBrightnessForRender", at = @At("RETURN"), cancellable = true)
    private void vulkanmod112$addDynamicLight(float partialTicks, CallbackInfoReturnable<Integer> cir) {
        int packed = cir.getReturnValueI();
        int raised = DynamicLights.applyTo(packed, this.posX, this.posY, this.posZ);
        if (raised != packed) {
            cir.setReturnValue(raised);
        }
    }
}
