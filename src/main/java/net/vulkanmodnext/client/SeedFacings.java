package net.vulkanmodnext.client;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import java.util.Set;

/**
 * The faces reachable from the block the camera's eye is in, memoised.
 *
 * The answer is what seeds the visibility search, and working it out reads all
 * 4096 block states of the camera's own chunk section. Two mixins on
 * {@code RenderGlobal} need it — the one that caches vanilla's call, and the
 * replacement search, which does not go through vanilla's call site at all —
 * and mixins targeting the same class are merged into it, so an interface is
 * how one reaches the other.
 */
public interface SeedFacings {

    /**
     * @return a set the caller may modify; the cached copy is kept separately.
     *         Vanilla removes an element from what it gets back, and handing
     *         out the cached set itself would have poisoned the cache.
     */
    Set<EnumFacing> vulkanmodnext$visibleFacings(BlockPos pos);
}
