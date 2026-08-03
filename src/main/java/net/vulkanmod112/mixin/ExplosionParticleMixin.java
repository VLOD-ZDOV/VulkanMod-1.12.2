package net.vulkanmod112.mixin;

import net.minecraft.util.EnumParticleTypes;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.vulkanmod112.client.ExplosionParticles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a tonne of TNT from asking for a particle per broken block.
 *
 * The client is handed the list of positions the server destroyed and walks it,
 * asking for a puff and a smoke at each one. Two hundred charges of a hundred
 * blocks is forty thousand particles in a single tick — each of them an object
 * to tick, sort and draw — and the tick they are born in is the one that stops.
 *
 * One redirect covers every request in the method: the explosion puff, the huge
 * and large ones at the centre, and the per-block pair. The centre ones are
 * asked for first and therefore always inside the budget, which is the right
 * order — they are the explosion, and the per-block pairs are the texture of it.
 *
 * Guarded on {@code isRemote} even though only the client ever passes true
 * here, because this is a class the integrated server also runs.
 */
@Mixin(Explosion.class)
public abstract class ExplosionParticleMixin {

    @Redirect(method = "doExplosionB",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;spawnParticle"
                            + "(Lnet/minecraft/util/EnumParticleTypes;DDDDDD[I)V"))
    private void vulkanmod112$budget(World world, EnumParticleTypes type,
                                     double x, double y, double z,
                                     double xSpeed, double ySpeed, double zSpeed,
                                     int[] parameters) {
        if (!world.isRemote || ExplosionParticles.allow()) {
            world.spawnParticle(type, x, y, z, xSpeed, ySpeed, zSpeed, parameters);
        }
    }
}
