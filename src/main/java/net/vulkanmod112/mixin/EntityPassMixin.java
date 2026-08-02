package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.vulkanmod112.client.EntityCapture;
import net.vulkanmod112.client.EntityGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks where the game is drawing the world's creatures.
 *
 * Without it the model hook would also fire for a mob in an inventory slot, a
 * spawn egg preview or a mod's own screen — none of which are the world, and
 * all of which would be counted as though they were. The pose read there is
 * relative to the camera, and there is no camera in a menu.
 */
@Mixin(RenderGlobal.class)
public abstract class EntityPassMixin {

    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void vulkanmod112$armCapture(net.minecraft.entity.Entity viewer,
                                         net.minecraft.client.renderer.culling.ICamera camera,
                                         float partialTicks, CallbackInfo ci) {
        EntityCapture.arm();
        // Here and not later: at this moment the model-view holds the camera
        // and nothing else, so inverting it is inverting the view.
        EntityGeometry.beginPass();
    }

    @Inject(method = "renderEntities", at = @At("RETURN"))
    private void vulkanmod112$disarmCapture(net.minecraft.entity.Entity viewer,
                                            net.minecraft.client.renderer.culling.ICamera camera,
                                            float partialTicks, CallbackInfo ci) {
        EntityCapture.disarm();
        EntityGeometry.flushToBridge();
    }
}
