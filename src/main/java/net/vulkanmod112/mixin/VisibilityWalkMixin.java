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
 * Stops the visibility walk from being redone for a world that is merely still
 * filling in.
 *
 * The walk is the flood fill in {@code RenderGlobal.setupTerrain} that decides
 * which chunks are on screen. The game's own profiler puts it at 25% to 48% of
 * the frame at render distance 64 — the largest single item, some eight times
 * what drawing the world costs — and it reruns whenever
 * {@code displayListEntitiesDirty} is set.
 *
 * The obvious-looking trigger is the {@code !chunksToUpdate.isEmpty()} term of
 * the condition, and that is where this first interposed. A counter said the
 * queue was empty every single time, and then that the redirect was not reached
 * at all: the flag was already true, so the condition short-circuited past it.
 * The two writes that actually set it both run *after* the walk, later in the
 * same frame:
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
 * upload, buys a full walk on the next frame — at render distance 64, every
 * frame for as long as the world is filling in.
 *
 * Those two writes are rate-limited here. Three things make that safe, and each
 * one is here because leaving it out was a real defect:
 *
 * - <b>A suppressed write is deferred, never dropped.</b> It is re-applied at
 *   the top of the next frame once the interval has passed. Simply skipping it
 *   meant that if the last arming write of a filling world landed inside a
 *   suppression window and the camera then never moved, the flag stayed false
 *   forever and those chunks were never drawn at all.
 * - <b>Camera movement is judged against our own record of the previous
 *   frame.</b> Vanilla overwrites its {@code lastViewEntity*} fields near the
 *   top of {@code setupTerrain}, before the write we redirect, so comparing
 *   against those compares the frame to itself and never sees movement.
 * - <b>The timer marks when a walk ran, not when a write was allowed.</b> Walks
 *   also come from camera movement, from {@code loadRenderers}, and from this
 *   mod's own zoom; without stamping those, the next suppressible write always
 *   measured a stale interval and sailed through.
 *
 * The cost, plainly: a chunk that finishes building can wait up to the
 * configured interval before it is drawn. Off by default — this changes the
 * game's logic, not this mod's renderer.
 */
@Mixin(RenderGlobal.class)
public abstract class VisibilityWalkMixin implements WalkTimer {

    @Shadow
    private boolean displayListEntitiesDirty;

    /** When the flood fill last ran, however it was triggered. */
    @Unique
    private long vulkanmod112$lastWalkNanos;
    /** A write we held back, owed to the game as soon as the interval passes. */
    @Unique
    private boolean vulkanmod112$armDeferred;
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
     * which is the only moment this comparison can still be made, and pays back
     * anything the throttle is holding.
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

        if (vulkanmod112$armDeferred && vulkanmod112$intervalElapsed()) {
            vulkanmod112$armDeferred = false;
            displayListEntitiesDirty = true;
            VanillaFrame.countDeferredWalk();
        }
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
        vulkanmod112$lastWalkNanos = System.nanoTime();
        vulkanmod112$armDeferred = false;
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

    @Unique
    private boolean vulkanmod112$intervalElapsed() {
        int intervalMs = VulkanConfig.getVisibilityWalkInterval();
        return intervalMs <= 0
                || System.nanoTime() - vulkanmod112$lastWalkNanos >= intervalMs * 1_000_000L;
    }

    /**
     * Clearing the flag always goes straight through; only arming it is
     * rate-limited, and only when the camera held still.
     */
    @Unique
    private void vulkanmod112$setDirty(boolean value) {
        boolean throttled = value
                && !vulkanmod112$cameraMovedThisFrame
                && VulkanConfig.getVisibilityWalkInterval() > 0
                && !vulkanmod112$intervalElapsed();
        VanillaFrame.countWalk(value, vulkanmod112$cameraMovedThisFrame, throttled);
        if (throttled) {
            vulkanmod112$armDeferred = true;
            return;
        }
        displayListEntitiesDirty = value;
    }
}
