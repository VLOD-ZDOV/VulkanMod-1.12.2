package net.vulkanmodnext.mixin;

import net.minecraft.world.World;
import net.vulkanmodnext.client.WorldDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Changes what the world says the time and the weather are, on this client.
 *
 * <h2>Why the angle and not the clock</h2>
 *
 * The sky, the light level, the fog colour and the shadow direction this mod
 * traces all end at {@code getCelestialAngle}, so answering there moves the
 * four together and answering further out would move some and leave the rest.
 *
 * Answering at {@code getWorldTime()} would look like the tidier place and is
 * a trap: {@code WorldClient.tick()} runs
 * {@code setWorldTime(getWorldTime() + 1)} every tick, so the client would
 * read our invented hour and write it into its own world info — the clock
 * would really stop, and switching the setting off would leave the world at a
 * time it was never at. The angle is read and never written back.
 *
 * It also leaves the moon phase alone, which is counted from the day number
 * rather than from the angle, so moving the slider does not move the moon.
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

    @Inject(method = "getCelestialAngle", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$angle(float partialTicks, CallbackInfoReturnable<Float> cir) {
        World self = (World) (Object) this;
        if (!self.isRemote || !WorldDisplay.overridingTime()) {
            return;
        }
        long shown = WorldDisplay.worldTime(self.getWorldInfo().getWorldTime());
        cir.setReturnValue(Float.valueOf(
                self.provider.calculateCelestialAngle(shown, partialTicks)));
    }

    @Inject(method = "getRainStrength", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$rain(float delta, CallbackInfoReturnable<Float> cir) {
        World self = (World) (Object) this;
        if (!self.isRemote || !WorldDisplay.overridingWeather()) {
            return;
        }
        cir.setReturnValue(Float.valueOf(WorldDisplay.rain(0.0f)));
    }

    @Inject(method = "getThunderStrength", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$thunder(float delta, CallbackInfoReturnable<Float> cir) {
        World self = (World) (Object) this;
        if (!self.isRemote || !WorldDisplay.overridingWeather()) {
            return;
        }
        cir.setReturnValue(Float.valueOf(WorldDisplay.thunder(0.0f)));
    }
}
