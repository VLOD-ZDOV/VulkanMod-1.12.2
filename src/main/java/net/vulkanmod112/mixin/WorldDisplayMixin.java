package net.vulkanmod112.mixin;

import net.minecraft.world.World;
import net.vulkanmod112.client.WorldDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Changes what the world says the time and the weather are, on this client.
 *
 * <h2>Why the world and not the sky renderer</h2>
 *
 * The sky is drawn from the celestial angle, the light level comes from the sun
 * brightness, the fog colour comes from both, and the shadow direction this mod
 * traces comes from the same angle again. All four of them end at
 * {@code World.getWorldTime()}, so answering there moves all four together and
 * answering anywhere else would move some of them and leave the rest.
 *
 * The same holds for weather: {@code isRaining} and {@code isThundering} are
 * both derived from the two strengths, so those two are the whole of it.
 *
 * <h2>Why this cannot reach the server</h2>
 *
 * {@code isRemote} is false on the world the integrated server ticks and true
 * on the one this client draws — they are different objects of the same class.
 * Every method here returns immediately on the server's copy, so what is
 * changed is what this screen is told and nothing else. Mobs still burn at
 * dawn, and a storm the server believes in still charges a creeper.
 */
@Mixin(World.class)
public abstract class WorldDisplayMixin {

    @Inject(method = "getWorldTime", at = @At("HEAD"), cancellable = true)
    private void vulkanmod112$time(CallbackInfoReturnable<Long> cir) {
        World self = (World) (Object) this;
        if (!self.isRemote || !WorldDisplay.overridingTime()) {
            return;
        }
        // Read from the world info rather than by calling the method this is
        // injected into, which would be this method calling itself.
        cir.setReturnValue(Long.valueOf(
                WorldDisplay.worldTime(self.getWorldInfo().getWorldTime())));
    }

    @Inject(method = "getRainStrength", at = @At("HEAD"), cancellable = true)
    private void vulkanmod112$rain(float delta, CallbackInfoReturnable<Float> cir) {
        World self = (World) (Object) this;
        if (!self.isRemote || !WorldDisplay.overridingWeather()) {
            return;
        }
        cir.setReturnValue(Float.valueOf(WorldDisplay.rain(0.0f)));
    }

    @Inject(method = "getThunderStrength", at = @At("HEAD"), cancellable = true)
    private void vulkanmod112$thunder(float delta, CallbackInfoReturnable<Float> cir) {
        World self = (World) (Object) this;
        if (!self.isRemote || !WorldDisplay.overridingWeather()) {
            return;
        }
        cir.setReturnValue(Float.valueOf(WorldDisplay.thunder(0.0f)));
    }
}
