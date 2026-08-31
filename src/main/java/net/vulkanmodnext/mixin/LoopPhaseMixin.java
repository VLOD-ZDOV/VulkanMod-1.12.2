package net.vulkanmodnext.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmodnext.client.FramePhases;
import net.vulkanmodnext.client.GlFrameTimer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostics only: times the four things the game's loop does around drawing
 * the world. Nothing here changes what happens.
 *
 * <h2>Why these four</h2>
 *
 * With the world pass itself broken into its own named phases, that pass came
 * to 0.80 ms of a 1.75 ms frame at thirty-two chunks. More than half the frame
 * is therefore spent somewhere the world pass never reaches, and until it is
 * named, every further saving inside the world pass is a saving in the smaller
 * half. These are the four candidates the loop actually has: running a game
 * tick, drawing the world and the interface, copying the game's framebuffer to
 * the window, and handing the window to the driver.
 *
 * Whatever they do not account for is printed as well, because a breakdown that
 * quietly loses a third of the frame is worse than no breakdown at all.
 */
@Mixin(Minecraft.class)
public abstract class LoopPhaseMixin {

    @Shadow
    protected abstract void runTick();

    @Shadow
    protected abstract void checkGLError(String message);

    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void vulkanmodnext$frame(CallbackInfo ci) {
        FramePhases.loopFrame();
        GlFrameTimer.mark(GlFrameTimer.START);
    }

    /**
     * The game clears its own framebuffer here, at whatever size the window is.
     * Timed on the card rather than on the thread, because a clear costs the
     * thread nothing and the card a screenful of writes.
     */
    @Redirect(method = "runGameLoop",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;clear(I)V",
                    ordinal = 0))
    private void vulkanmodnext$clear(int mask) {
        GlStateManager.clear(mask);
        GlFrameTimer.mark(GlFrameTimer.AFTER_CLEAR);
    }

    @Redirect(method = "runGameLoop",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick()V"))
    private void vulkanmodnext$tick(Minecraft self) {
        long started = System.nanoTime();
        runTick();
        FramePhases.addLoop("tick", System.nanoTime() - started);
    }

    @Redirect(method = "runGameLoop",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;"
                            + "updateCameraAndRender(FJ)V"))
    private void vulkanmodnext$render(EntityRenderer renderer, float partialTicks, long nanoTime) {
        long started = System.nanoTime();
        renderer.updateCameraAndRender(partialTicks, nanoTime);
        FramePhases.addLoop("world and interface", System.nanoTime() - started);
        GlFrameTimer.mark(GlFrameTimer.AFTER_WORLD);
    }

    @Redirect(method = "runGameLoop",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/shader/Framebuffer;framebufferRender(II)V"))
    private void vulkanmodnext$blit(Framebuffer framebuffer, int width, int height) {
        long started = System.nanoTime();
        framebuffer.framebufferRender(width, height);
        FramePhases.addLoop("framebuffer to window", System.nanoTime() - started);
        GlFrameTimer.mark(GlFrameTimer.AFTER_BLIT);
    }

    /**
     * Two of these a frame, and between them they hold more than half of it.
     *
     * The name is what it measures, not what it does. The call itself asks the
     * driver whether anything has gone wrong, which is nothing; what it costs
     * is that the answer cannot come back until the driver has caught up with
     * the work already handed to it. Skipping it — {@code -PskipGlErrorCheck}
     * — does not make a single frame faster: the wait moves to the next call
     * that needs the driver, which is the first one of the following frame, and
     * the breakdown then shows it under {@code camera} instead. That experiment
     * is left in place because "just stop asking for errors, everyone does"
     * is a suggestion worth being able to answer with a run rather than an
     * argument.
     *
     * What the number really says is how far behind the card is, and the way to
     * read it is to change the window: it tracks the pixel count and almost
     * nothing else. See {@link FramePhases}.
     */
    @Redirect(method = "runGameLoop",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;checkGLError(Ljava/lang/String;)V"))
    private void vulkanmodnext$errorCheck(Minecraft self, String message) {
        long started = System.nanoTime();
        if (!VULKANMOD112$SKIP_ERROR_CHECK) {
            checkGLError(message);
        }
        FramePhases.addLoop("waiting for the driver to catch up", System.nanoTime() - started);
    }

    /** Under test: see whether the cost is the question or the wait behind it. */
    @Unique
    private static final boolean VULKANMOD112$SKIP_ERROR_CHECK =
            Boolean.getBoolean("vulkanmodnext.skipGlErrorCheck");

    /**
     * The loop hands the processor away once a frame, right after the window
     * goes. With every core already busy building chunks, what comes back is
     * whatever the scheduler decides, and that is not bounded by anything the
     * game knows about.
     */
    @Redirect(method = "runGameLoop",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V"))
    private void vulkanmodnext$yield() {
        long started = System.nanoTime();
        Thread.yield();
        FramePhases.addLoop("standing aside for other threads", System.nanoTime() - started);
    }

    @Redirect(method = "runGameLoop",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;updateDisplay()V"))
    private void vulkanmodnext$display(Minecraft self) {
        long started = System.nanoTime();
        self.updateDisplay();
        FramePhases.addLoop("handing the window over", System.nanoTime() - started);
        GlFrameTimer.mark(GlFrameTimer.AFTER_PRESENT);
    }
}
