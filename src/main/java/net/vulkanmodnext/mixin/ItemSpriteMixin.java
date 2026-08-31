package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.vulkanmodnext.client.AnimatedSprites;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes which animated textures an item is made of, so that Smart Animations
 * does not stop them.
 *
 * An item is drawn in the hand, in a menu, on the ground and in a frame, and
 * none of that is terrain — so no chunk will ever say it needs those sprites,
 * and a rule built only on what chunks use would freeze the clock in your
 * inventory. This is the one place all four cases pass through.
 *
 * Does nothing at all unless the setting is on.
 */
@Mixin(RenderItem.class)
public abstract class ItemSpriteMixin {

    @Inject(method = "renderModel(Lnet/minecraft/client/renderer/block/model/IBakedModel;"
            + "ILnet/minecraft/item/ItemStack;)V", at = @At("HEAD"), require = 0)
    private void vulkanmodnext$noteItemSprites(IBakedModel model, int color, ItemStack stack,
                                              CallbackInfo ci) {
        if (!VulkanConfig.isSmartAnimations() || model == null) {
            return;
        }
        try {
            AnimatedSprites.recordItemModel(model, model.getQuads(null, null, 0L));
            for (EnumFacing face : EnumFacing.VALUES) {
                AnimatedSprites.recordItemModel(model, model.getQuads(null, face, 0L));
            }
        } catch (Throwable ignored) {
            // An item model that refuses to list its quads costs a saving and
            // never an animation: the sprites simply stay in whatever set they
            // were already in.
        }
    }
}
