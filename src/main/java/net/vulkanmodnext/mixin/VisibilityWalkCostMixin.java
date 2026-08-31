package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.vulkanmodnext.client.SeedFacings;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.EnumSet;
import java.util.Set;

/**
 * Takes two allocations out of the per-frame visibility walk without changing
 * what it computes.
 *
 * The walk costs 25% to 48% of the frame at render distance 64 — measured with
 * the game's own profiler, and eight times what drawing the world costs. An
 * attempt to run it less often failed on the data: 9,794 of every 10,100
 * requests to redo it come from the camera moving, which legitimately changes
 * what is visible, so there is nothing to skip. What is left is to make the run
 * itself cheaper, and two items stand out in the source.
 *
 * <b>The seed rescan.</b> {@code getVisibleFacings} builds a fresh
 * {@code VisGraph} and reads all 4,096 block states of the camera's own chunk
 * section, every walk, only to find which faces are reachable from the block
 * the eye is in:
 *
 * <pre>
 * VisGraph visgraph = new VisGraph();
 * for (BlockPos.MutableBlockPos p : BlockPos.getAllInBoxMutable(blockpos, blockpos.add(15, 15, 15))) {
 *     if (chunk.getBlockState(p).isOpaqueCube()) visgraph.setOpaqueCube(p);
 * }
 * return visgraph.getVisibleFacings(pos);
 * </pre>
 *
 * At a hundred walks a second that is 400,000 block-state reads a second for an
 * answer that only changes when the camera moves to a different block or that
 * section's contents change. Both are cheap to detect exactly: the block
 * position, and the identity of the section's {@code CompiledChunk}, which the
 * game replaces whenever the chunk is rebuilt — and a block change is precisely
 * what schedules a rebuild. This is memoisation with an exact key, not an
 * approximation, and it is stale only for the moment between a block changing
 * and its chunk finishing the rebuild, during which the drawn geometry is
 * equally out of date.
 *
 * <b>The array clone.</b> {@code EnumFacing.values()} allocates a fresh
 * six-element array on every popped node, thousands of times per walk.
 * {@code EnumFacing.VALUES} is the same contents as a shared constant — the
 * class exposes it for exactly this reason. Nothing in the loop writes to it.
 */
@Mixin(RenderGlobal.class)
public abstract class VisibilityWalkCostMixin implements SeedFacings {

    @Shadow
    private ViewFrustum viewFrustum;

    @Unique
    private long vulkanmodnext$seedPos = Long.MIN_VALUE;
    @Unique
    private CompiledChunk vulkanmodnext$seedCompiled;
    @Unique
    private Set<EnumFacing> vulkanmodnext$seedFacings;

    /**
     * Vanilla's own loop variable, unchanged in contents. Redirecting the call
     * rather than the loop keeps the traversal itself vanilla, which is the
     * whole point: the special cases around being sealed inside opaque blocks
     * and around spectator mode are easy to get wrong when rewritten and hard
     * to notice when broken.
     *
     * <h2>Why this one is allowed to find nothing</h2>
     *
     * Because there is nothing to do when it does. This replaces a call that
     * copies an array with a read of the shared one, and the only way the call
     * can be missing is that somebody already replaced it — this exact swap is
     * a stock optimisation, and at least one widely used coremod performs it on
     * every class it touches, announcing itself in the log as
     * {@code Transforming EnumFacing::values() to EnumFacing::VALUES}. That
     * runs before mixins do, so by the time this is applied the array copy is
     * gone and this redirect has no call site left to redirect.
     *
     * Required, that reads as a crash. Not merely a crash in this class: a
     * mixin that fails takes its target class down with it, so the game then
     * reports {@code NoClassDefFoundError} on {@code RenderGlobal} — a vanilla
     * class, naming neither this mod nor the reason. A pack lost its whole
     * launch to an optimisation that had already been applied for us.
     *
     * So: not required. If the call is absent, what this wanted is already
     * true. Mixin still logs one line saying it found nothing, which is the
     * right amount of noise for "somebody beat us to it".
     *
     * This reasoning does not extend to the other injections here. They change
     * what the walk computes rather than what it allocates, and one of those
     * missing is a real fault that should be loud.
     */
    @Redirect(method = "setupTerrain", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/EnumFacing;values()[Lnet/minecraft/util/EnumFacing;"))
    private EnumFacing[] vulkanmodnext$sharedFacings() {
        return EnumFacing.VALUES;
    }

    @Redirect(method = "setupTerrain",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;getVisibleFacings"
                            + "(Lnet/minecraft/util/math/BlockPos;)Ljava/util/Set;"))
    private Set<EnumFacing> vulkanmodnext$cachedVisibleFacings(RenderGlobal self, BlockPos pos) {
        return vulkanmodnext$visibleFacings(pos);
    }

    /**
     * Also reached directly by the replacement search, which never executes
     * vanilla's call site.
     *
     * <b>What comes back is always a fresh set.</b> The caller removes an
     * element from it — the face opposite the one being looked at, when only
     * one face is reachable — and the first version of this cache handed out
     * the set it had stored. Turning the camera while standing in a spot with a
     * single reachable face would then have emptied the cached answer, and an
     * empty answer means "sealed in", which draws the camera's own chunk and
     * nothing else.
     */
    @Override
    public Set<EnumFacing> vulkanmodnext$visibleFacings(BlockPos pos) {
        RenderGlobal self = (RenderGlobal) (Object) this;
        if (!VulkanConfig.isVisibilitySeedCacheEnabled()) {
            return vulkanmodnext$callVanilla(self, pos);
        }
        RenderChunk chunk = viewFrustum == null ? null
                : ((ViewFrustumAccessor) viewFrustum).vulkanmodnext$getRenderChunk(pos);
        CompiledChunk compiled = chunk == null ? null : chunk.getCompiledChunk();
        long key = pos.toLong();
        if (vulkanmodnext$seedFacings != null
                && key == vulkanmodnext$seedPos
                && compiled == vulkanmodnext$seedCompiled) {
            return EnumSet.copyOf(vulkanmodnext$seedFacings);
        }
        Set<EnumFacing> facings = vulkanmodnext$callVanilla(self, pos);
        // Only cache when there is a compiled chunk to key on. Without one there
        // is no signal for when the answer goes stale, and a wrong seed hides
        // parts of the world.
        if (compiled != null && !facings.isEmpty()) {
            vulkanmodnext$seedPos = key;
            vulkanmodnext$seedCompiled = compiled;
            vulkanmodnext$seedFacings = EnumSet.copyOf(facings);
        } else {
            vulkanmodnext$seedFacings = null;
        }
        return facings;
    }

    @Shadow
    private Set<EnumFacing> getVisibleFacings(BlockPos pos) {
        throw new AssertionError();
    }

    @Unique
    private Set<EnumFacing> vulkanmodnext$callVanilla(RenderGlobal self, BlockPos pos) {
        return getVisibleFacings(pos);
    }
}
