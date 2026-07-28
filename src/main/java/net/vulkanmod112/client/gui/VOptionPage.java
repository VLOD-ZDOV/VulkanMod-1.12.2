package net.vulkanmod112.client.gui;

/** One tab of the settings screen. */
public final class VOptionPage {

    public final String name;
    public final VOptionBlock[] blocks;

    /** Translated for display; {@link #name} stays English and keys off it. */
    public String title() {
        return Lang.tr(Lang.PAGE, name);
    }

    public VOptionPage(String name, VOptionBlock... blocks) {
        this.name = name;
        this.blocks = blocks;
    }
}
