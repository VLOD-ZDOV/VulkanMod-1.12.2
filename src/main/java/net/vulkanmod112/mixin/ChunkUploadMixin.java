package net.vulkanmod112.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import net.vulkanmod112.client.ChunkMirror;
import net.vulkanmod112.client.ChunkSlots;
import net.vulkanmod112.client.VertexBufferSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * Mirrors a chunk on the thread that built it, not on the thread that draws.
 *
 * {@code uploadChunk} is called from the chunk builder threads and, when it is
 * not on the client thread, only queues the work: the actual GL upload happens
 * later inside {@code runChunkUploads}, which the game gives a hard budget of a
 * quarter of the frame minus what the frame already spent. Mirroring used to
 * ride along with that upload, so we spent that budget twice as fast as vanilla
 * alone — once for the GL buffer, once for the Vulkan copy. Measured at render
 * distance 64, the budget is what the fill-in rate is limited by, not the
 * number of builder threads.
 *
 * The copy needs neither OpenGL nor a driver call, so it can happen here, in
 * parallel, while the geometry is still in hand.
 *
 * Two things keep this safe rather than clever:
 *
 * - the mirror refuses whenever its fast path is unavailable, and then the
 *   render thread does exactly what it did before, so nothing depends on this
 *   succeeding;
 * - a buffer with no slot yet is left alone. Slots are handed out on the render
 *   thread during the first upload, and a chunk's VertexBuffer outlives its
 *   rebuilds, so this costs one missed copy per buffer ever rather than one per
 *   rebuild.
 */
@Mixin(ChunkRenderDispatcher.class)
public abstract class ChunkUploadMixin {

    @Inject(method = "uploadChunk", at = @At("HEAD"))
    private void vulkanmod112$mirrorOffThread(BlockRenderLayer layer, BufferBuilder builder,
                                              RenderChunk chunk, CompiledChunk compiled,
                                              double distanceSq,
                                              CallbackInfoReturnable<ListenableFuture<Object>> cir) {
        if (Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            // Vanilla uploads inline on this path, so the ordinary mirror hook
            // is about to run anyway; doing it here too would only copy twice.
            return;
        }
        VertexBuffer vertexBuffer = chunk.getVertexBufferByLayer(layer.ordinal());
        if (vertexBuffer == null) {
            return;
        }
        int slot = ((VertexBufferSlot) (Object) vertexBuffer).vulkanmod112$slot();
        if (slot == ChunkSlots.UNASSIGNED) {
            return;
        }
        ChunkMirror.onWorkerBuild(slot, builder.getByteBuffer());
    }
}
