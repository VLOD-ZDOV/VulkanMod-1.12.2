package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Moves the near clipping plane out, which is the only cure for depth fighting
 * at long render distances.
 *
 * Vanilla builds its projection with a near plane of 0.05 blocks:
 *
 * <pre>
 * Project.gluPerspective(fov, aspect, 0.05F, this.farPlaneDistance * MathHelper.SQRT_2);
 * </pre>
 *
 * The depth buffer is 24-bit and its precision falls off with the square of the
 * distance divided by the near plane, so the gap it can still resolve at a
 * distance z is roughly {@code z² / (near · 2²⁴)}. Three hundred blocks out with
 * near at 0.05 that is about 0.107 blocks — and a snow layer sits 0.125 blocks
 * above the block it covers, whose top face is still drawn because a one-deep
 * snow layer is not an opaque cube. Two surfaces a hair further apart than the
 * depth buffer can tell: the speckled grey and sand showing through white snow
 * that gets reported from any high vantage point.
 *
 * At 0.2 the same figure is 0.027 blocks, comfortably under the gap.
 *
 * The near plane cannot be changed for the Vulkan pass alone: the depth buffer
 * is shared with OpenGL, which draws entities and water into it from the same
 * matrix. So this changes the game's projection, and everything downstream
 * follows automatically.
 *
 * The price: geometry closer to the eye than the near plane is clipped away.
 * Pressed against a wall, or with the head inside a block, a larger value can
 * open a hole into it. At 0.2 that is four fifths of the way from the eye to the
 * face of the block it is inside, which is why that value ships on and why the
 * setting still goes back to vanilla.
 */
@Mixin(EntityRenderer.class)
public abstract class NearPlaneMixin {

    /** The projection the world is drawn with when render distance is under 4. */
    @ModifyConstant(method = "setupCameraTransform", constant = @Constant(floatValue = 0.05F))
    private float vulkanmodnext$cameraNearPlane(float vanilla) {
        return vulkanmodnext$nearPlane(vanilla);
    }

    /**
     * Both calls in {@code renderWorldPass}: the sky is drawn with its own far
     * plane and then the projection is re-established for the world. The second
     * is the one terrain is actually drawn with, and they have to agree — a sky
     * and a world on different near planes would not share a depth buffer.
     */
    @ModifyConstant(method = "renderWorldPass", constant = @Constant(floatValue = 0.05F))
    private float vulkanmodnext$worldNearPlane(float vanilla) {
        return vulkanmodnext$nearPlane(vanilla);
    }

    /**
     * Clouds set up a projection of their own and then put the world's back, and
     * that restore carries its own copy of the constant. Below cloud height they
     * are drawn <em>before</em> the terrain, so leaving this one alone silently
     * undid the setting for everyone standing on the ground: the value only ever
     * reached the terrain from above the clouds, where they are drawn last.
     *
     * Both constants in the method are moved, the clouds' own and the restore, so
     * that clouds keep the same relationship to terrain depth that vanilla gives
     * them — vanilla already draws them with a different far plane.
     */
    @ModifyConstant(method = "renderCloudsCheck", constant = @Constant(floatValue = 0.05F))
    private float vulkanmodnext$cloudsNearPlane(float vanilla) {
        return vulkanmodnext$nearPlane(vanilla);
    }

    private float vulkanmodnext$nearPlane(float vanilla) {
        int hundredths = VulkanConfig.getNearPlaneHundredths();
        return hundredths <= 0 ? vanilla : hundredths / 100.0f;
    }
}
