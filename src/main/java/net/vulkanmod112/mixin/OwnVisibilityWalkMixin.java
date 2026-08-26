package net.vulkanmod112.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.util.vector.Vector3f;
import net.vulkanmod112.client.RenderInfo;
import net.vulkanmod112.client.SectionIndex;
import net.vulkanmod112.client.SeedFacings;
import net.vulkanmod112.client.VanillaFrame;
import net.vulkanmod112.client.VisibilityWalk;
import net.vulkanmod112.client.VulkanConfig;
import net.vulkanmod112.client.WalkTimer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Replaces the flood fill that decides which chunks are on screen.
 *
 * The search itself lives in {@link VisibilityWalk}, which explains why it is
 * shaped the way it is. This class is only the join: it decides whether the
 * replacement can be used at all, reproduces vanilla's seeding, and hands the
 * frame back untouched whenever the answer is anything but a plain yes.
 *
 * <h2>Where it interposes</h2>
 *
 * Vanilla guards the whole search with
 * {@code if (!flag && this.displayListEntitiesDirty)}. Redirecting that one
 * field read is the smallest cut that removes exactly the search and nothing
 * else — returning false there skips vanilla's block, and everything after it
 * (refilling the rebuild queue, the frustum capture, the near-chunk rebuild)
 * runs unchanged over the list this produced. Overwriting {@code setupTerrain}
 * outright, which is the usual approach, would take those with it.
 *
 * <h2>What is handed back to vanilla</h2>
 *
 * Each of these leaves the frame entirely to the game rather than approximating
 * it, and each is counted, so a report says how often the replacement was not
 * the thing being measured:
 *
 * <ul>
 * <li>The camera above or below the world. Vanilla seeds that case from the
 *     whole grid ring using boxes stretched to infinity, and an infinite
 *     coordinate against a plane normal of exactly zero is NaN — which vanilla's
 *     chain of comparisons treats as "not rejected" in a way no rewrite
 *     reproduces by accident.</li>
 * <li>A camera that is not the game's own {@code Frustum}.</li>
 * <li>Running out of records mid-walk, which means the record type could not be
 *     reached by reflection at all.</li>
 * </ul>
 *
 * <h2>The two cases that are implemented, because they are easy to lose</h2>
 *
 * <ul>
 * <li><b>Sealed in.</b> With no face reachable from the block the eye is in,
 *     vanilla draws the camera's own chunk and stops. Losing this draws nothing
 *     at all when standing inside a solid block, which no ordinary test finds.</li>
 * <li><b>Spectator inside an opaque block.</b> There vanilla turns its own
 *     direction culling off for the frame, otherwise a spectator flying through
 *     stone would see the world cut away.</li>
 * </ul>
 */
@Mixin(RenderGlobal.class)
public abstract class OwnVisibilityWalkMixin implements SectionIndex {

    @Shadow
    private ViewFrustum viewFrustum;
    @Shadow
    private int renderDistanceChunks;
    @Shadow
    private boolean displayListEntitiesDirty;
    @Shadow
    private WorldClient world;
    @Shadow
    @SuppressWarnings("rawtypes")
    private List renderInfos;

    @Shadow
    protected abstract Vector3f getViewVector(Entity entityIn, double partialTicks);

    /**
     * Reused between walks. Vanilla allocates a fresh list every time; the
     * records in it belong to a pool that is reset at the same moment this is
     * cleared, which is the start of a walk and not the end of one — until then
     * the game is still reading last frame's list.
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private final List vulkanmod112$visible = new ArrayList(8192);

    @Unique
    private final VisibilityWalk vulkanmod112$walk = new VisibilityWalk();

    /**
     * Handed out only while the list the game holds is the one this produced.
     * The search hands whole frames back to vanilla — a camera outside the
     * world's height, a debug frustum, a record type it could not reach — and
     * on those frames the index describes a list nobody is reading.
     */
    @Override
    public VisibilityWalk vulkanmod112$sectionWalk() {
        return vulkanmod112$walk;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List vulkanmod112$visibleList() {
        return vulkanmod112$visible;
    }

    /**
     * When the last walk finished, and the block the camera was standing in.
     *
     * The walk is armed by two different things wearing the same flag. One is
     * the camera leaving the neighbourhood it was in, and that has to be
     * answered at once: the list is about what is on screen, and it is wrong
     * the moment the screen changes. The other is a chunk finishing its build,
     * and that one has no such claim — the chunk was not on screen a moment ago
     * and nobody can tell whether it arrives this frame or three frames later.
     *
     * Flying at thirty-two chunks, the second kind arrives by the million:
     * measured on a fixed route, 1 201 010 arm requests over one interval, all
     * of them from chunks finishing, and the walk ran on 1762 frames out of
     * 1762 at about 1.4 ms each. That is a third of the render thread spent
     * rebuilding an answer that changed by one chunk. Standing still on the
     * same route the walk runs about ten times in three thousand frames, which
     * is why this only ever showed up in flight.
     */
    @Unique
    private long vulkanmod112$lastWalkNanos;
    @Unique
    private int vulkanmod112$lastWalkX = Integer.MIN_VALUE;
    @Unique
    private int vulkanmod112$lastWalkY = Integer.MIN_VALUE;
    @Unique
    private int vulkanmod112$lastWalkZ = Integer.MIN_VALUE;

    /**
     * The longest a chunk may wait to appear, in nanoseconds.
     *
     * One client tick. A chunk that has just finished building has already
     * waited far longer than this to be built at all, and the walk it is asking
     * for costs more than the chunk did.
     */
    @Unique
    private static final long VULKANMOD112$CHURN_INTERVAL = 50L * 1_000_000L;

    @Redirect(method = "setupTerrain",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;displayListEntitiesDirty:Z",
                    opcode = Opcodes.GETFIELD, ordinal = 1))
    private boolean vulkanmod112$ownWalk(RenderGlobal self, Entity viewEntity, double partialTicks,
                                         ICamera camera, int frameCount, boolean playerSpectator) {
        boolean dirty = displayListEntitiesDirty;
        if (!dirty || !VulkanConfig.isOwnVisibilityWalk()) {
            return dirty;
        }

        long started = System.nanoTime();
        // Answer the camera at once and the chunks at a tick's pace. The flag
        // is deliberately left set when a walk is held back, so the next frame
        // asks again rather than the request being lost.
        int cameraX = MathHelper.floor(viewEntity.posX);
        int cameraY = MathHelper.floor(viewEntity.posY);
        int cameraZ = MathHelper.floor(viewEntity.posZ);
        boolean cameraMoved = cameraX != vulkanmod112$lastWalkX
                || cameraY != vulkanmod112$lastWalkY
                || cameraZ != vulkanmod112$lastWalkZ;
        if (!cameraMoved && started - vulkanmod112$lastWalkNanos < VULKANMOD112$CHURN_INTERVAL) {
            VanillaFrame.countOwnWalkHeld();
            return false;
        }
        if (!vulkanmod112$run(self, viewEntity, partialTicks, camera, playerSpectator)) {
            VanillaFrame.countOwnWalkFallback();
            return true;
        }
        VanillaFrame.countOwnWalk(vulkanmod112$walk.tested(), vulkanmod112$visible.size(),
                System.nanoTime() - started);

        // Vanilla clears the flag as the first statement of the block being
        // skipped, and the counter in VisibilityWalkMixin learns from that
        // instruction that a walk happened. Neither runs now, so both are done
        // here.
        displayListEntitiesDirty = false;
        vulkanmod112$lastWalkNanos = System.nanoTime();
        vulkanmod112$lastWalkX = cameraX;
        vulkanmod112$lastWalkY = cameraY;
        vulkanmod112$lastWalkZ = cameraZ;
        ((WalkTimer) self).vulkanmod112$noteWalkRan();
        return false;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private boolean vulkanmod112$run(RenderGlobal self, Entity viewEntity, double partialTicks,
                                     ICamera camera, boolean playerSpectator) {
        if (!(camera instanceof Frustum) || viewFrustum == null || world == null) {
            return false;
        }
        RenderChunk[] chunks = viewFrustum.renderChunks;
        if (chunks == null) {
            return false;
        }

        double x = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * partialTicks;
        double y = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * partialTicks;
        double z = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * partialTicks;
        BlockPos eye = new BlockPos(x, y + viewEntity.getEyeHeight(), z);

        ViewFrustumAccessor grid = (ViewFrustumAccessor) viewFrustum;
        RenderChunk start = grid.vulkanmod112$getRenderChunk(eye);
        if (start == null) {
            // The camera is outside the world's height. Vanilla's seeding for
            // that case is a different algorithm, so it keeps the frame.
            return false;
        }
        if (!vulkanmod112$walk.begin(grid.vulkanmod112$countX(), grid.vulkanmod112$countY(),
                grid.vulkanmod112$countZ(), chunks.length)) {
            return false;
        }

        Entity.setRenderDistanceWeight(MathHelper.clamp(
                (double) Minecraft.getMinecraft().gameSettings.renderDistanceChunks / 8.0D, 1.0D, 2.5D));
        boolean many = Minecraft.getMinecraft().renderChunksMany;

        vulkanmod112$visible.clear();
        renderInfos = vulkanmod112$visible;

        Set<EnumFacing> reachable = ((SeedFacings) self).vulkanmod112$visibleFacings(eye);
        if (reachable.size() == 1) {
            Vector3f view = getViewVector(viewEntity, partialTicks);
            reachable.remove(EnumFacing.getFacingFromVector(view.x, view.y, view.z).getOpposite());
        }

        if (reachable.isEmpty() && !playerSpectator) {
            RenderInfo lone = vulkanmod112$walk.lone(self, start);
            if (lone == null) {
                return false;
            }
            vulkanmod112$visible.add(lone);
            return true;
        }

        if (playerSpectator && world.getBlockState(eye).isOpaqueCube()) {
            many = false;
        }

        BlockPos origin = start.getPosition();
        int slot = vulkanmod112$walk.slotOf(origin.getX(), origin.getY(), origin.getZ());
        if (slot < 0 || vulkanmod112$walk.seed(self, start, slot,
                origin.getX(), origin.getY(), origin.getZ()) == null) {
            return false;
        }

        // Vanilla measures the range from the player's chunk-aligned position,
        // not from the eye, and the difference decides a whole ring of chunks.
        int playerX = MathHelper.floor(x / 16.0D) * 16;
        int playerZ = MathHelper.floor(z / 16.0D) * 16;
        return vulkanmod112$walk.iterate(self, chunks, (Frustum) camera, vulkanmod112$visible,
                playerX, playerZ, renderDistanceChunks, many);
    }
}
