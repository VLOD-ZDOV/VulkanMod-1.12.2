package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.vulkanmod112.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Lets chunks outside the view be rebuilt, which vanilla never does.
 *
 * {@code setupTerrain} flood-fills outward from the player's chunk, and the
 * expansion step ANDs in {@code camera.isBoundingBoxInFrustum(...)}. A chunk
 * that fails that test never enters {@code renderInfos}, and the loop that
 * schedules rebuilds only walks {@code renderInfos} — so a chunk needing a
 * rebuild that is not currently on screen is never even considered, no matter
 * how idle the builder threads are. Turn the camera and it is discovered then,
 * from scratch.
 *
 * At normal render distances nobody notices. At 64 the number of chunks
 * wanting a rebuild dwarfs what fits in a frame, so the world visibly fills in
 * along whatever you are looking at, and narrowing the field of view (the zoom
 * key) makes distant chunks appear because it shrinks the competition.
 *
 * This tops the build queue up from the full chunk grid once the visible work
 * is dealt with. Two rules keep it from making things worse:
 *
 * - it only adds when the queue is nearly empty, so on-screen chunks always
 *   win the builder threads;
 * - it scans a bounded slice of the grid per frame, resuming where it left
 *   off, because the grid is 129x129x16 at distance 64 and sweeping it every
 *   frame would trade slow loading for a stutter.
 *
 * Only the build queue is touched. What actually gets drawn is still decided
 * by {@code renderInfos}, so this cannot put off-screen geometry on screen.
 */
@Mixin(RenderGlobal.class)
public abstract class ChunkPreloadMixin {

    /*
     * Both of these were constants here, which meant the only way to match them
     * to a machine was to rebuild the mod. They decide the whole shape of the
     * trade: a processor with room to spare would rather fill the world faster
     * than hold a frame rate on a scene that is not moving, and one without it
     * would rather not be asked. The defaults are what they were.
     */

    /** Leave the queue alone above this; visible chunks have first claim. */
    private static int vulkanmod112$queueTarget() {
        return VulkanConfig.getPreloadQueue();
    }

    /** Grid entries examined per frame. */
    private static int vulkanmod112$scanPerFrame() {
        return VulkanConfig.getPreloadScan();
    }

    @Shadow
    private ViewFrustum viewFrustum;

    @Shadow
    private Set<RenderChunk> chunksToUpdate;

    /** Resume point, so successive frames sweep the whole grid. */
    private int vulkanmod112$scanCursor;

    @Inject(method = "setupTerrain", at = @At("RETURN"))
    private void vulkanmod112$preloadOffscreenChunks(Entity viewEntity, double partialTicks,
                                                     ICamera camera, int frameCount,
                                                     boolean playerSpectator, CallbackInfo ci) {
        if (!VulkanConfig.isChunkPreloadEnabled() || viewFrustum == null) {
            return;
        }
        RenderChunk[] grid = viewFrustum.renderChunks;
        if (grid == null || grid.length == 0) {
            return;
        }
        // Only when the queue has run dry, not merely when it is short. Topping
        // it up while work remains would keep vanilla's chunk builder busy
        // every frame forever instead of only while there is catching up to do
        // — and on a CPU that is already the bottleneck, that is a cost paid on
        // every frame in exchange for chunks nobody is looking at yet.
        if (!chunksToUpdate.isEmpty()) {
            return;
        }
        int room = vulkanmod112$queueTarget();
        int cursor = vulkanmod112$scanCursor;
        if (cursor >= grid.length) {
            cursor = 0;
        }
        int scanned = 0;
        int scanBudget = vulkanmod112$scanPerFrame();
        while (scanned < scanBudget && room > 0) {
            RenderChunk chunk = grid[cursor];
            cursor++;
            scanned++;
            if (cursor >= grid.length) {
                cursor = 0;
            }
            if (chunk != null && chunk.needsUpdate() && chunksToUpdate.add(chunk)) {
                room--;
            }
        }
        vulkanmod112$scanCursor = cursor;
    }
}
