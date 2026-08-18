package net.vulkanmod112.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.vulkanmod112.client.gui.Lang;
import net.vulkanmod112.core.VulkanPatchGroups;
import net.vulkanmod112.core.VulkanPatchState;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

/**
 * Which of this mod's class patches are allowed to be installed.
 *
 * <h2>Why this is a screen and not a setting</h2>
 *
 * Everything else in this mod's settings takes effect while you watch. This
 * cannot: a class is rewritten once, as the game loads it, and by the time
 * there is a screen to press a button on, every class this mod touches has been
 * loaded for minutes. So this screen is a list of what the next launch will do,
 * and it says that on the face of it rather than leaving the discovery to
 * somebody who turned something off and saw nothing change.
 *
 * <h2>What it is for</h2>
 *
 * Two things, and both of them start as a crash somebody else has to report.
 *
 * When a patch cannot be applied, the class it aimed at fails to load, and what
 * reaches the crash report is a vanilla class reported missing — no patch
 * named, no mod named, and removing mods one at a time isolates nothing because
 * every failure reads the same. The mod now writes down which group failed and
 * skips it next time, so the game starts. This screen is where that shows up:
 * which group stood down, what it said, and the button to try it again after
 * changing whatever was in the way.
 *
 * The other thing is going the other way. If this mod is suspected of a crash
 * or a visual fault, turning off one group at a time narrows it to a feature in
 * a few launches, which is a far better report than "it crashes with your mod".
 */
public final class GuiVulkanPatches extends GuiScreen {

    private static final int TOGGLE = 100;
    private static final int ALL_ON = 101;
    private static final int DONE = 102;

    private static final int ROW_HEIGHT = 16;
    private static final int LIST_TOP = 46;
    private static final int LIST_WIDTH = 300;

    private final GuiScreen parent;
    private List<String> groups;
    private String selected;
    private int scroll;

    public GuiVulkanPatches(GuiScreen parent) {
        this.parent = parent;
    }

    private int listLeft() {
        return (this.width - LIST_WIDTH) / 2;
    }

    private int listBottom() {
        return this.height - 76;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.groups = VulkanPatchGroups.names();
        if (this.selected == null && !this.groups.isEmpty()) {
            this.selected = this.groups.get(0);
        }
        int left = listLeft();
        int row = this.height - 48;
        int wide = LIST_WIDTH / 2 - 2;
        this.buttonList.add(new GuiButton(TOGGLE, left, row, wide, 20, ""));
        this.buttonList.add(new GuiButton(ALL_ON, left + wide + 4, row, wide, 20,
                Lang.tr(Lang.UI, "Turn Everything Back On")));
        this.buttonList.add(new GuiButton(DONE, left + LIST_WIDTH + 8, row, 70, 20,
                I18n.format("gui.done")));
        updateButtons();
    }

    private void updateButtons() {
        boolean essential = this.selected == null || VulkanPatchGroups.isEssential(this.selected);
        boolean on = this.selected != null && willBeInstalled(this.selected);
        for (Object entry : this.buttonList) {
            GuiButton button = (GuiButton) entry;
            if (button.id == TOGGLE) {
                button.enabled = !essential;
                button.displayString = on
                        ? Lang.tr(Lang.UI, "Do Not Install")
                        : Lang.tr(Lang.UI, "Install Again");
            }
        }
    }

    /** Whether the next launch will try to install this group. */
    private static boolean willBeInstalled(String group) {
        return VulkanPatchGroups.isEssential(group)
                || (VulkanPatchState.isEnabled(group)
                && VulkanPatchState.quarantineReason(group) == null);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (!button.enabled) {
            return;
        }
        switch (button.id) {
            case TOGGLE:
                if (willBeInstalled(this.selected)) {
                    VulkanPatchState.setEnabled(this.selected, false);
                } else {
                    // One button, both reasons a group can be off. Somebody
                    // pressing "install again" on a group that stood down after
                    // a failure is asking for exactly that, and making them
                    // clear the failure separately would only teach them that
                    // the button does not work.
                    VulkanPatchState.clearQuarantine(this.selected);
                    VulkanPatchState.setEnabled(this.selected, true);
                }
                break;
            case ALL_ON:
                for (String group : this.groups) {
                    VulkanPatchState.clearQuarantine(group);
                    VulkanPatchState.setEnabled(group, true);
                }
                break;
            case DONE:
                this.mc.displayGuiScreen(this.parent);
                return;
            default:
                return;
        }
        updateButtons();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, Lang.tr(Lang.UI, "Class Patches"),
                this.width / 2, 14, 0xFFFFFF);
        this.drawCenteredString(this.fontRenderer,
                Lang.tr(Lang.UI, "What this mod is allowed to rewrite. Changes apply the next time the game starts."),
                this.width / 2, 27, 0x808080);

        int left = listLeft();
        int right = left + LIST_WIDTH;
        int bottom = listBottom();
        drawRect(left - 2, LIST_TOP - 2, right + 2, bottom + 2, 0x60000000);

        int y = LIST_TOP - this.scroll;
        for (String group : this.groups) {
            if (y + ROW_HEIGHT > LIST_TOP && y < bottom) {
                boolean over = mouseX >= left && mouseX <= right && mouseY >= y
                        && mouseY < y + ROW_HEIGHT && mouseY >= LIST_TOP && mouseY < bottom;
                boolean chosen = group.equals(this.selected);
                if (chosen || over) {
                    drawRect(left, Math.max(y, LIST_TOP), right, Math.min(y + ROW_HEIGHT, bottom),
                            chosen ? 0x805A8CC8 : 0x40FFFFFF);
                }
                this.fontRenderer.drawString(VulkanPatchGroups.title(group), left + 5, y + 4,
                        chosen ? 0xFFFFFF : 0xD0D0D0);
                String state = state(group);
                this.fontRenderer.drawString(state,
                        right - 5 - this.fontRenderer.getStringWidth(state), y + 4, stateColour(group));
            }
            y += ROW_HEIGHT;
        }

        drawFoot(bottom);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /**
     * The three lines under the list: what the selected group is, what turning
     * it off costs, and what it said if it failed.
     */
    private void drawFoot(int bottom) {
        if (this.selected == null) {
            return;
        }
        int left = listLeft();
        String reason = VulkanPatchState.quarantineReason(this.selected);
        String line = reason != null
                ? Lang.tr(Lang.UI, "It stood down on its own") + ": " + reason
                : VulkanPatchGroups.cost(this.selected);
        for (String wrapped : this.fontRenderer.listFormattedStringToWidth(line, LIST_WIDTH)) {
            this.fontRenderer.drawString(wrapped, left, bottom + 6, reason != null ? 0xC08050 : 0xA0A0A0);
            bottom += 10;
            // Two lines is the room there is; the rest is in the log, where a
            // reason this long belongs anyway.
            if (bottom > listBottom() + 16) {
                break;
            }
        }
    }

    private static String state(String group) {
        if (VulkanPatchGroups.isEssential(group)) {
            return Lang.tr(Lang.UI, "always");
        }
        if (!willBeInstalled(group)) {
            return VulkanPatchState.quarantineReason(group) != null
                    ? Lang.tr(Lang.UI, "stood down")
                    : Lang.tr(Lang.UI, "off");
        }
        // Installed for the next launch, but was it installed for this one? The
        // difference is the whole reason somebody opens this screen after
        // pressing a button here and finding nothing changed.
        return VulkanPatchState.wasApplied(group)
                ? Lang.tr(Lang.UI, "on")
                : Lang.tr(Lang.UI, "on after restart");
    }

    private static int stateColour(String group) {
        if (VulkanPatchGroups.isEssential(group)) {
            return 0x707070;
        }
        if (VulkanPatchState.quarantineReason(group) != null) {
            return 0xC08050;
        }
        if (!VulkanPatchState.isEnabled(group)) {
            return 0x909090;
        }
        return VulkanPatchState.wasApplied(group) ? 0x70B070 : 0xB0B070;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            this.scroll -= net.vulkanmod112.client.gui.Wheel.notches(wheel) * ROW_HEIGHT * 2;
            int max = Math.max(0, this.groups.size() * ROW_HEIGHT - (listBottom() - LIST_TOP));
            this.scroll = Math.max(0, Math.min(this.scroll, max));
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        int left = listLeft();
        if (mouseX >= left && mouseX <= left + LIST_WIDTH
                && mouseY >= LIST_TOP && mouseY < listBottom()) {
            int index = (mouseY - LIST_TOP + this.scroll) / ROW_HEIGHT;
            if (index >= 0 && index < this.groups.size()) {
                this.selected = this.groups.get(index);
                updateButtons();
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }
}
