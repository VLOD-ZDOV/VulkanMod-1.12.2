package net.vulkanmod112.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.vulkanmod112.client.gui.Lang;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

/**
 * Saved configurations, by name.
 *
 * This replaces four numbered slots, and the reason is not tidiness. A slot
 * tells you nothing about what is in it: two weeks after saving one, choosing
 * between "Slot 2" and "Slot 3" means loading both to find out. The whole value
 * of a profile is being able to go back to a configuration without remembering
 * what was in it, and a number is the one label that cannot help with that.
 *
 * A screen of its own rather than more rows in the settings list, because this
 * needs something the settings list does not have and should not grow: a place
 * to type.
 */
public final class GuiVulkanProfiles extends GuiScreen {

    private static final int SAVE = 100;
    private static final int LOAD = 101;
    private static final int RENAME = 102;
    private static final int DELETE = 103;
    private static final int DONE = 104;

    private static final int ROW_HEIGHT = 14;
    private static final int LIST_TOP = 44;
    private static final int LIST_WIDTH = 240;

    private final GuiScreen parent;
    private GuiTextField nameField;
    private List<String> names;
    private String selected;
    private int scroll;
    /** One line under the list saying what the last button did. */
    private String status = "";

    public GuiVulkanProfiles(GuiScreen parent) {
        this.parent = parent;
    }

    private int listBottom() {
        return this.height - 78;
    }

    private int listLeft() {
        return (this.width - LIST_WIDTH) / 2;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();
        this.names = VulkanProfiles.names();
        if (this.selected != null && !this.names.contains(this.selected)) {
            this.selected = null;
        }

        int left = listLeft();
        this.nameField = new GuiTextField(0, this.fontRenderer, left, this.height - 72,
                LIST_WIDTH, 18);
        this.nameField.setMaxStringLength(40);
        if (this.selected != null) {
            this.nameField.setText(this.selected);
        }

        int row = this.height - 48;
        int wide = LIST_WIDTH / 2 - 2;
        this.buttonList.add(new GuiButton(SAVE, left, row, wide, 20,
                Lang.tr(Lang.UI, "Save As")));
        this.buttonList.add(new GuiButton(LOAD, left + wide + 4, row, wide, 20,
                Lang.tr(Lang.UI, "Load")));
        row += 22;
        this.buttonList.add(new GuiButton(RENAME, left, row, wide, 20,
                Lang.tr(Lang.UI, "Rename")));
        this.buttonList.add(new GuiButton(DELETE, left + wide + 4, row, wide, 20,
                Lang.tr(Lang.UI, "Delete")));
        this.buttonList.add(new GuiButton(DONE, left + LIST_WIDTH + 8, this.height - 48, 70, 20,
                net.minecraft.client.resources.I18n.format("gui.done")));
        updateButtons();
    }

    private void updateButtons() {
        String typed = this.nameField == null ? "" : this.nameField.getText().trim();
        for (Object entry : this.buttonList) {
            GuiButton button = (GuiButton) entry;
            switch (button.id) {
                case SAVE:
                    button.enabled = VulkanProfiles.nameIsUsable(typed);
                    break;
                case LOAD:
                case DELETE:
                    button.enabled = this.selected != null;
                    break;
                case RENAME:
                    // Renaming to the name it already has is not a mistake worth
                    // an error message, but it is not an action either.
                    button.enabled = this.selected != null
                            && VulkanProfiles.nameIsUsable(typed)
                            && !typed.equals(this.selected);
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public void updateScreen() {
        this.nameField.updateCursorCounter();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (!button.enabled) {
            return;
        }
        String typed = this.nameField.getText().trim();
        switch (button.id) {
            case SAVE:
                if (VulkanProfiles.save(typed, this.mc)) {
                    this.selected = typed;
                    this.status = Lang.tr(Lang.UI, "Saved") + ": " + typed;
                } else {
                    this.status = Lang.tr(Lang.UI, "Could not write that profile");
                }
                break;
            case LOAD:
                if (VulkanProfiles.load(this.selected, this.mc)) {
                    this.status = Lang.tr(Lang.UI, "Loaded") + ": " + this.selected;
                } else {
                    this.status = Lang.tr(Lang.UI, "Could not read that profile");
                }
                break;
            case RENAME:
                if (VulkanProfiles.rename(this.selected, typed)) {
                    this.selected = typed;
                    this.status = Lang.tr(Lang.UI, "Renamed");
                } else {
                    this.status = Lang.tr(Lang.UI, "That name is taken");
                }
                break;
            case DELETE:
                VulkanProfiles.delete(this.selected);
                this.status = Lang.tr(Lang.UI, "Deleted") + ": " + this.selected;
                this.selected = null;
                break;
            case DONE:
                this.mc.displayGuiScreen(this.parent);
                return;
            default:
                return;
        }
        // The list changed under the buttons in every case above.
        this.names = VulkanProfiles.names();
        if (this.selected != null && !this.names.contains(this.selected)) {
            this.selected = null;
        }
        updateButtons();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, Lang.tr(Lang.UI, "Profiles"),
                this.width / 2, 14, 0xFFFFFF);
        this.drawCenteredString(this.fontRenderer,
                Lang.tr(Lang.UI, "Everything this mod owns, plus the game's own settings that decide the frame rate"),
                this.width / 2, 27, 0x808080);

        int left = listLeft();
        int right = left + LIST_WIDTH;
        int bottom = listBottom();
        drawRect(left - 2, LIST_TOP - 2, right + 2, bottom + 2, 0x60000000);

        if (this.names.isEmpty()) {
            this.drawCenteredString(this.fontRenderer,
                    Lang.tr(Lang.UI, "No profiles yet — type a name below and press Save As"),
                    this.width / 2, LIST_TOP + 10, 0x707070);
        }

        int y = LIST_TOP - this.scroll;
        for (String name : this.names) {
            if (y + ROW_HEIGHT > LIST_TOP && y < bottom) {
                boolean over = mouseX >= left && mouseX <= right && mouseY >= y && mouseY < y + ROW_HEIGHT
                        && mouseY >= LIST_TOP && mouseY < bottom;
                boolean chosen = name.equals(this.selected);
                if (chosen || over) {
                    drawRect(left, Math.max(y, LIST_TOP), right, Math.min(y + ROW_HEIGHT, bottom),
                            chosen ? 0x805A8CC8 : 0x40FFFFFF);
                }
                this.fontRenderer.drawString(name, left + 5, y + 3, chosen ? 0xFFFFFF : 0xD0D0D0);
            }
            y += ROW_HEIGHT;
        }

        this.nameField.drawTextBox();
        if (!this.status.isEmpty()) {
            this.fontRenderer.drawString(this.status, left, bottom + 6, 0xA0A0A0);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            this.scroll -= net.vulkanmod112.client.gui.Wheel.notches(wheel) * ROW_HEIGHT * 2;
            clampScroll();
        }
    }

    private void clampScroll() {
        int max = Math.max(0, this.names.size() * ROW_HEIGHT - (listBottom() - LIST_TOP));
        this.scroll = Math.max(0, Math.min(this.scroll, max));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        this.nameField.mouseClicked(mouseX, mouseY, mouseButton);
        int left = listLeft();
        if (mouseX >= left && mouseX <= left + LIST_WIDTH
                && mouseY >= LIST_TOP && mouseY < listBottom()) {
            int index = (mouseY - LIST_TOP + this.scroll) / ROW_HEIGHT;
            if (index >= 0 && index < this.names.size()) {
                this.selected = this.names.get(index);
                // Typing over the name is the common next thing, and having to
                // clear the field first would make the list feel read-only.
                this.nameField.setText(this.selected);
                updateButtons();
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.nameField.textboxKeyTyped(typedChar, keyCode)) {
            updateButtons();
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN) {
            // Enter on a name that exists loads it; on a new one, saves it.
            String typed = this.nameField.getText().trim();
            if (VulkanProfiles.exists(typed)) {
                this.selected = typed;
                updateButtons();
                actionPerformed(buttonById(LOAD));
            } else {
                actionPerformed(buttonById(SAVE));
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private GuiButton buttonById(int id) {
        for (Object entry : this.buttonList) {
            GuiButton button = (GuiButton) entry;
            if (button.id == id) {
                return button;
            }
        }
        return new GuiButton(-1, 0, 0, "");
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
