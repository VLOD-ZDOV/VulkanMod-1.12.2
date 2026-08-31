package net.vulkanmodnext.client.gui;

/** A titled group of rows, drawn with a heading above it. */
public final class VOptionBlock {

    public final String title;
    public final VOption[] options;

    /** Translated for display; {@link #title} stays English and keys off it. */
    public String heading() {
        return Lang.tr(Lang.GROUP, title);
    }

    public VOptionBlock(String title, VOption... options) {
        this.title = title;
        this.options = options;
    }
}
