package net.vulkanmod112.client.gui;

/**
 * One row in the settings list.
 *
 * Modelled on VulkanMod's option widgets: a name, the current value, a
 * tooltip and how much the setting is worth in frames. Rows draw and handle
 * input themselves rather than being GuiButtons, because the list scrolls and
 * range rows behave like sliders.
 */
public abstract class VOption {

    /** How much this option is worth in performance, shown next to the tooltip. */
    public enum Impact {
        LOW("Low impact", 0x55C355),
        MEDIUM("Medium impact", 0xE0C040),
        HIGH("High impact", 0xE06060),
        NONE("", 0xA0A0A0);

        public final String label;
        public final int color;

        Impact(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    private final String name;
    private final String tooltip;
    private final Impact impact;
    /** Shown in the tooltip when the value only takes effect later. */
    private final String appliesWhen;

    protected VOption(String name, String tooltip, Impact impact, String appliesWhen) {
        this.name = name;
        this.tooltip = tooltip;
        this.impact = impact;
        this.appliesWhen = appliesWhen;
    }

    public String name() {
        return name;
    }

    public String tooltip() {
        return tooltip;
    }

    public Impact impact() {
        return impact;
    }

    public String appliesWhen() {
        return appliesWhen;
    }

    /** Text drawn on the right of the row. */
    public abstract String valueText();

    /** 0..1 fill of the row background; ranges draw a slider, others do not. */
    public float fill() {
        return -1.0f;
    }

    /**
     * @param direction  +1 forward, -1 backward (right mouse button)
     * @param fraction   horizontal click position across the row, for ranges
     */
    public abstract void activate(int direction, float fraction);

    /** True while the row should keep following the mouse after a press. */
    public boolean draggable() {
        return false;
    }

}
