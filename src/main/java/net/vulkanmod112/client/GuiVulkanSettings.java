package net.vulkanmod112.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.vulkanmod112.VulkanBridge;
import net.vulkanmod112.VulkanLoader;
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
    private static final int PAGE_BUTTON_BASE = 300;

    private static final int MARGIN = 10;
    private static final int TAB_WIDTH = 100;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 2;
    private static final int BLOCK_TITLE_HEIGHT = 14;
    private static final int TOP = 42;
    private static final int BOTTOM_GAP = 36;
    /** Below this the tooltip panel is dropped and descriptions follow the cursor. */
    private static final int TOOLTIP_MIN_WIDTH = 150;

    private final GuiScreen parent;
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
    private VOption dragging;

    public GuiVulkanSettings(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        if (this.pages == null) {
            this.pages = VulkanOptions.buildPages(this.mc);
        }

        this.listTop = TOP;
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
                    TAB_WIDTH, ROW_HEIGHT, this.pages[i].name));
        }
        this.buttonList.add(new GuiButton(DONE, this.width / 2 - 100, this.height - 27, 200, 20,
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
        } else if (button.id >= PAGE_BUTTON_BASE) {
            this.currentPage = button.id - PAGE_BUTTON_BASE;
            this.scroll = 0;
            updateTabHighlight();
            clampScroll();
        }
    }

    // ------------------------------------------------------------------
    // Layout and drawing
    // ------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.hovered = null;

        this.drawCenteredString(this.fontRenderer, "VulkanMod112", this.width / 2, 12, 0xFFFFFF);
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        String gpu = bridge == null ? "Vulkan unavailable" : bridge.gpuSummary();
        this.drawCenteredString(this.fontRenderer, gpu, this.width / 2, 24, 0x909090);

        drawRect(this.listLeft - 2, this.listTop - 2, this.listLeft + this.listWidth + 2,
                this.listBottom + 2, 0x50000000);

        boolean insideList = mouseX >= this.listLeft && mouseX <= this.listLeft + this.listWidth
                && mouseY >= this.listTop && mouseY <= this.listBottom;
        int y = this.listTop - this.scroll;
        for (VOptionBlock block : this.pages[this.currentPage].blocks) {
            if (y + BLOCK_TITLE_HEIGHT > this.listTop && y < this.listBottom) {
                this.fontRenderer.drawString(block.title, this.listLeft + 2, y + 3, 0xC0C0C0);
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
        if (this.hovered.impact() != VOption.Impact.NONE) {
            y += 6;
            this.fontRenderer.drawString(this.hovered.impact().label, left + 4, y,
                    this.hovered.impact().color);
            y += 12;
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
            this.scroll -= wheel / 120 * (ROW_HEIGHT + ROW_GAP) * 2;
            clampScroll();
        }
    }

    private void clampScroll() {
        int max = Math.max(0, this.contentHeight - (this.listBottom - this.listTop));
        this.scroll = Math.max(0, Math.min(this.scroll, max));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
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
        for (VOptionBlock block : this.pages[this.currentPage].blocks) {
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

    @Override
    public void onGuiClosed() {
        this.mc.gameSettings.saveOptions();
    }
}
