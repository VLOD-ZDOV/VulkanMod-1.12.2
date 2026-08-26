package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.util.BlockRenderLayer;
import net.vulkanmod112.client.VanillaFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Diagnostics only: times the two vanilla methods that scan the visible-chunk
 * list, so the part of the frame this mod cannot see stops being a guess.
 * Nothing here changes what is drawn. See {@link VanillaFrame}.
 */
@Mixin(RenderGlobal.class)
public abstract class VanillaFrameMixin {

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void vulkanmod112$beginSetupTerrain(Entity viewEntity, double partialTicks, ICamera camera,
                                                int frameCount, boolean playerSpectator, CallbackInfo ci) {
        VanillaFrame.beginSetupTerrain();
    }

    @Inject(method = "setupTerrain", at = @At("RETURN"))
    private void vulkanmod112$endSetupTerrain(Entity viewEntity, double partialTicks, ICamera camera,
                                              int frameCount, boolean playerSpectator, CallbackInfo ci) {
        VanillaFrame.endSetupTerrain();
    }

    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void vulkanmod112$beginEntities(Entity renderViewEntity, ICamera camera, float partialTicks,
                                            CallbackInfo ci) {
        VanillaFrame.beginEntities();
    }

    @Inject(method = "renderEntities", at = @At("RETURN"))
    private void vulkanmod112$endEntities(Entity renderViewEntity, ICamera camera, float partialTicks,
                                          CallbackInfo ci) {
        VanillaFrame.endEntities();
    }

    /**
     * The block-entity pass, timed on its own.
     *
     * The frame breakdown puts it at 6.2%, and the reason is not the drawing —
     * the frustum test that decides whether a block entity is on screen is
     * already there, Forge added it. It is the loop around it: for every chunk
     * on screen, {@code getCompiledChunk().getTileEntities().isEmpty()} is three
     * pointer chases into three unrelated objects, sixteen thousand times a
     * frame, to find the handful of chunks that contain a chest at all. Same
     * disease as the rebuild scan next door.
     *
     * Timed before being touched. A roadmap item claiming the frustum test was
     * missing sat in the "cheap and safe" list until somebody read the source,
     * and the replacement for it has earned no more trust than that one had.
     */
    @Redirect(method = "renderEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/tileentity/"
                            + "TileEntityRendererDispatcher;preDrawBatch()V"))
    private void vulkanmod112$beginBlockEntities(TileEntityRendererDispatcher dispatcher) {
        VanillaFrame.beginBlockEntities();
        dispatcher.preDrawBatch();
    }

    @Redirect(method = "renderEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/tileentity/"
                            + "TileEntityRendererDispatcher;drawBatch(I)V"))
    private void vulkanmod112$endBlockEntities(TileEntityRendererDispatcher dispatcher, int pass) {
        dispatcher.drawBatch(pass);
        VanillaFrame.endBlockEntities();
    }

    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("HEAD"))
    private void vulkanmod112$beginLayer(BlockRenderLayer blockLayerIn, double partialTicks, int pass,
                                         Entity entityIn, CallbackInfoReturnable<Integer> cir) {
        VanillaFrame.beginLayer(blockLayerIn == BlockRenderLayer.SOLID);
    }

    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("RETURN"))
    private void vulkanmod112$endLayer(BlockRenderLayer blockLayerIn, double partialTicks, int pass,
                                       Entity entityIn, CallbackInfoReturnable<Integer> cir) {
        VanillaFrame.endLayer();
    }
}
