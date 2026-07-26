package net.vulkanmod112.client.gui;

/** A titled group of rows, drawn with a heading above it. */
public final class VOptionBlock {

    public final String title;
    public final VOption[] options;

    public VOptionBlock(String title, VOption... options) {
        this.title = title;
        this.options = options;
    }
}
