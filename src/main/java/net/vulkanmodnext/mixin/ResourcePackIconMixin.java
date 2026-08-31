package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.util.ResourceLocation;
import net.vulkanmodnext.client.ResourcePackIcons;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops the resource pack screen decoding every icon it can see, every frame.
 *
 * The entry list draws each visible row through {@code bindTexturePackIcon},
 * which reads {@code pack.png} out of the pack and decodes it into a
 * {@code BufferedImage} before doing anything else — then keeps the result only
 * the first time, when it has an upload to make. Every frame after that the
 * decode happens and the image is thrown away: an inflate and a full PNG decode
 * per visible row, sixty times a second, for a texture that has not changed.
 *
 * Packs with no {@code pack.png} of their own are worse. Their fallback goes
 * through the resource manager for the placeholder, and that walks the list of
 * loaded packs — so the cost of drawing one row grows with how many packs are
 * turned on, which is why this is only unbearable for the people who have a lot
 * of them.
 *
 * Nothing here changes what ends up on screen. The icon is uploaded once and
 * vanilla has no path that replaces it afterwards, so once the location exists
 * the only part of the original method that still mattered was the bind.
 */
@Mixin(ResourcePackRepository.Entry.class)
public abstract class ResourcePackIconMixin {

    @Shadow
    private ResourceLocation locationTexturePackIcon;

    /**
     * Started at the head of a call that is going ahead, read at its return.
     * One field is enough: this runs on the client thread with a GUI open, and
     * the call it measures cannot be reentered.
     */
    private static long vulkanmodnext$decodeStartedAt;

    @Inject(method = "bindTexturePackIcon", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$bindCachedIcon(TextureManager textureManager, CallbackInfo ci) {
        if (this.locationTexturePackIcon != null) {
            textureManager.bindTexture(this.locationTexturePackIcon);
            ResourcePackIcons.served();
            ci.cancel();
            return;
        }
        vulkanmodnext$decodeStartedAt = System.nanoTime();
    }

    @Inject(method = "bindTexturePackIcon", at = @At("RETURN"))
    private void vulkanmodnext$timeDecode(TextureManager textureManager, CallbackInfo ci) {
        ResourcePackIcons.decoded(System.nanoTime() - vulkanmodnext$decodeStartedAt);
    }
}
