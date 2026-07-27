package net.vulkanmod112.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.vulkanmod112.client.DynamicLights;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lights the item held in first person by whatever the player is carrying.
 *
 * A torch in the hand lit the ground and left itself in the dark, which is the
 * one place the light is most obviously expected to be. Raising the player's
 * own brightness did not reach it, because this is the one caller that does not
 * ask an entity anything — {@code ItemRenderer.setLightmap} reads the world
 * directly at the player's eye:
 *
 * <pre>
 * int i = this.mc.world.getCombinedLight(new BlockPos(player.posX, player.posY + eyeHeight, player.posZ), 0);
 * </pre>
 *
 * So the call itself is redirected rather than its caller patched.
 */
@Mixin(ItemRenderer.class)
public abstract class HeldItemLightMixin {

    @Redirect(method = "setLightmap",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getCombinedLight(Lnet/minecraft/util/math/BlockPos;I)I"))
    private int vulkanmod112$lightHeldItem(World world, BlockPos pos, int minimum) {
        int packed = world.getCombinedLight(pos, minimum);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {
            return packed;
        }
        int raised = DynamicLights.applyTo(packed, mc.player.posX,
                mc.player.posY + mc.player.getEyeHeight(), mc.player.posZ);
        // Recorded here, at the only place that knows both what the game asked
        // for and what it was given.
        DynamicLights.recordHeldItemLight(packed, raised);
        return raised;
    }
}
