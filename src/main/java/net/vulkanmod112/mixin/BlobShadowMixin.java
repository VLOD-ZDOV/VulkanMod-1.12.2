package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.settings.GameSettings;
import net.vulkanmod112.client.TerrainHooks;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Drops vanilla's round blur under a creature once a real shadow is cast.
 *
 * The blob is not a shadow, it is a stand-in for one: a circular texture
 * painted on the blocks under the entity, the same shape whatever the creature
 * is and wherever the sun stands. It exists because vanilla has no shadows at
 * all.
 *
 * With creatures in the acceleration structures the sun casts a real one — the
 * shape of the animal, moving with the light, thrown by the same ray the
 * terrain already uses. Two shadows under one cow is worse than either of them
 * alone, so this one goes.
 *
 * The setting is read rather than the drawing suppressed, which is the narrow
 * way to do it: the same method draws the flames on a burning creature a few
 * lines further down, and those have nothing to do with any of this.
 *
 * Every condition is asked at the moment of drawing rather than remembered —
 * tracing running on this device, sun shadows switched on, creatures going
 * through this renderer at all. Without the last one there is no geometry in
 * the structure to cast anything and the blob is all there is, so a setting
 * turned off mid-game brings it back on the next frame.
 */
@Mixin(Render.class)
public abstract class BlobShadowMixin {

    @Redirect(method = "doRenderShadowAndFire",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/settings/GameSettings;entityShadows:Z",
                    opcode = Opcodes.GETFIELD))
    private boolean vulkanmod112$blobStillWanted(GameSettings settings) {
        return settings.entityShadows && !TerrainHooks.creaturesCastRealShadows();
    }
}
