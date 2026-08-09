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

    /**
     * Forge runs this method twice a frame, and only the first is ours.
     *
     * The second call happens after the translucent layer, for entities that
     * ask to be drawn in that pass. By then this frame's translucent
     * submission has already gone and its sprite batches have been cleared, so
     * anything captured there is not drawn this frame — it sits in the buffer
     * and arrives in the next one, mixed in with a different frame's particles.
     * Vanilla creatures answer no to the second pass and it stays empty, so the
     * only thing this ever produced was a mod's own translucent layer arriving
     * one frame late. Left to the game, which draws it correctly.
     */
    private static boolean secondPass() {
        return net.minecraftforge.client.MinecraftForgeClient.getRenderPass() != 0;
    }

    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void vulkanmod112$armCapture(net.minecraft.entity.Entity viewer,
                                         net.minecraft.client.renderer.culling.ICamera camera,
                                         float partialTicks, CallbackInfo ci) {
        if (secondPass()) {
            return;
        }
        EntityCapture.arm();
        // Here and not later: at this moment the model-view holds the camera
        // and nothing else, so inverting it is inverting the view.
        EntityGeometry.beginPass();
    }

    @Inject(method = "renderEntities", at = @At("RETURN"))
    private void vulkanmod112$disarmCapture(net.minecraft.entity.Entity viewer,
                                            net.minecraft.client.renderer.culling.ICamera camera,
                                            float partialTicks, CallbackInfo ci) {
        if (secondPass()) {
            return;
        }
        EntityCapture.disarm();
        EntityGeometry.flushToBridge();
        // And stand down until the next frame's first pass: without this the
        // model hook goes on taking parts through Forge's second pass and
        // cancelling the game's own drawing of them, which would lose exactly
        // the entities this is stepping aside for.
        EntityGeometry.endPass();
    }
}
