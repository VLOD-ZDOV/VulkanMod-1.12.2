package net.vulkanmod112.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.vulkanmod112.client.gui.Lang;
import net.vulkanmod112.VulkanBridge;
import net.vulkanmod112.VulkanLoader;
import net.vulkanmod112.client.gui.OptionSearch;
import net.vulkanmod112.client.gui.VOption;
import net.vulkanmod112.client.gui.VOptionBlock;
import net.vulkanmod112.client.gui.VOptionPage;
import org.lwjgl.input.Mouse;

import java.io.IOException;

/**
 * Settings screen laid out like VulkanMod's own: page tabs down the left, a
 * scrolling list of grouped options in the middle, and a panel on the right
 * describing whatever the cursor is over, including how much it is worth in
 * frames.
 *
 * Rows are drawn and hit-tested by hand instead of being GuiButtons: the list
 * scrolls, and range rows have to behave like sliders under a drag.
 */
public final class GuiVulkanSettings extends GuiScreen {

    private static final int DONE = 200;
    private static final int RESET = 201;
    private static final int PAGE_BUTTON_BASE = 300;

    private static final int MARGIN = 10;
    private static final int TAB_WIDTH = 100;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 2;
    private static final int BLOCK_TITLE_HEIGHT = 14;
    /**
     * Room for the memory bar and its line of numbers between the GPU name and
     * the list. Fourteen pixels of list given up for it, deliberately: the
     * settings that cost video memory are spread over three tabs, and the only
     * place their total can be seen at once is above all of them.
     */
    private static final int TOP = 58;
    private static final int BAR_TOP = 34;
    private static final int BAR_HEIGHT = 8;
    private static final int BOTTOM_GAP = 36;
    /** Below this the tooltip panel is dropped and descriptions follow the cursor. */
    private static final int TOOLTIP_MIN_WIDTH = 150;

    private final GuiScreen parent;
    private static final int SEARCH_FIELD = 9001;
    private VOptionPage[] pages;
    private int currentPage;

    private int listLeft;
    private int listWidth;
    private int listTop;
    private int listBottom;
    private int tooltipLeft;
    private int tooltipWidth;

    private int scroll;
    private int contentHeight;
    private VOption hovered;
    /**
     * Type here and the list stops being a page and becomes an answer.
     *
     * Held as a page rather than as a mode: everything below already knows how
     * to draw, scroll, hover and click one, and a second path through all of
     * that is a second path to keep in step.
     */
    private GuiTextField search;
    private VOptionPage searchPage;
    private VOption dragging;

    public GuiVulkanSettings(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        if (this.pages == null) {
            this.pages = VulkanOptions.buildPages(this.mc);
            vulkanmod112$dumpLang();
        }

        this.listTop = TOP + 20;
        this.listBottom = this.height - BOTTOM_GAP;
        this.listLeft = MARGIN + TAB_WIDTH + 8;

        int available = this.width - this.listLeft - MARGIN;
        int wantedTooltip = Math.min(180, available / 3);
        if (wantedTooltip >= TOOLTIP_MIN_WIDTH) {
            this.tooltipWidth = wantedTooltip;
            this.listWidth = Math.min(available - wantedTooltip - 8, 420);
            this.tooltipLeft = this.listLeft + this.listWidth + 8;
        } else {
            this.tooltipWidth = 0;
            this.listWidth = Math.min(available, 420);
            this.tooltipLeft = 0;
        }

        for (int i = 0; i < this.pages.length; i++) {
            this.buttonList.add(new GuiButton(PAGE_BUTTON_BASE + i, MARGIN, TOP + i * (ROW_HEIGHT + 2),
                    TAB_WIDTH, ROW_HEIGHT, this.pages[i].title()));
        }
        String typed = this.search == null ? "" : this.search.getText();
        this.search = new GuiTextField(SEARCH_FIELD, this.fontRenderer,
                this.listLeft, TOP - 1, this.listWidth, 16);
        this.search.setMaxStringLength(48);
        this.search.setText(typed);
        // Focused from the start: this screen is opened to change something,
        // and the row wanted is more often found by name than by tab. The
        // arrow keys and the mouse still work, so nothing is taken away by it.
        this.search.setFocused(true);
        refreshSearch();
        this.buttonList.add(new GuiButton(RESET, this.width / 2 - 154, this.height - 27, 100, 20,
                Lang.tr(Lang.UI, "Reset")));
        this.buttonList.add(new GuiButton(DONE, this.width / 2 - 50, this.height - 27, 150, 20,
                I18n.format("gui.done")));
        updateTabHighlight();
        clampScroll();
    }

    private void updateTabHighlight() {
        for (Object entry : this.buttonList) {
            GuiButton button = (GuiButton) entry;
            if (button.id >= PAGE_BUTTON_BASE) {
                // Vanilla buttons have no selected state; the current tab is
                // the disabled one, which reads as "pressed".
                button.enabled = button.id - PAGE_BUTTON_BASE != this.currentPage;
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (!button.enabled) {
            return;
        }
        if (button.id == DONE) {
            this.mc.gameSettings.saveOptions();
            this.mc.displayGuiScreen(this.parent);
        } else if (button.id == RESET) {
            // Only this mod's settings: Minecraft's own are not ours to undo,
            // and someone reaching for Reset here is not asking for their
            // render distance back.
            VulkanConfig.resetToDefaults();
        } else if (button.id >= PAGE_BUTTON_BASE) {
            this.currentPage = button.id - PAGE_BUTTON_BASE;
            // Picking a tab is a way of saying the search is over. Leaving the
            // results up while the tab beside them looks pressed is two
            // different answers to "where am I" on one screen.
            this.search.setText("");
            refreshSearch();
            this.scroll = 0;
            updateTabHighlight();
            clampScroll();
        }
    }

    // ------------------------------------------------------------------
    // Layout and drawing
    // ------------------------------------------------------------------

    /** The page being shown: the search's answer while there is one. */
    private VOptionPage activePage() {
        return this.searchPage != null ? this.searchPage : this.pages[this.currentPage];
    }

    /**
     * Rebuilt on every keystroke rather than debounced.
     *
     * It walks a hundred rows and their descriptions, which is a few thousand
     * short strings — less work than one frame of this screen already does to
     * draw itself, and a search that lags behind the typing is worse than no
     * search at all.
     */
    private void refreshSearch() {
        VOptionPage was = this.searchPage;
        this.searchPage = OptionSearch.page(this.pages, this.search.getText());
        if (was != this.searchPage) {
            this.scroll = 0;
        }
        for (Object button : this.buttonList) {
            if (button instanceof GuiButton) {
                GuiButton b = (GuiButton) button;
                if (b.id >= PAGE_BUTTON_BASE && b.id < PAGE_BUTTON_BASE + this.pages.length) {
                    b.enabled = this.searchPage != null || b.id - PAGE_BUTTON_BASE != this.currentPage;
                }
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.hovered = null;
        // Everything drawn from here on rocks, if it rocks at all. The
        // background stays put on purpose: a moving background reads as the
        // window itself sliding, and the joke is the page nodding inside it.
        SixSeven.begin();

        this.drawCenteredString(this.fontRenderer, "VulkanMod112", this.width / 2, 12, 0xFFFFFF);
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        String gpu = bridge == null ? Lang.tr(Lang.UI, "Vulkan unavailable") : bridge.gpuSummary();
        int vram = VulkanOptions.vramMegabytes();
        if (vram > 0) {
            gpu = gpu + " — " + vram + " MiB";
        }
        this.drawCenteredString(this.fontRenderer, gpu, this.width / 2, 24, 0x909090);
        drawVramBar();

        drawRect(this.listLeft - 2, this.listTop - 2, this.listLeft + this.listWidth + 2,
                this.listBottom + 2, 0x50000000);
        this.search.drawTextBox();
        if (this.search.getText().isEmpty()) {
            this.fontRenderer.drawString(Lang.tr(Lang.UI, "Search settings"),
                    this.listLeft + 5, TOP + 3, 0x707070);
        }

        boolean insideList = mouseX >= this.listLeft && mouseX <= this.listLeft + this.listWidth
                && mouseY >= this.listTop && mouseY <= this.listBottom;
        int y = this.listTop - this.scroll;
        for (VOptionBlock block : activePage().blocks) {
            if (y + BLOCK_TITLE_HEIGHT > this.listTop && y < this.listBottom) {
                this.fontRenderer.drawString(block.heading(), this.listLeft + 2, y + 3, 0xC0C0C0);
            }
            y += BLOCK_TITLE_HEIGHT;
            for (VOption option : block.options) {
                if (y + ROW_HEIGHT > this.listTop && y < this.listBottom) {
                    boolean over = insideList && mouseY >= y && mouseY < y + ROW_HEIGHT;
                    if (over) {
                        this.hovered = option;
                    }
                    drawRow(option, y, over);
                }
                y += ROW_HEIGHT + ROW_GAP;
            }
            y += 4;
        }
        this.contentHeight = y + this.scroll - this.listTop;

        drawTooltipPanel(mouseX, mouseY);
        drawScrollbar();

        super.drawScreen(mouseX, mouseY, partialTicks);
        SixSeven.end();
    }

    /**
     * What the settings are asking of the card, against what the card has.
     *
     * The numbers behind it are two different kinds of thing and the bar says
     * which is which rather than blending them into one confident total: the
     * screen-sized targets are arithmetic, the world is a measurement of this
     * player's world, and with no world loaded there is nothing to measure and
     * the bar says so instead of inventing a figure.
     */
    private void drawVramBar() {
        int available = VramEstimate.availableMiB();
        if (available <= 0) {
            return;
        }
        VramEstimate.Breakdown estimate = VramEstimate.current();
        int used = estimate.totalMiB();
        int left = this.listLeft;
        int right = this.listLeft + this.listWidth;
        int bottom = BAR_TOP + BAR_HEIGHT;

        drawRect(left, BAR_TOP, right, bottom, 0x60000000);
        double fraction = Math.min(1.0, used / (double) available);
        int filled = left + (int) ((right - left) * fraction);
        // Three colours rather than a gradient: the question a player has here
        // is not "how full" but "am I about to run out", and that has three
        // answers.
        int colour = fraction < 0.6 ? 0xFF4C9E5A : fraction < 0.85 ? 0xFFC8A03C : 0xFFC85A4C;
        drawRect(left, BAR_TOP, filled, bottom, colour);

        String label = String.format("%s of %s used by these settings", size(used), size(available));
        if (!estimate.geometryKnown) {
            label = size(used) + " of " + size(available) + " — world not loaded, terrain not counted";
        }
        this.fontRenderer.drawString(label, left, bottom + 3, 0xA0A0A0);
    }

    private static String size(int mib) {
        return mib >= 1024
                ? String.format("%.1f GiB", mib / 1024.0)
                : mib + " MiB";
    }

    private void drawRow(VOption option, int y, boolean hover) {
        int left = this.listLeft;
        int right = left + this.listWidth;
        int top = Math.max(y, this.listTop);
        int bottom = Math.min(y + ROW_HEIGHT, this.listBottom);
        if (bottom <= top) {
            return;
        }
        drawRect(left, top, right, bottom, hover ? 0x60FFFFFF : 0x40000000);

        float fill = option.fill();
        if (fill >= 0.0f) {
            drawRect(left, top, left + (int) ((right - left) * fill), bottom, 0x805A8CC8);
        }

        int textY = y + (ROW_HEIGHT - 8) / 2;
        if (textY >= this.listTop && textY + 8 <= this.listBottom) {
            this.fontRenderer.drawString(option.name(), left + 5, textY, 0xFFFFFF);
            String value = option.valueText();
            this.fontRenderer.drawString(value, right - 5 - this.fontRenderer.getStringWidth(value),
                    textY, 0xD0D0D0);
        }
    }

    private void drawTooltipPanel(int mouseX, int mouseY) {
        if (this.hovered == null) {
            return;
        }
        if (this.tooltipWidth == 0) {
            java.util.List<String> lines = new java.util.ArrayList<String>();
            lines.add(this.hovered.name());
            lines.addAll(this.fontRenderer.listFormattedStringToWidth(this.hovered.tooltip(), 220));
            VOption.Cost cost = this.hovered.cost();
            if (!cost.isFree()) {
                lines.add(costText("CPU", cost.cpu) + "   " + costText("GPU", cost.gpu)
                        + "   " + costText("VRAM", cost.vram));
            }
            drawHoveringText(lines, mouseX, mouseY);
            return;
        }
        int left = this.tooltipLeft;
        int right = left + this.tooltipWidth;
        drawRect(left - 2, this.listTop - 2, right + 2, this.listBottom + 2, 0x50000000);

        int y = this.listTop + 4;
        this.fontRenderer.drawString(this.hovered.name(), left + 4, y, 0xFFFFFF);
        y += 14;
        for (Object line : this.fontRenderer.listFormattedStringToWidth(this.hovered.tooltip(),
                this.tooltipWidth - 8)) {
            this.fontRenderer.drawString((String) line, left + 4, y, 0xB0B0B0);
            y += 10;
        }
        VOption.Cost cost = this.hovered.cost();
        if (!cost.isFree()) {
            y += 8;
            this.fontRenderer.drawString(Lang.tr(Lang.UI, "Cost"), left + 4, y, 0x808080);
            y += 12;
            y = drawCostRow(left, y, Lang.tr(Lang.UI, "CPU"), cost.cpu);
            y = drawCostRow(left, y, Lang.tr(Lang.UI, "GPU"), cost.gpu);
            y = drawCostRow(left, y, Lang.tr(Lang.UI, "VRAM"), cost.vram);
        }
        if (this.hovered.appliesWhen() != null) {
            y += 4;
            for (Object line : this.fontRenderer.listFormattedStringToWidth(
                    this.hovered.appliesWhen(), this.tooltipWidth - 8)) {
                this.fontRenderer.drawString((String) line, left + 4, y, 0xE0B060);
                y += 10;
            }
        }
    }

    /**
     * One resource of an option's cost: its name, then three segments filled
     * to the level. Three short bars read at a glance in a way three sentences
     * do not, and the whole point is to see at a glance whether a setting
     * touches the resource you are short of.
     */
    private int drawCostRow(int left, int y, String label, VOption.Level level) {
        this.fontRenderer.drawString(label, left + 4, y, 0xA0A0A0);
        int barsLeft = left + 4 + 30;
        for (int i = 0; i < 3; i++) {
            int x = barsLeft + i * 10;
            boolean filled = i < level.bars;
            drawRect(x, y, x + 8, y + 7, filled ? 0xFF000000 | level.color : 0x40FFFFFF);
        }
        this.fontRenderer.drawString(costLabel(level), barsLeft + 34, y, level.color);
        return y + 11;
    }

    private void drawScrollbar() {
        int viewHeight = this.listBottom - this.listTop;
        if (this.contentHeight <= viewHeight) {
            return;
        }
        int barLeft = this.listLeft + this.listWidth + 2;
        int barHeight = Math.max(20, viewHeight * viewHeight / this.contentHeight);
        int barTop = this.listTop + (viewHeight - barHeight) * this.scroll
                / Math.max(1, this.contentHeight - viewHeight);
        drawRect(barLeft, barTop, barLeft + 3, barTop + barHeight, 0x80FFFFFF);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            this.scroll -= net.vulkanmod112.client.gui.Wheel.notches(wheel)
                    * (ROW_HEIGHT + ROW_GAP) * 2;
            clampScroll();
        }
    }

    private void clampScroll() {
        int max = Math.max(0, this.contentHeight - (this.listBottom - this.listTop));
        this.scroll = Math.max(0, Math.min(this.scroll, max));
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.search.updateCursorCounter();
    }

    /**
     * Escape clears the search before it closes the screen.
     *
     * Somebody who has typed a word and cannot see the row they wanted presses
     * Escape to get rid of the word, not to leave — and having the screen shut
     * on them costs the whole trip back through Options and Video Settings.
     */
    @Override
    protected void keyTyped(char typed, int key) throws IOException {
        if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE && !this.search.getText().isEmpty()) {
            this.search.setText("");
            refreshSearch();
            return;
        }
        if (this.search.textboxKeyTyped(typed, key)) {
            refreshSearch();
            return;
        }
        super.keyTyped(typed, key);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        this.search.mouseClicked(mouseX, mouseY, mouseButton);
        VOption option = optionAt(mouseX, mouseY);
        if (option != null) {
            option.activate(mouseButton == 1 ? -1 : 1,
                    (mouseX - this.listLeft) / (float) this.listWidth);
            this.mc.getSoundHandler().playSound(
                    net.minecraft.client.audio.PositionedSoundRecord.getMasterRecord(
                            net.minecraft.init.SoundEvents.UI_BUTTON_CLICK, 1.0f));
            if (option.draggable()) {
                this.dragging = option;
            }
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int mouseButton, long timeSinceLastClick) {
        if (this.dragging != null) {
            this.dragging.activate(1, (mouseX - this.listLeft) / (float) this.listWidth);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.dragging = null;
        super.mouseReleased(mouseX, mouseY, state);
    }

    /** The row under the cursor, or null outside the list. */
    private VOption optionAt(int mouseX, int mouseY) {
        if (mouseX < this.listLeft || mouseX > this.listLeft + this.listWidth
                || mouseY < this.listTop || mouseY > this.listBottom) {
            return null;
        }
        int y = this.listTop - this.scroll;
        for (VOptionBlock block : activePage().blocks) {
            y += BLOCK_TITLE_HEIGHT;
            for (VOption option : block.options) {
                if (mouseY >= y && mouseY < y + ROW_HEIGHT) {
                    return option;
                }
                y += ROW_HEIGHT + ROW_GAP;
            }
            y += 4;
        }
        return null;
    }

    /**
     * The same two calls vanilla's video settings screen makes on the way out.
     *
     * {@code onGuiClosed} on the game's settings is the second of them and it is
     * not optional. Forge defers the model reload the mipmap slider needs to
     * exactly this point — MC-64581, "very laggy mipmap slider" — and a screen
     * that changes that setting without calling it either never applies the new
     * mipmap level, or has to reload resources itself, which is what this screen
     * used to do and what crashed the game.
     */
    @Override
    public void onGuiClosed() {
        // Everything moved on this screen reaches the file here, in one write.
        VulkanConfig.flush();
        this.mc.gameSettings.saveOptions();
        this.mc.gameSettings.onGuiClosed();
    }

    /** "low", "high" and friends, translated. */
    private static String costLabel(VOption.Level level) {
        return Lang.tr(Lang.COST, level.label);
    }

    /**
     * Writes the English language file from the screen that was just built.
     *
     * Generated rather than maintained by hand: every key here is derived from
     * the English text in the source, so a rename would orphan its translations
     * with nothing to notice it. Regenerating and diffing is how that is caught.
     */
    private void vulkanmod112$dumpLang() {
        if (!Boolean.getBoolean("vulkanmod112.dumpLang")) {
            return;
        }
        try {
            java.io.File file = new java.io.File(this.mc.gameDir, "logs/vulkanmod112-en_us.lang");
            java.io.Writer writer = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file), "UTF-8");
            try {
                writer.write(Lang.dump(this.pages));
            } finally {
                writer.close();
            }
            net.vulkanmod112.VulkanMod112.LOGGER.info("Language keys written to {}", file);
        } catch (Throwable e) {
            // Never let a development aid take the settings screen down.
            net.vulkanmod112.VulkanMod112.LOGGER.warn("Could not write language keys", e);
        }
    }

    /** One cost for the hovering tooltip, carrying the same colour as the bars. */
    private static String costText(String resource, VOption.Level level) {
        return "\u00a77" + Lang.tr(Lang.UI, resource) + ": " + level.format
                + costLabel(level) + "\u00a7r";
    }
}
