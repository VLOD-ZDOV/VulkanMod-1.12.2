package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.vulkanmod112.mixin.RenderChunkAccessor;
import net.vulkanmod112.mixin.RenderGlobalAccessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

/**
 * Asks the game, in one keypress, why a chunk is not on screen.
 *
 * <h2>Why this exists</h2>
 *
 * Three separate complaints have the same shape and have been chased
 * separately for weeks: a single chunk that will not draw from certain
 * positions, a machine where twelve chunks of fifteen hundred loaded ones are
 * drawn, and a chunk-preloading setting that loads less than it promises. All
 * three go away when the view narrows — zoom, fly closer, look again — and a
 * chunk that appears when the view frustum gets *smaller* was never rejected by
 * the frustum, because that is motion in the wrong direction.
 *
 * What they share is that each of them forces the visible list to be built
 * again. So the question is not "is it in front of me" but "did the walk reach
 * it, and if it did, what threw it away afterwards" — and that is answerable
 * from state the game already keeps, if anybody reads it.
 *
 * <h2>What it reads</h2>
 *
 * Vanilla stamps every chunk its visibility walk touches with the frame number
 * it touched it on. A chunk that is loaded, built, not empty and still absent
 * either has an old stamp — the walk never came — or a current one, and then
 * the loss is downstream and ours to explain. Those two need opposite fixes,
 * and no amount of toggling settings tells them apart.
 *
 * The count of chunks carrying the newest stamp is the same number the game's
 * own debug screen shows on the left, so it can be compared against what this
 * renderer actually drew without trusting either one alone.
 */
public final class ChunkProbe {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Probe");

    private static final KeyBinding KEY = new KeyBinding(
            "key.vulkanmod112.probe", Keyboard.KEY_NONE, "key.categories.vulkanmod112");

    /** How far to look for the chunk being asked about. */
    private static final double REACH = 160.0;

    private ChunkProbe() {
    }

    public static void register() {
        ClientRegistry.registerKeyBinding(KEY);
    }

    public static KeyBinding keyBinding() {
        return KEY;
    }

    public static final class Handler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.currentScreen != null || mc.world == null || mc.player == null) {
                return;
            }
            if (KEY.isPressed()) {
                try {
                    probe(mc);
                } catch (Throwable t) {
                    LOGGER.error("The chunk probe failed", t);
                }
            }
        }
    }

    private static void probe(Minecraft mc) {
        BlockPos target = lookedAt(mc);
        ViewFrustum frustum = ((RenderGlobalAccessor) mc.renderGlobal).vulkanmod112$viewFrustum();
        if (frustum == null || frustum.renderChunks == null) {
            LOGGER.info("Chunk probe: the renderer has no chunk grid yet");
            return;
        }

        // The newest stamp any chunk carries is this frame's, whatever number
        // the game happens to be counting from — so the walk's own numbering
        // never has to be reached for.
        int newest = Integer.MIN_VALUE;
        int reached = 0;
        int built = 0;
        int empty = 0;
        for (RenderChunk chunk : frustum.renderChunks) {
            if (chunk == null) {
                continue;
            }
            int stamp = ((RenderChunkAccessor) chunk).vulkanmod112$frameIndex();
            if (stamp > newest) {
                newest = stamp;
            }
        }
        for (RenderChunk chunk : frustum.renderChunks) {
            if (chunk == null) {
                continue;
            }
            if (((RenderChunkAccessor) chunk).vulkanmod112$frameIndex() == newest) {
                reached++;
            }
            CompiledChunk compiled = chunk.getCompiledChunk();
            if (compiled != null && compiled != CompiledChunk.DUMMY) {
                built++;
                if (compiled.isEmpty()) {
                    empty++;
                }
            }
        }
        int queued = 0;
        try {
            java.util.Set<RenderChunk> waiting =
                    ((RenderGlobalAccessor) mc.renderGlobal).vulkanmod112$chunksToUpdate();
            queued = waiting == null ? -1 : waiting.size();
        } catch (Throwable ignored) {
            queued = -1;
        }
        // When every chunk carries the same stamp the stamp is not being set at
        // all — this renderer can do its own visibility search, and that one
        // does not mark what it visits. Saying so is better than reporting that
        // everything was reached, which is what the raw number looks like.
        boolean stampWorks = reached < frustum.renderChunks.length;
        LOGGER.info("Chunk probe: grid holds {} chunks, {} built, {} of those empty, "
                        + "{} waiting to be built; walk stamp {}",
                frustum.renderChunks.length, built, empty, queued,
                stampWorks ? reached + " chunks reached this frame"
                        : "NOT SET — nothing marks what it visits, so 'reached' means nothing");

        RenderChunk chunk = findChunk(frustum, target);
        if (chunk == null) {
            LOGGER.info("Chunk probe: looking at {} — no chunk renderer covers it, so it is "
                    + "outside the grid the render distance builds", target);
            return;
        }
        CompiledChunk compiled = chunk.getCompiledChunk();
        int stamp = ((RenderChunkAccessor) chunk).vulkanmod112$frameIndex();
        StringBuilder line = new StringBuilder();
        line.append("Chunk probe: looking at ").append(target)
                .append(", its chunk starts at ").append(chunk.getPosition())
                .append("; walk stamp ").append(stamp).append(" against newest ").append(newest)
                .append(stampWorks
                        ? (stamp == newest ? " — REACHED this frame" : " — NOT reached this frame")
                        : " — stamp not in use, ignore this")
                .append("; needs rebuild ").append(chunk.needsUpdate());
        if (compiled == null || compiled == CompiledChunk.DUMMY) {
            line.append("; never built");
        } else {
            line.append("; built, empty=").append(compiled.isEmpty()).append(", layers");
            for (BlockRenderLayer layer : BlockRenderLayer.values()) {
                line.append(' ').append(layer.name().charAt(0))
                        .append(compiled.isLayerEmpty(layer) ? "-" : "+");
            }
            // Which ways out of this section the game believes are connected.
            // A chunk the walk did not reach, whose neighbours all report no
            // way through, was refused by vanilla's own graph and would be
            // missing without this mod as well.
            line.append("; visible through");
            int open = 0;
            for (net.minecraft.util.EnumFacing from : net.minecraft.util.EnumFacing.values()) {
                for (net.minecraft.util.EnumFacing to : net.minecraft.util.EnumFacing.values()) {
                    if (from != to && compiled.isVisible(from, to)) {
                        open++;
                    }
                }
            }
            line.append(' ').append(open).append(" of 30 face pairs");
        }
        LOGGER.info(line.toString());
        LOGGER.info("Chunk probe: read it as — \"never built\" with \"needs rebuild true\" is a "
                + "chunk waiting in the queue, and then the queue length above is the thing to "
                + "look at, not the visibility walk; \"built\" and not empty but absent from the "
                + "screen means the loss is after the build, in this renderer; few open face "
                + "pairs means vanilla's own graph refused it and the mod is innocent");
    }

    /**
     * The block being looked at, or a point out along the view when nothing is.
     *
     * Sky is the interesting case rather than an obstacle: a chunk that will not
     * draw is very often one you can see past, so a point along the look
     * direction is what the question is really about.
     */
    private static BlockPos lookedAt(Minecraft mc) {
        RayTraceResult hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK && hit.getBlockPos() != null) {
            return hit.getBlockPos();
        }
        Vec3d eye = mc.player.getPositionEyes(1.0f);
        Vec3d look = mc.player.getLook(1.0f);
        return new BlockPos(eye.x + look.x * REACH, eye.y + look.y * REACH, eye.z + look.z * REACH);
    }

    /**
     * Scanned rather than indexed, because the grid's own lookup is not public
     * and this runs once, on a keypress, on a few thousand entries.
     */
    private static RenderChunk findChunk(ViewFrustum frustum, BlockPos pos) {
        int wantX = pos.getX() >> 4 << 4;
        int wantY = pos.getY() >> 4 << 4;
        int wantZ = pos.getZ() >> 4 << 4;
        for (RenderChunk chunk : frustum.renderChunks) {
            if (chunk == null) {
                continue;
            }
            BlockPos at = chunk.getPosition();
            if (at.getX() == wantX && at.getY() == wantY && at.getZ() == wantZ) {
                return chunk;
            }
        }
        return null;
    }
}
