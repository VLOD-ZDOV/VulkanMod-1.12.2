package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import net.vulkanmodnext.client.RenderInfo;
import net.vulkanmodnext.client.SectionIndex;
import net.vulkanmodnext.client.VanillaFrame;
import net.vulkanmodnext.client.VisibilityWalk;
import net.vulkanmodnext.client.VulkanConfig;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Gives the game's own layer filter the sections that hold blocks instead of
 * every section on screen.
 *
 * <h2>What the filter is</h2>
 *
 * `renderBlockLayer` begins by walking {@code renderInfos} and calling
 * {@code isLayerEmpty} on each section, keeping the ones that contribute to the
 * layer being drawn. It runs four times a frame, once per layer. At thirty-two
 * chunks it asks some 17 700 sections and keeps under 2 700 — the world above
 * the ground and below it is open air, and open air is asked four times a frame
 * whether it has any stone in it.
 *
 * Measured on a fixed route at thirty-two chunks, the method as a whole falls
 * from 0.72 ms a frame to 0.45. That is the largest single thing left on the
 * render thread that this mod did not put there — and on the machine it was
 * measured on it bought no frames at all, which is why the setting is off by
 * default and says so. Once the entity passes were shortened the frame stopped
 * waiting on this thread, so what is removed here is real work that hides in
 * time already spare. On a processor slow enough for that thread to be the
 * limit again it is worth having, and the switch is how that is found out.
 *
 * <h2>Why the answer is free to have</h2>
 *
 * Whether a section holds any blocks at all is one bit on its compiled chunk,
 * and the pointer that leads to it is the same one the visibility search
 * already follows for every section it walks. So the search keeps the list, and
 * the game's filter runs over that — it still decides, per layer, which of
 * those sections contributes, because that is the part that genuinely differs
 * between the four passes.
 *
 * The order is the visible-list order, and it is walked backwards for the
 * translucent layer exactly as before: a subset of a list, reversed, is the
 * reverse of the subset.
 *
 * <h2>The one read left alone</h2>
 *
 * The loop above the filter, which asks the chunk dispatcher to re-sort
 * translucent geometry after the camera has moved a block, still reads the full
 * list. It asks {@code isLayerStarted} rather than {@code isLayerEmpty}, and a
 * chunk can have started a layer and used none of it — so a shorter list would
 * be a different answer rather than the same one arrived at cheaply, and this
 * change is only worth making where it is the same answer.
 */
@Mixin(RenderGlobal.class)
public abstract class LayerSectionsMixin {

    @Shadow
    @SuppressWarnings("rawtypes")
    private List renderInfos;

    /**
     * Decided once at the top of each call and read by the two that follow it.
     *
     * The last of the three reads happens on every element of the list, so
     * paying for the decision there would be paying seventeen thousand times
     * for an answer that cannot change inside one call.
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private List vulkanmodnext$layerSections;

    @Unique
    private static final boolean VULKANMOD112$VERIFY =
            Boolean.getBoolean("vulkanmodnext.verifyLayerSections");

    /**
     * The two size reads are the two arms of one conditional — the translucent
     * layer counts down from the end and the other three count up from the
     * start — so exactly one of them runs per call, and both have to be the
     * place the decision is made.
     */
    @Redirect(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;renderInfos:Ljava/util/List;",
                    opcode = Opcodes.GETFIELD, ordinal = 1))
    @SuppressWarnings("rawtypes")
    private List vulkanmodnext$decideCountingBack(RenderGlobal self, BlockRenderLayer layer,
                                                 double partialTicks, int pass, Entity entityIn) {
        return vulkanmodnext$decide(layer);
    }

    @Redirect(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;renderInfos:Ljava/util/List;",
                    opcode = Opcodes.GETFIELD, ordinal = 2))
    @SuppressWarnings("rawtypes")
    private List vulkanmodnext$decideCountingForward(RenderGlobal self, BlockRenderLayer layer,
                                                    double partialTicks, int pass, Entity entityIn) {
        return vulkanmodnext$decide(layer);
    }

    /**
     * Read once per element of the list, so it does nothing but hand back what
     * the decision above already settled.
     */
    @Redirect(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;renderInfos:Ljava/util/List;",
                    opcode = Opcodes.GETFIELD, ordinal = 3))
    @SuppressWarnings("rawtypes")
    private List vulkanmodnext$takeElement(RenderGlobal self) {
        return vulkanmodnext$layerSections;
    }

    @Unique
    @SuppressWarnings("rawtypes")
    private List vulkanmodnext$decide(BlockRenderLayer layer) {
        List full = renderInfos;
        VisibilityWalk walk = vulkanmodnext$index(full);
        if (walk == null) {
            vulkanmodnext$layerSections = full;
            return full;
        }
        walk.applyArrivals();
        List out = walk.geometrySections();
        vulkanmodnext$layerSections = out;
        VanillaFrame.countLayerSections(out.size(), full.size());
        if (VULKANMOD112$VERIFY) {
            vulkanmodnext$verify(full, out, layer);
        }
        return out;
    }

    @Unique
    @SuppressWarnings("rawtypes")
    private VisibilityWalk vulkanmodnext$index(List full) {
        if (!VulkanConfig.isShortLayerSections() || !(this instanceof SectionIndex)) {
            return null;
        }
        SectionIndex index = (SectionIndex) this;
        if (full != index.vulkanmodnext$visibleList()) {
            return null;
        }
        VisibilityWalk walk = index.vulkanmodnext$sectionWalk();
        return walk != null && walk.indexed() ? walk : null;
    }

    /**
     * Walks the long list as well and says what the short one is missing.
     *
     * A section dropped from here is a chunk that stops being drawn, so this
     * exists for the same reason the one beside it does: the failure looks
     * exactly like terrain that has not finished building, and nothing about
     * the picture would say which.
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private void vulkanmodnext$verify(List full, List out, BlockRenderLayer layer) {
        int expected = 0;
        int missing = 0;
        for (int i = 0; i < full.size(); i++) {
            RenderInfo info = (RenderInfo) full.get(i);
            if (info.vulkanmodnext$chunk().getCompiledChunk().isLayerEmpty(layer)) {
                continue;
            }
            expected++;
            if (!out.contains(info)) {
                missing++;
            }
        }
        VanillaFrame.countLayerSectionCheck(expected, missing);
    }
}
