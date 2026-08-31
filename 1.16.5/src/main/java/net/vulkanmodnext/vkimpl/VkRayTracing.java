package net.vulkanmodnext.vkimpl;

/**
 * The shape of ray tracing, with none of it behind the shape.
 *
 * <h2>Why a class that does nothing is better than deleting the calls</h2>
 *
 * The terrain renderer arrived from 1.12.2 with ray tracing woven through it:
 * where the acceleration structure is rebuilt, which frame it belongs to, how
 * the creature geometry is handed over, what the buffer needs on top of being a
 * vertex buffer. That is a great deal of hard-won placement, and none of it can
 * run here — the bindings the game brings are older than Vulkan 1.2 and know
 * nothing of {@code KHR_acceleration_structure}.
 *
 * <p>Two ways to deal with that. Cut the calls out, and the placement is lost:
 * whoever brings ray tracing to this version has to find every one of those
 * spots again, and the ones they miss are the ones that fail quietly. Or keep
 * the calls and make the object they are talking to do nothing — which is what
 * this is.
 *
 * <p>It is never constructed. The renderer only builds one when ray tracing is
 * enabled, and {@code VkContext} cannot enable it. So this costs a class file
 * and buys the map back.
 *
 * <p>What it deliberately does <b>not</b> do is pretend. Nothing here returns a
 * plausible-looking handle or a comforting boolean: {@link #topLevel} answers
 * zero, {@link #creaturesInStructure} answers false, and the diagnostics say in
 * words that this version has no ray tracing rather than printing an empty
 * section. A stub that answers agreeably is how a missing feature gets reported
 * as a broken one.
 */
final class VkRayTracing {

    VkRayTracing(VkContext ctx) {
        throw new UnsupportedOperationException(
                "Ray tracing needs Vulkan 1.2 and KHR_acceleration_structure, and the LWJGL this "
                        + "version of Minecraft ships (3.2.2) predates both. Nothing should be "
                        + "constructing this — see VkContext.pickApiVersion.");
    }

    /**
     * How far the traced light reaches, in blocks.
     *
     * Kept real rather than stubbed: the terrain shader is told this number
     * every frame whether or not anything is traced, and it is a setting, not
     * a structure.
     */
    static int radiusBlocks() {
        return 0;
    }

    void setKindBounds(int solidCount, int foliageEnd) {
    }

    long topLevel(int slot) {
        return 0L;
    }

    void update(int[] chunks, int chunkCount, VkChunkMirror mirror, long frameIndex,
                int slot, int slots, double viewX, double viewY, double viewZ) {
    }

    boolean creaturesInStructure() {
        return false;
    }

    void setCreatureGeometry(long buffer, long byteOffset, int vertexCount) {
    }

    void setIndexBuffer(long buffer) {
    }

    void appendDiagnostics(StringBuilder sb) {
        sb.append("  ray tracing: not on this version — the game's LWJGL 3.2.2 has neither "
                + "Vulkan 1.2 nor KHR_acceleration_structure\n");
    }

    void destroy() {
    }
}
