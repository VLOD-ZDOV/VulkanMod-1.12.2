package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.profiler.Profiler;
import net.vulkanmodnext.client.FramePhases;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostics only: puts a clock on the phase boundaries the game already
 * declares inside its world pass. Nothing here changes what is drawn.
 *
 * The boundaries are the calls that tell the game's own profiler which part of
 * the frame is starting. They happen whether or not profiling is switched on —
 * it is the body of those calls that is conditional, not the calls — so reading
 * the clock at each one costs twenty-odd readings a frame and needs no marks of
 * this mod's own anywhere. See {@link FramePhases}.
 */
@Mixin(EntityRenderer.class)
public abstract class FramePhaseMixin {

    @Redirect(method = "renderWorldPass",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V"),
            require = 0)
    private void vulkanmodnext$phase(Profiler profiler, String name) {
        FramePhases.mark(name);
        profiler.endStartSection(name);
    }

    @Inject(method = "renderWorldPass", at = @At("RETURN"))
    private void vulkanmodnext$endPass(int pass, float partialTicks, long finishTimeNano,
                                      CallbackInfo ci) {
        FramePhases.endPass();
    }
}
