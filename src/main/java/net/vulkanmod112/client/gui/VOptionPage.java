package net.vulkanmod112.client.gui;

/** One tab of the settings screen. */
public final class VOptionPage {

    public final String name;
    public final VOptionBlock[] blocks;

    public VOptionPage(String name, VOptionBlock... blocks) {
        this.name = name;
        this.blocks = blocks;
    }
}
