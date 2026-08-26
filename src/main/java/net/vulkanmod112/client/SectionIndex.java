package net.vulkanmod112.client;

import java.util.List;

/**
 * The way one patch on {@code RenderGlobal} reaches what another one built.
 *
 * {@code OwnVisibilityWalkMixin} owns the search and the list it produces;
 * {@code EntitySectionsMixin} needs both in order to hand the game's entity
 * passes a shorter list. Mixin fields are private to the mixin that declares
 * them, so the two are joined the way the rest of this mod joins them — by an
 * interface the game's class ends up implementing once both patches are on.
 *
 * Either patch can be switched off on its own, so the reader has to cope with
 * the other one being absent: {@link #vulkanmod112$sectionWalk()} returns null
 * when there is no search of ours behind the list, and the caller then leaves
 * the frame alone.
 */
public interface SectionIndex {

    /**
     * The search whose index describes the current visible list, or null if
     * this mod did not produce that list.
     */
    VisibilityWalk vulkanmod112$sectionWalk();

    /**
     * The list this mod's search fills. Compared by identity against the game's
     * {@code renderInfos}: if they differ, the game did its own search this
     * frame and nothing here describes what it found.
     */
    @SuppressWarnings("rawtypes")
    List vulkanmod112$visibleList();
}
