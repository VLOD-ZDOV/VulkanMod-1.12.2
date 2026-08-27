package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.vulkanmod112.client.VanillaFrame;
import net.vulkanmod112.client.VulkanConfig;
import net.vulkanmod112.client.WalkTimer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts what makes the game redo its visibility walk.
 *
 * The walk is the flood fill in {@code RenderGlobal.setupTerrain} that decides
 * which chunks are on screen. The game's own profiler puts it at 25% to 48% of
 * the frame at render distance 64 — the largest single item, some eight times
 * what drawing the world costs — and it reruns whenever
 * {@code displayListEntitiesDirty} is set. Two writes set it, both running
 * after the walk, later in the same frame:
 *
 * <pre>
 * // setupTerrain, in the loop that refills the rebuild queue
 * if (renderchunk4.needsUpdate() || set.contains(renderchunk4)) {
 *     this.displayListEntitiesDirty = true;
 *
 * // updateChunks, every frame
 * this.displayListEntitiesDirty |= this.renderDispatcher.runChunkUploads(finishTimeNano);
 * </pre>
 *
 * So any visible chunk waiting to be rebuilt, and any chunk finishing its
 * upload, buys a full walk on the next frame.
 *
 * <h2>What this used to do, and why it does not</h2>
 *
 * Those two writes used to be rate-limited here — held back while the camera
 * stood still until a timer elapsed. That is gone. Not because it was broken,
 * but because it was measured: of ten thousand requests a second, nine and a
 * half thousand come from the camera moving rather than from a chunk
 * finishing, and the limiter deliberately never touched those. There is no
 * interval at which it would have helped more than the seed cache already
 * does, so its setting had no position worth choosing, stood at zero, and made
 * every branch under it unreachable.
 *
 * <h2>What is left, and why the class is</h2>
 *
 * The counting. "The walk ran on 1882 frames of 2316, from 136399 requests, of
 * which 102198 were the camera moving" is the sentence that says where the
 * frame went, and nothing else in the game can say it — the flag is written
 * from three places that share nothing but the field, and only two of the
 * three arm it: the third is vanilla clearing it as the walk begins.
 *
 * Camera movement is judged against this class's own record of the previous
 * frame, not vanilla's: the game overwrites its {@code lastViewEntity*} fields
 * near the top of {@code setupTerrain}, before the write watched here, so
 * comparing against those compares a frame to itself and never sees movement.
 */
@Mixin(RenderGlobal.class)
public abstract class VisibilityWalkMixin implements WalkTimer {

    @Shadow
    private boolean displayListEntitiesDirty;

    @Unique
    private boolean vulkanmod112$cameraMovedThisFrame;
    @Unique
    private double vulkanmod112$prevX = Double.MIN_VALUE;
    @Unique
    private double vulkanmod112$prevY = Double.MIN_VALUE;
    @Unique
    private double vulkanmod112$prevZ = Double.MIN_VALUE;
    @Unique
    private double vulkanmod112$prevPitch = Double.MIN_VALUE;
    @Unique
    private double vulkanmod112$prevYaw = Double.MIN_VALUE;

    /**
     * Runs before vanilla overwrites its own record of where the camera was,
     * which is the only moment this comparison can still be made.
     */
    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void vulkanmod112$beginFrame(Entity viewEntity, double partialTicks, ICamera camera,
                                         int frameCount, boolean playerSpectator, CallbackInfo ci) {
        VanillaFrame.countWalkFrame();
        vulkanmod112$cameraMovedThisFrame = viewEntity.posX != vulkanmod112$prevX
                || viewEntity.posY != vulkanmod112$prevY
                || viewEntity.posZ != vulkanmod112$prevZ
                || (double) viewEntity.rotationPitch != vulkanmod112$prevPitch
                || (double) viewEntity.rotationYaw != vulkanmod112$prevYaw;
        vulkanmod112$prevX = viewEntity.posX;
        vulkanmod112$prevY = viewEntity.posY;
        vulkanmod112$prevZ = viewEntity.posZ;
        vulkanmod112$prevPitch = viewEntity.rotationPitch;
        vulkanmod112$prevYaw = viewEntity.rotationYaw;

    }

    /**
     * Vanilla clears the flag at the top of the flood fill, so this fires
     * exactly when a walk begins — whatever triggered it.
     */
    @Redirect(method = "setupTerrain",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;displayListEntitiesDirty:Z",
                    opcode = Opcodes.PUTFIELD, ordinal = 1))
    private void vulkanmod112$markWalkRan(RenderGlobal self, boolean value) {
        vulkanmod112$noteWalkRan();
        displayListEntitiesDirty = value;
    }

    /**
     * The same stamp, for a walk that did not come from vanilla's own code.
     * The replacement search never executes the instruction above.
     */
    @Override
    public void vulkanmod112$noteWalkRan() {
        VanillaFrame.countWalkRan();
    }

    /** The rearm for any visible chunk that still needs rebuilding. */
    @Redirect(method = "setupTerrain",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;displayListEntitiesDirty:Z",
                    opcode = Opcodes.PUTFIELD, ordinal = 2))
    private void vulkanmod112$armFromPendingRebuild(RenderGlobal self, boolean value) {
        vulkanmod112$setDirty(value);
    }

    /** The one write in {@code updateChunks}: chunks finished uploading. */
    @Redirect(method = "updateChunks",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;displayListEntitiesDirty:Z",
                    opcode = Opcodes.PUTFIELD, ordinal = 0))
    private void vulkanmod112$armFromUpload(RenderGlobal self, boolean value) {
        vulkanmod112$setDirty(value);
    }

    /**
     * Passes the flag through, and counts what asked for it.
     *
     * There used to be a rate limiter here — arming the walk was held back
     * while the camera stood still until a timer elapsed. It is gone, and not
     * because it was broken: the project measured it and closed it. Of ten
     * thousand requests a second, nine and a half thousand come from the camera
     * moving rather than from a chunk finishing, and the limiter deliberately
     * never touched those. There is no value of the interval where it would
     * have helped more than the seed cache already does, so the setting had no
     * position worth choosing and stood at zero, which made every branch below
     * it unreachable. A hundred and eighty seven lines that read as a working
     * mechanism and did nothing — and that had already broken once, subtly and
     * silently, by suppressing an arming it then never restored.
     *
     * The counting stays, and is the whole reason this class is still here.
     * "The walk ran on 1882 frames of 2316, from 136399 requests, of which
     * 102198 were the camera moving" is the sentence that told us where the
     * frame went, and no other place in the game can say it.
     */
    @Unique
    private void vulkanmod112$setDirty(boolean value) {
        VanillaFrame.countWalk(value, vulkanmod112$cameraMovedThisFrame, false);
        displayListEntitiesDirty = value;
    }
}
