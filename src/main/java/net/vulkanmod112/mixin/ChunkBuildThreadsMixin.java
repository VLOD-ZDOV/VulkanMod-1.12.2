package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.util.math.MathHelper;
import net.vulkanmod112.client.VulkanConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Decides the chunk-building thread count from the CPU instead of the heap.
 *
 * Vanilla picks it in the dispatcher's constructor:
 *
 * <pre>
 * int i = Math.max(1, (int)(maxMemory * 0.3D) / 10485760);
 * int j = Math.max(1, MathHelper.clamp(availableProcessors, 1, i / 5));
 * </pre>
 *
 * The ceiling {@code i / 5} comes from the heap, and the smaller of the two
 * wins. On a 4 GiB heap that ceiling is 24 threads however many cores the
 * machine has, so a large CPU builds chunks with part of itself idle — and
 * chunk building is measurably what the frame waits for at high render
 * distances, not drawing.
 *
 * Only the thread count is redirected. The builder pool right below it is
 * still sized by vanilla's {@code clamp(j * 10, 1, i)}, so the heap keeps the
 * last word on memory: past a dozen threads that clamp is already saturated
 * and more workers cost threads, not buffers. Workers that find no free
 * builder simply wait, which is why overshooting is wasteful rather than
 * harmful.
 *
 * Raising this cannot help a machine whose cores are already all in use, and
 * on a small heap the builder pool, not the thread count, is the limit. The
 * default leaves vanilla's choice untouched.
 */
@Mixin(ChunkRenderDispatcher.class)
public abstract class ChunkBuildThreadsMixin {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/ChunkBuild");

    /**
     * The first of the two {@code clamp} calls in the constructor is the one
     * that yields the thread count; the second sizes the builder pool and is
     * deliberately left alone.
     */
    @Redirect(
            method = "<init>(I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/math/MathHelper;clamp(III)I",
                    ordinal = 0))
    private int vulkanmod112$chooseThreadCount(int value, int min, int max) {
        int vanilla = MathHelper.clamp(value, min, max);
        int configured = VulkanConfig.getChunkBuildThreads();
        if (configured <= 0) {
            return vanilla;
        }
        LOGGER.info("Chunk building on {} threads instead of vanilla's {} ({} cores, heap ceiling {})",
                configured, vanilla, Runtime.getRuntime().availableProcessors(), max);
        return configured;
    }
}
