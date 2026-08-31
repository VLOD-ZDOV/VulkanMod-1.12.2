package net.vulkanmodnext.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.vulkanmodnext.client.DynamicLights;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * AbstractClientPlayer p = this.mc.player;
 * int i = this.mc.world.getCombinedLight(new BlockPos(p.posX, p.posY + p.getEyeHeight(), p.posZ), 0);
 * float f = (float)(i &amp; 65535);
 * float f1 = (float)(i &gt;&gt; 16);
 * </pre>
 *
 * <h2>Why the value and not the call</h2>
 *
 * This was a {@code @Redirect} on {@code getCombinedLight} for a whole release,
 * and it never ran once. Not "ran and changed nothing" — never. The counter
 * below is what proved it: the method was entered every frame while the
 * redirect reported zero, which are two different faults needing opposite
 * fixes.
 *
 * The cause is one line of Java that reads as though it says otherwise.
 * {@code getCombinedLight} is declared on {@code World}, but the receiver is
 * {@code Minecraft.world}, whose declared type is {@code WorldClient} — so the
 * compiler writes the call against {@code WorldClient} and a redirect aimed at
 * {@code World} matches nothing in the method. Mixin then skipped it in
 * silence, because an injector without {@code require} is allowed to find no
 * target.
 *
 * Aiming at {@code WorldClient} would work and would break again the moment
 * anything changes the declared type of a field this mixin does not mention.
 * The local variable does not care who was asked: it is the first {@code int}
 * the method stores, and there is exactly one.
 */
@Mixin(ItemRenderer.class)
public abstract class HeldItemLightMixin {

    /** Counts the method itself, to tell "never called" from "never patched". */
    @Inject(method = "setLightmap", at = @At("HEAD"))
    private void vulkanmodnext$countLightmapCall(CallbackInfo ci) {
        DynamicLights.recordHeldItemLightmapCall();
    }

    @ModifyVariable(method = "setLightmap", at = @At("STORE"), ordinal = 0)
    private int vulkanmodnext$lightHeldItem(int packed) {
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
