package net.vulkanmodnext.mixin;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Hands the two passes inside {@code renderEntities} a list of the sections
 * that can possibly contain anything, instead of every section on screen.
 *
 * <h2>What is actually expensive there</h2>
 *
 * {@code renderEntities} reads as a loop over creatures and is not one. It
 * loops over {@code renderInfos} — every section the visibility search reached
 * — and asks the world which chunk each one belongs to before it knows whether
 * anything stands in it. Measured on a fixed route with the scene held still at
 * five drawn creatures, it costs 0.07 ms at eight chunks, 0.38 at sixteen and
 * 1.10 at thirty-two. The creature count never moved; the section count did.
 * The block-entity half of the same method grows the same way — 0.01 to 0.36 ms
 * — with zero block entities drawn, which is the cleanest possible proof that
 * what is being paid for is the walk and not the drawing.
 *
 * At thirty-two chunks that list holds some 17 700 sections and about 2 700 of
 * them have any geometry at all. The other 85% is open air, and it cannot
 * simply be dropped: creatures stand in open air. But the two questions the
 * passes are really asking — "which sections hold a creature" and "which hold a
 * block entity" — both have far shorter answers than "which sections are on
 * screen", and both can be answered without walking the long list.
 *
 * <h2>The creature pass</h2>
 *
 * Turned inside out. There are sixty entities in the world and seventeen
 * thousand sections, so the loop runs over the entities: each one names the
 * section it is filed under through {@code chunkCoordX/Y/Z}, which are the very
 * indices the chunk's own entity lists are kept at, and the visibility search's
 * index says in one array read whether that section is on screen. The sections
 * that come back are handed to the game, which then walks them exactly as it
 * always did and finds exactly the same creatures — the set is identical by
 * construction, since a section reaches the game only if a creature named it
 * and the search had it on screen.
 *
 * They are handed over in visible-list order rather than in entity order, so
 * the game still draws front-to-back down to the section, as it would have.
 *
 * <h2>The block-entity pass</h2>
 *
 * Answered from a list the search gathers while it walks, because the answer
 * changes only when a chunk is rebuilt and a rebuild arms the next search. A
 * chest placed this instant therefore appears within one client tick, which is
 * the same delay the terrain around it already has.
 *
 * <h2>When it stands down</h2>
 *
 * Whenever the list the game is holding is not the one this mod's search
 * produced — the search switched off, a frame handed back to vanilla, a camera
 * outside the world's height. The test for that is identity on the list itself,
 * which cannot be fooled by a stale index.
 */
@Mixin(RenderGlobal.class)
public abstract class EntitySectionsMixin {

    @Shadow
    private WorldClient world;
    @Shadow
    @SuppressWarnings("rawtypes")
    private List renderInfos;

    /**
     * Rebuilt each pass and never handed out, so the game reads it while it is
     * ours and nothing else ever holds a reference to it.
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private final List vulkanmodnext$withEntities = new ArrayList(64);

    /**
     * Walks the long list as well and says what the short one is missing.
     *
     * A shortcut that quietly drops a creature looks exactly like a creature
     * that walked off, so it needs an instrument that fails loudly rather than
     * an argument that it cannot happen. This costs the full scan it exists to
     * remove, which is why it is a switch and not an assertion.
     */
    @Unique
    private static final boolean VULKANMOD112$VERIFY =
            Boolean.getBoolean("vulkanmodnext.verifyEntitySections");

    @Redirect(method = "renderEntities",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;renderInfos:Ljava/util/List;",
                    opcode = Opcodes.GETFIELD, ordinal = 0))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List vulkanmodnext$sectionsWithEntities(RenderGlobal self) {
        List full = renderInfos;
        VisibilityWalk walk = vulkanmodnext$index(full);
        if (walk == null) {
            return full;
        }

        List out = vulkanmodnext$withEntities;
        out.clear();
        walk.beginPick();
        List<Entity> loaded = world.loadedEntityList;
        for (int i = 0, size = loaded.size(); i < size; i++) {
            Entity entity = loaded.get(i);
            // The fields, not the position: an entity that moved this tick is
            // still filed in the section these name, and that is the section
            // the game's own loop would have found it in.
            RenderInfo info = walk.visibleSection(entity.chunkCoordX, entity.chunkCoordY,
                    entity.chunkCoordZ);
            if (info != null && walk.pick(info.vulkanmodnext$gridSlot())) {
                out.add(info);
            }
        }
        vulkanmodnext$sortByVisibleOrder(out, walk);
        VanillaFrame.countEntitySections(out.size(), full.size());
        if (VULKANMOD112$VERIFY) {
            vulkanmodnext$verifyEntities(full, out);
        }
        return out;
    }

    @Redirect(method = "renderEntities",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;renderInfos:Ljava/util/List;",
                    opcode = Opcodes.GETFIELD, ordinal = 1))
    @SuppressWarnings("rawtypes")
    private List vulkanmodnext$sectionsWithTileEntities(RenderGlobal self) {
        List full = renderInfos;
        VisibilityWalk walk = vulkanmodnext$index(full);
        if (walk == null) {
            return full;
        }
        walk.applyArrivals();
        List out = walk.tileEntitySections();
        VanillaFrame.countTileEntitySections(out.size(), full.size());
        if (VULKANMOD112$VERIFY) {
            vulkanmodnext$verifyTileEntities(full, out);
        }
        return out;
    }

    /**
     * The search behind the list the game is holding, or null when there is
     * none and the frame has to be left alone.
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private VisibilityWalk vulkanmodnext$index(List full) {
        if (!VulkanConfig.isShortEntitySections() || world == null
                || !(this instanceof SectionIndex)) {
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
     * Insertion sort, because the list is tens of entries long and the one
     * thing that matters is that it ends up in the order the game would have
     * visited these sections in.
     */
    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void vulkanmodnext$sortByVisibleOrder(List out, VisibilityWalk walk) {
        for (int i = 1; i < out.size(); i++) {
            Object moving = out.get(i);
            int key = walk.visibleOrder((RenderInfo) moving);
            int j = i - 1;
            while (j >= 0 && walk.visibleOrder((RenderInfo) out.get(j)) > key) {
                out.set(j + 1, out.get(j));
                j--;
            }
            out.set(j + 1, moving);
        }
    }

    @Unique
    @SuppressWarnings("rawtypes")
    private void vulkanmodnext$verifyEntities(List full, List out) {
        int expected = 0;
        int missing = 0;
        for (int i = 0; i < full.size(); i++) {
            RenderInfo info = (RenderInfo) full.get(i);
            BlockPos position = info.vulkanmodnext$chunk().getPosition();
            Chunk chunk = world.getChunk(position);
            if (chunk.getEntityLists()[position.getY() >> 4].isEmpty()) {
                continue;
            }
            expected++;
            if (!out.contains(info)) {
                missing++;
            }
        }
        VanillaFrame.countEntitySectionCheck(expected, out.size(), missing);
    }

    @Unique
    @SuppressWarnings("rawtypes")
    private void vulkanmodnext$verifyTileEntities(List full, List out) {
        int expected = 0;
        int missing = 0;
        for (int i = 0; i < full.size(); i++) {
            RenderInfo info = (RenderInfo) full.get(i);
            if (info.vulkanmodnext$chunk().getCompiledChunk().getTileEntities().isEmpty()) {
                continue;
            }
            expected++;
            if (!out.contains(info)) {
                missing++;
            }
        }
        VanillaFrame.countTileEntitySectionCheck(expected, out.size(), missing);
    }
}
