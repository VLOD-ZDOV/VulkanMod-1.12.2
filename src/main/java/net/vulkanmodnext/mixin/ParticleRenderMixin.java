package net.vulkanmodnext.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.Entity;
import net.vulkanmodnext.client.ParticleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;

/**
 * Sends the world's particles to Vulkan instead of to OpenGL.
 *
 * All or nothing per frame, and at the head of the method rather than around
 * the draw inside it: the six queues are one unit of work, and taking half of
 * them would put half the particles under the water surface and half over it.
 * The hook declines for the whole frame whenever the Vulkan path is not
 * available, and the game's own loop then runs untouched.
 */
@Mixin(ParticleManager.class)
public abstract class ParticleRenderMixin {

    @Shadow
    private ArrayDeque<Particle>[][] fxLayers;

    @Inject(method = "renderParticles", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$drawInVulkan(Entity viewer, float partialTicks, CallbackInfo ci) {
        if (ParticleHooks.render(this.fxLayers, viewer, partialTicks)) {
            ci.cancel();
        }
    }
}
