package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import net.vulkanmod112.client.VanillaFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Diagnostics only: times the two vanilla methods that scan the visible-chunk
 * list, so the part of the frame this mod cannot see stops being a guess.
 * Nothing here changes what is drawn. See {@link VanillaFrame}.
 */
@Mixin(RenderGlobal.class)
public abstract class VanillaFrameMixin {

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
