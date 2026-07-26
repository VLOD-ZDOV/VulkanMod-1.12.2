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

    /** Leave the queue alone above this; visible chunks have first claim. */
    private static final int QUEUE_TARGET = 32;
    /** Grid entries examined per frame. */
    private static final int SCAN_PER_FRAME = 4096;

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
        int room = QUEUE_TARGET - chunksToUpdate.size();
        if (room <= 0) {
            return;
        }
        int cursor = vulkanmod112$scanCursor;
        if (cursor >= grid.length) {
            cursor = 0;
        }
        int scanned = 0;
        while (scanned < SCAN_PER_FRAME && room > 0) {
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
