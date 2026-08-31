package net.vulkanmodnext.mixin;

import com.google.common.collect.Sets;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import net.vulkanmodnext.client.DirtyChunks;
import net.vulkanmodnext.client.GridSlot;
import net.vulkanmodnext.client.RenderInfo;
import net.vulkanmodnext.client.VanillaFrame;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cuts the last loop of {@code setupTerrain} down to the chunks it can act on.
 *
 * <h2>What the loop does, and what it costs</h2>
 *
 * After the visibility search has produced the visible list, vanilla walks all
 * of it again to find the chunks that need rebuilding:
 *
 * <pre>
 *   Set&lt;RenderChunk&gt; pending = this.chunksToUpdate;
 *   this.chunksToUpdate = new LinkedHashSet&lt;&gt;();
 *   for (info : this.renderInfos)
 *       if (info.renderChunk.needsUpdate() || pending.contains(info.renderChunk))
 *           ... queue it, or build it here and now if it is close
 *   this.chunksToUpdate.addAll(pending);
 * </pre>
 *
 * The game's own profiler puts that at 19.1% of the frame at render distance
 * 64. It runs every frame, including the frames the search itself is skipped,
 * and the visible list is around 8 600 entries. Almost none of them qualify:
 * this is a scan of the whole world on screen to find the handful of chunks a
 * block was broken in.
 *
 * The expensive part is not the test but where it reads from.
 * {@code needsUpdate()} is one boolean inside a {@code RenderChunk}, and those
 * are scattered over hundreds of megabytes of heap, so collecting 8 600 bits
 * costs 8 600 trips to memory. {@code contains} then hashes into a set that is
 * somewhere else again.
 *
 * <h2>What this does instead</h2>
 *
 * Both answers are moved into flat bitsets indexed by grid slot — see
 * {@link DirtyChunks} — and the visible list already carries each chunk's slot,
 * because the visibility search knew it while stepping there. The filter is
 * then a bit test per entry against 33 KiB of first-level cache, and the chunk
 * objects are touched only for the chunks that qualify.
 *
 * <h2>Why it filters rather than replaces</h2>
 *
 * Vanilla's loop body decides between queueing a chunk and rebuilding it on the
 * spot, and that decision involves Forge's off-thread flag, an immediate-update
 * flag and a distance. Rewriting it would mean reproducing all of that. Instead
 * the game is handed an iterator over the chunks that pass the filter, and runs
 * its own body over them unchanged, in the same order, re-testing the condition
 * it always tested. Everything this can get wrong is therefore visible as
 * chunks missing from the shortlist, and the guard below makes that a one-sided
 * error: anything not known to be clean is included.
 */
@Mixin(RenderGlobal.class)
public abstract class RebuildNearMixin {

    @Shadow
    @SuppressWarnings("rawtypes")
    private List renderInfos;

    @Shadow
    private Set<RenderChunk> chunksToUpdate;

    /**
     * The rebuild queue as it stood when this frame's loop began. Vanilla keeps
     * it in a local, which a redirect cannot see, so it is caught at the one
     * instruction that separates the old queue from the new.
     */
    @Unique
    private Set<RenderChunk> vulkanmodnext$pending;

    @Unique
    private long vulkanmodnext$startedNanos;

    /** Reused between frames; holds only the chunks the filter kept. */
    @Unique
    @SuppressWarnings("rawtypes")
    private final List vulkanmodnext$shortlist = new ArrayList(256);

    /**
     * The first instruction of the rebuild pass: vanilla swaps in a fresh queue
     * here, so this is the last moment the old one can be read, and the first
     * moment worth timing from.
     */
    @Redirect(method = "setupTerrain",
            at = @At(value = "INVOKE",
                    target = "Lcom/google/common/collect/Sets;newLinkedHashSet()Ljava/util/LinkedHashSet;"))
    private LinkedHashSet<RenderChunk> vulkanmodnext$capturePending() {
        this.vulkanmodnext$pending = this.chunksToUpdate;
        this.vulkanmodnext$startedNanos = System.nanoTime();
        return Sets.newLinkedHashSet();
    }

    @Inject(method = "setupTerrain", at = @At("RETURN"))
    private void vulkanmodnext$endTiming(CallbackInfo ci) {
        if (this.vulkanmodnext$startedNanos != 0L) {
            VanillaFrame.countRebuildNear(System.nanoTime() - this.vulkanmodnext$startedNanos);
            this.vulkanmodnext$startedNanos = 0L;
        }
    }

    /**
     * The only {@code List.iterator()} in {@code setupTerrain} is the rebuild
     * loop's. It is checked against the field at run time all the same, so that
     * a future change which adds another one degrades to vanilla behaviour
     * instead of handing the wrong list to the wrong loop.
     */
    @Redirect(method = "setupTerrain",
            at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;",
                    ordinal = 0))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Iterator vulkanmodnext$shortlistRebuilds(List list) {
        int size = list == this.renderInfos ? list.size() : 0;
        // Counted on both sides of the switch and before any of the bail-outs.
        // Without it the arm with the filter off has no denominator, and two
        // flights over different ground cannot be compared at all — which is
        // exactly what happened the first time this was measured.
        VanillaFrame.countRebuildScan(size, this.vulkanmodnext$pending == null
                ? 0 : this.vulkanmodnext$pending.size());
        if (CHECK_ORDER && size > 1) {
            vulkanmodnext$checkOrder(list, size);
        }

        if (list != this.renderInfos || !VulkanConfig.isFastRebuildNear() || !DirtyChunks.ready()) {
            return list.iterator();
        }
        if (size == 0) {
            return list.iterator();
        }
        Object first = list.get(0);
        // Records the game allocated itself carry no slot. That is every frame
        // our own search handed back, and the whole list on frames it never ran.
        if (!(first instanceof RenderInfo) || ((RenderInfo) first).vulkanmodnext$gridSlot() < 0) {
            return list.iterator();
        }
        Set<RenderChunk> pending = this.vulkanmodnext$pending;
        int pendingSize = pending == null ? 0 : pending.size();
        // The queue is normally a handful of chunks against thousands on
        // screen, which is what makes marking it cheaper than hashing against
        // it. While a world is loading it can grow past that, and then vanilla's
        // way round is the better one.
        if (pendingSize > size) {
            return list.iterator();
        }

        long started = System.nanoTime();
        if (pendingSize > 0) {
            for (RenderChunk chunk : pending) {
                DirtyChunks.markPending(((GridSlot) chunk).vulkanmodnext$gridSlot());
            }
        }

        List shortlist = this.vulkanmodnext$shortlist;
        shortlist.clear();
        for (int i = 0; i < size; i++) {
            Object info = list.get(i);
            if (DirtyChunks.dirtyOrPending(((RenderInfo) info).vulkanmodnext$gridSlot())) {
                shortlist.add(info);
            }
        }

        if (pendingSize > 0) {
            for (RenderChunk chunk : pending) {
                DirtyChunks.clearPending(((GridSlot) chunk).vulkanmodnext$gridSlot());
            }
        }
        VanillaFrame.countRebuildFilter(shortlist.size(), System.nanoTime() - started);
        return shortlist.iterator();
    }

    /**
     * Answers roadmap item B2 with a number: how often the visible list steps
     * away from the camera and then back towards it. Opt-in, because it costs a
     * dereference per chunk.
     */
    @Unique
    private static final boolean CHECK_ORDER = Boolean.getBoolean("vulkanmodnext.checkOrder");

    /**
     * The list is breadth-first from the camera's chunk, so it is ordered by
     * graph distance by construction. This measures the gap between that and
     * Euclidean distance, which is what an early-depth pass actually wants: a
     * chunk reached the long way round a wall lands later than its distance
     * deserves, and each such pair is counted here. The origin is the first
     * entry, which is the chunk the search was seeded from.
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private void vulkanmodnext$checkOrder(List list, int size) {
        BlockPos origin = ((RenderInfo) list.get(0)).vulkanmodnext$chunk().getPosition();
        long previous = -1L;
        int inversions = 0;
        int pairs = 0;
        for (int i = 0; i < size; i++) {
            BlockPos at = ((RenderInfo) list.get(i)).vulkanmodnext$chunk().getPosition();
            long dx = at.getX() - origin.getX();
            long dy = at.getY() - origin.getY();
            long dz = at.getZ() - origin.getZ();
            long distance = dx * dx + dy * dy + dz * dz;
            if (previous >= 0L) {
                pairs++;
                if (distance < previous) {
                    inversions++;
                }
            }
            previous = distance;
        }
        VanillaFrame.countListOrder(pairs, inversions);
    }

    /**
     * Scratch for the one position the rebuild pass builds per chunk. Only ever
     * touched on the render thread, inside a single statement, and never stored.
     */
    @Unique
    private final BlockPos.MutableBlockPos vulkanmodnext$nearCentre = new BlockPos.MutableBlockPos();

    /**
     * The centre of a chunk, without allocating one object per chunk to hold it.
     *
     * Vanilla builds it as {@code renderChunk.getPosition().add(8, 8, 8)} and
     * uses it on the very next line, for one distance comparison, and never
     * again. That is a fresh {@code BlockPos} for every chunk the rebuild pass
     * acts on — and while a world is loading that is around 16 800 a frame, some
     * 1.7 million allocations a second, for a number that is read once and
     * dropped.
     *
     * Worse, with near chunks queued rather than built on the spot the number is
     * not read at all: vanilla computes the position and the distance
     * unconditionally, before the branch that would have used them, and the
     * branch then takes its first arm on the left of an {@code ||}.
     *
     * One reused mutable does instead. It cannot escape — the value lives for
     * two instructions inside a single statement — so this is the same
     * comparison against the same numbers with the allocation removed.
     */
    @Redirect(method = "setupTerrain",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/math/BlockPos;add(III)"
                            + "Lnet/minecraft/util/math/BlockPos;"))
    private BlockPos vulkanmodnext$chunkCentre(BlockPos position, int x, int y, int z) {
        if (!VulkanConfig.isFastRebuildNear()) {
            return position.add(x, y, z);
        }
        return this.vulkanmodnext$nearCentre.setPos(
                position.getX() + x, position.getY() + y, position.getZ() + z);
    }

    /**
     * Whether a chunk that has just changed near the camera is queued for a
     * builder thread rather than built here and now.
     *
     * Vanilla builds it here: any dirty chunk whose centre is within about 28
     * blocks of the eye is rebuilt on the render thread, in the middle of
     * setting the frame up, and the frame waits for it. Measured, that is what
     * the whole of this section costs — 0.4 to 0.7 ms a frame in the windows
     * where it happens, against 0.02 to 0.07 for the scan around it — and it is
     * why breaking a block can be felt rather than only seen.
     *
     * Forge already has a switch for this, so the behaviour is well travelled
     * rather than invented here; this one simply lets it be reached from this
     * mod's screen, and leaves Forge's own setting winning when it is on. The
     * price is that a chunk you just changed is a frame or two behind instead of
     * instant.
     */
    @Redirect(method = "setupTerrain",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraftforge/common/ForgeModContainer;"
                            + "alwaysSetupTerrainOffThread:Z",
                    opcode = org.objectweb.asm.Opcodes.GETSTATIC))
    private boolean vulkanmodnext$buildNearOffThread() {
        return net.minecraftforge.common.ForgeModContainer.alwaysSetupTerrainOffThread
                || VulkanConfig.isBuildNearOffThread();
    }

    /**
     * The synchronous rebuild of a nearby chunk, timed separately.
     *
     * This is the other thing inside the same profiler section, and it is a full
     * chunk build on the thread that has to finish the frame. Without splitting
     * the two, "19% of the frame" cannot be read as either "the scan is
     * expensive" or "the game rebuilt three chunks here", and those want
     * opposite fixes.
     */
    @Redirect(method = "setupTerrain",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher;"
                            + "updateChunkNow(Lnet/minecraft/client/renderer/chunk/RenderChunk;)Z"))
    private boolean vulkanmodnext$timeBuildNear(ChunkRenderDispatcher dispatcher, RenderChunk chunk) {
        long started = System.nanoTime();
        boolean built = dispatcher.updateChunkNow(chunk);
        VanillaFrame.countBuildNear(System.nanoTime() - started);
        return built;
    }
}
