package net.vulkanmod112.mixin;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;
import net.vulkanmod112.client.VulkanConfig;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pushes the game's distance fog further out, or pulls it in.
 *
 * Written after reading how Celeritas Extra does it (LGPL-3.0,
 * github.com/Sumire-Labs/Celeritas-Extra), which took the idea in turn from
 * Sodium Extra. Not a copy of their code — the arithmetic is two lines and the
 * plumbing is ours — but the rule below is theirs and is the whole reason this
 * is worth doing carefully rather than quickly.
 *
 * <h2>The rule</h2>
 *
 * Some of this game's fog is scenery and some of it is information. Blindness,
 * being under water, being in lava: each of those is told to the player almost
 * entirely by fog, and a setting that thins the haze on a mountain range must
 * not also tell somebody they can see through lava. So the scale is applied to
 * the fog of an ordinary view and to nothing else.
 *
 * <h2>Why this is a change to OpenGL and not to this renderer</h2>
 *
 * The terrain shader does not decide its own fog. It reads the game's — start,
 * end and density, out of GL, once a frame — precisely so that the blocks
 * cannot disagree with the sky, the creatures and everything else the game
 * draws around them. So changing it here changes it everywhere, in one place,
 * and this renderer follows without knowing anything happened.
 *
 * <h2>Why only linear fog</h2>
 *
 * Vanilla uses linear fog for distance and exponential fog for the states
 * above. Scaling a density would be a different sum with a different meaning,
 * and the only fog that wants scaling is the one measured in blocks.
 */
@Mixin(EntityRenderer.class)
public abstract class FogDistanceMixin {

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void vulkanmod112$stretchFog(int startCoords, float partialTicks, CallbackInfo ci) {
        int percent = VulkanConfig.getFogDistance();
        if (percent == 100 || tellsYouSomething()) {
            return;
        }
        if (GL11.glGetInteger(GL11.GL_FOG_MODE) != GL11.GL_LINEAR) {
            return;
        }
        float scale = percent / 100.0f;
        // Through the game's own state tracker, never straight at GL.
        //
        // It remembers what it last set and skips the call when the value has
        // not moved. Writing behind it leaves the two disagreeing: the game
        // believes the fog still starts where it put it, so next frame it sets
        // the same number, sees no change and sends nothing — and this scaling
        // lands on its own previous answer instead of on the game's. Three
        // becomes nine becomes twenty-seven, once a frame, until the numbers
        // stop being numbers, and the terrain shader reading them out of GL
        // fogs the entire world away. Told properly, the tracker sees a value
        // it did not write, restores the game's next frame, and this scales it
        // once more from the same place every time.
        //
        // Both ends, so the band the fog thickens across keeps its shape rather
        // than stretching from a fixed start — pushing only the far end makes a
        // long soft smear instead of a wall of haze further away.
        net.minecraft.client.renderer.GlStateManager.setFogStart(
                GL11.glGetFloat(GL11.GL_FOG_START) * scale);
        net.minecraft.client.renderer.GlStateManager.setFogEnd(
                GL11.glGetFloat(GL11.GL_FOG_END) * scale);
    }

    /**
     * Whether the fog on screen is carrying gameplay state rather than distance.
     *
     * No test for boss fog: it is set up elsewhere and by the time this runs it
     * cannot be told apart from an ordinary view, so it is left alone by being
     * out of reach rather than by being detected.
     */
    private static boolean tellsYouSomething() {
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) {
            return false;
        }
        if (view instanceof EntityLivingBase
                && ((EntityLivingBase) view).isPotionActive(MobEffects.BLINDNESS)) {
            return true;
        }
        return view.isInsideOfMaterial(Material.WATER)
                || view.isInsideOfMaterial(Material.LAVA);
    }
}
