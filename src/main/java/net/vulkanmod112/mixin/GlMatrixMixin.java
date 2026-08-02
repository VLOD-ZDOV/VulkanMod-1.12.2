package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod112.client.GlMatrixMirror;
import net.vulkanmod112.client.GlTextureMirror;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

/**
 * Watches every change to the model-view matrix, and makes none.
 *
 * The alternative is asking the driver where a bone is, which was measured at
 * about two and a half microseconds and, at a few hundred bones a frame, at
 * most of a millisecond — against a whole Vulkan terrain pass of a third of
 * one. Every one of these calls is a handful of multiplications; doing them
 * twice costs a fraction of what asking the answer back does.
 *
 * These are the busiest methods in the client, so each hook is a single static
 * call that returns immediately when nothing is watching.
 */
@Mixin(GlStateManager.class)
public abstract class GlMatrixMixin {

    /**
     * Which picture is about to be stretched over the next model.
     *
     * Here rather than in a mixin of its own because it is the same class, the
     * same shape of hook and the same reason: these are the busiest methods in
     * the client, and the alternative is asking the driver.
     */
    @Inject(method = "setActiveTexture", at = @At("HEAD"))
    private static void vulkanmod112$activeTexture(int texture, CallbackInfo ci) {
        GlTextureMirror.setActiveTexture(texture);
    }

    @Inject(method = "bindTexture", at = @At("HEAD"))
    private static void vulkanmod112$bindTexture(int texture, CallbackInfo ci) {
        GlTextureMirror.bindTexture(texture);
    }

    @Inject(method = "matrixMode", at = @At("HEAD"))
    private static void vulkanmod112$matrixMode(int mode, CallbackInfo ci) {
        GlMatrixMirror.setMode(mode);
    }

    @Inject(method = "pushMatrix", at = @At("HEAD"))
    private static void vulkanmod112$push(CallbackInfo ci) {
        GlMatrixMirror.push();
    }

    @Inject(method = "popMatrix", at = @At("HEAD"))
    private static void vulkanmod112$pop(CallbackInfo ci) {
        GlMatrixMirror.pop();
    }

    @Inject(method = "loadIdentity", at = @At("HEAD"))
    private static void vulkanmod112$identity(CallbackInfo ci) {
        GlMatrixMirror.loadIdentity();
    }

    @Inject(method = "translate(FFF)V", at = @At("HEAD"))
    private static void vulkanmod112$translateF(float x, float y, float z, CallbackInfo ci) {
        GlMatrixMirror.translate(x, y, z);
    }

    @Inject(method = "translate(DDD)V", at = @At("HEAD"))
    private static void vulkanmod112$translateD(double x, double y, double z, CallbackInfo ci) {
        GlMatrixMirror.translate(x, y, z);
    }

    @Inject(method = "scale(FFF)V", at = @At("HEAD"))
    private static void vulkanmod112$scaleF(float x, float y, float z, CallbackInfo ci) {
        GlMatrixMirror.scale(x, y, z);
    }

    @Inject(method = "scale(DDD)V", at = @At("HEAD"))
    private static void vulkanmod112$scaleD(double x, double y, double z, CallbackInfo ci) {
        GlMatrixMirror.scale(x, y, z);
    }

    @Inject(method = "rotate(FFFF)V", at = @At("HEAD"))
    private static void vulkanmod112$rotate(float angle, float x, float y, float z,
                                            CallbackInfo ci) {
        GlMatrixMirror.rotate(angle, x, y, z);
    }

    @Inject(method = "multMatrix", at = @At("HEAD"))
    private static void vulkanmod112$multiply(FloatBuffer matrix, CallbackInfo ci) {
        GlMatrixMirror.multiply(matrix);
    }
}
