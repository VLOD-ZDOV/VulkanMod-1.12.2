package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.culling.ClippingHelper;
import net.vulkanmodnext.client.VanillaFrame;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tests a box against the view frustum by its far corner instead of all eight.
 *
 * Vanilla asks, for each of the six planes, whether every one of the box's
 * eight corners is on the outside of it:
 *
 * <pre>
 * if (dot(plane, minX, minY, minZ) &lt;= 0 &amp;&amp; dot(plane, maxX, minY, minZ) &lt;= 0 &amp;&amp; ... )
 *     return false;
 * </pre>
 *
 * Each {@code dot} is three float-to-double multiplies and three adds, so
 * rejecting a box costs up to forty-eight of them.
 *
 * <h2>Why this is the walk's cost</h2>
 *
 * The game's profiler puts the visibility search at a quarter to a half of the
 * frame at render distance 64, and the search calls this once for every chunk
 * it reaches — {@code setFrameIndex} runs first and marks a chunk as seen even
 * when the frustum then rejects it, so no chunk is tested twice. At around
 * 4 600 chunks drawn the search reaches perhaps three times that, and the
 * measured 37% of the frame works out to roughly 650 ns of work per chunk,
 * which is the order of this arithmetic and not the order of the allocation
 * the search also does.
 *
 * <h2>Why the answer is the same</h2>
 *
 * The dot product is linear and separable per axis, so over the eight corners
 * it is maximised by taking, on each axis independently, the coordinate that
 * the plane's normal points towards. "Every corner is outside" is therefore
 * exactly "that one corner is outside", and the value computed for it is the
 * same expression vanilla evaluates for the same corner — not an approximation
 * of it. Six dot products instead of six to forty-eight, with an early exit
 * that vanilla also has.
 *
 * <h2>The one case handed back</h2>
 *
 * The search seeds itself differently when the camera is outside the world
 * vertically, and stretches the candidate boxes to infinity along Y to do it
 * ({@code RenderGlobal.setupTerrain}, the Forge fix for MC-73139). An infinite
 * coordinate multiplied by a plane normal of exactly zero is NaN, and NaN fails
 * every comparison, so vanilla's chain of {@code &&} treats such a plane as not
 * rejecting — which the corner test would not reproduce, because it picks a
 * finite corner there instead. That combination needs a plane exactly parallel
 * to an axis, which is what a camera at zero pitch gives, so it is reachable
 * rather than theoretical. Boxes with a non-finite coordinate go to vanilla.
 */
@Mixin(ClippingHelper.class)
public abstract class FrustumTestMixin {

    @Shadow
    public float[][] frustum;

    @Inject(method = "isBoxInFrustum", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$farCornerTest(double minX, double minY, double minZ,
                                            double maxX, double maxY, double maxZ,
                                            CallbackInfoReturnable<Boolean> cir) {
        VanillaFrame.countFrustumTest();
        if (!VulkanConfig.isFastFrustumTest()) {
            return;
        }
        if (vulkanmodnext$notFinite(minX) || vulkanmodnext$notFinite(maxX)
                || vulkanmodnext$notFinite(minY) || vulkanmodnext$notFinite(maxY)
                || vulkanmodnext$notFinite(minZ) || vulkanmodnext$notFinite(maxZ)) {
            return;
        }
        float[][] planes = this.frustum;
        for (int i = 0; i < 6; ++i) {
            float[] plane = planes[i];
            float nx = plane[0];
            float ny = plane[1];
            float nz = plane[2];
            double far = (double) nx * (nx > 0.0F ? maxX : minX)
                    + (double) ny * (ny > 0.0F ? maxY : minY)
                    + (double) nz * (nz > 0.0F ? maxZ : minZ)
                    + (double) plane[3];
            if (far <= 0.0D) {
                cir.setReturnValue(false);
                return;
            }
        }
        cir.setReturnValue(true);
    }

    /**
     * A normal component of exactly zero picks the low corner above, and zero
     * times an infinite coordinate is NaN rather than the zero the arithmetic
     * wants, so those boxes are not ours to answer.
     */
    private static boolean vulkanmodnext$notFinite(double v) {
        return v - v != 0.0D;
    }
}
