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
public abstract class OwnVisibilityWalkMixin {

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
        if (!vulkanmod112$run(self, viewEntity, partialTicks, camera, playerSpectator)) {
            VanillaFrame.countOwnWalkFallback();
            return true;
        }
        VanillaFrame.countOwnWalk(vulkanmod112$walk.tested(), vulkanmod112$visible.size(),
                System.nanoTime() - started);

        // Vanilla clears the flag as the first statement of the block being
        // skipped, and the throttle in VisibilityWalkMixin learns from that
        // instruction that a walk happened. Neither runs now, so both are done
        // here.
        displayListEntitiesDirty = false;
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
