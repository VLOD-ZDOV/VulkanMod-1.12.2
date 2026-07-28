package net.vulkanmod112.client;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.EnumFacing;

/**
 * A handle on {@code RenderGlobal.ContainerLocalRenderInformation}, the record
 * the game keeps for every chunk its visibility search reaches.
 *
 * That class is package-private and an inner class of {@code RenderGlobal}, so
 * code outside {@code net.minecraft.client.renderer} cannot name it at all —
 * not to construct one, not to declare a variable of it. A replacement search
 * still has to produce exactly those objects, because {@code renderInfos} is
 * read afterwards by {@code renderEntities} and {@code renderBlockLayer}, which
 * are vanilla and expect vanilla's type.
 *
 * A mixin can reach it by name as a string, so the way through is to have that
 * mixin implement this interface: our search then holds the objects as
 * {@code RenderInfo} and never mentions the real type.
 *
 * The fields it exposes are final in the game's source, which is fine for a
 * class allocated once per visited chunk and thrown away. Reusing the objects
 * instead means writing them again, so the mixin unseals them; see
 * {@code RenderInfoMixin}.
 */
public interface RenderInfo {

    RenderChunk vulkanmod112$chunk();

    EnumFacing vulkanmod112$facing();

    /** Puts a pooled record back into the state a fresh one would have. */
    void vulkanmod112$reset(RenderChunk chunk, EnumFacing facing, int counter);

    /**
     * The bitmask of directions travelled to get here, inherited from the
     * record this one was reached from. Vanilla's {@code setDirection} takes the
     * parent's mask and the step, and the search uses it to refuse to walk back
     * the way it came.
     */
    void vulkanmod112$setDirection(byte parentFacing, EnumFacing step);

    boolean vulkanmod112$hasDirection(EnumFacing direction);

    byte vulkanmod112$facingMask();

    /**
     * The grid slot of this record's chunk, or -1 when it is not known — which
     * is the case for every record the game allocated itself.
     *
     * Carried here so that the loop at the end of {@code setupTerrain} can ask
     * whether a chunk needs rebuilding without dereferencing the chunk. The
     * search knows the slot already: it is the number it stepped to in order to
     * reach the chunk, so putting it here costs nothing.
     */
    int vulkanmod112$gridSlot();

    void vulkanmod112$setGridSlot(int slot);
}
