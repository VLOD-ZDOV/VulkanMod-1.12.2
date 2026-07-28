package net.vulkanmod112.client.gui;

/**
 * One row in the settings list.
 *
 * Modelled on VulkanMod's option widgets: a name, the current value, a
 * tooltip and what the setting costs. Rows draw and handle input themselves
 * rather than being GuiButtons, because the list scrolls and range rows behave
 * like sliders.
 */
public abstract class VOption {

    /** One resource's share of an option's cost. */
    public enum Level {
        NONE("none", 0x707070, 0),
        LOW("low", 0x55C355, 1),
        MEDIUM("medium", 0xE0C040, 2),
        HIGH("high", 0xE06060, 3);

        public final String label;
        public final int color;
        /** Filled segments in the bar drawn next to the name. */
        public final int bars;

        Level(String label, int color, int bars) {
            this.label = label;
            this.color = color;
            this.bars = bars;
        }
    }

    /**
     * What an option costs, split by resource.
     *
     * A single "impact" rating hid the thing people actually need to know:
     * whether a setting will help them at all. Lowering entity distance costs
     * nothing on the GPU and everything on the CPU, so it is useless to
     * someone whose GPU is the bottleneck — and the reverse for render scale.
     */
    public static final class Cost {

        public static final Cost FREE = of(Level.NONE, Level.NONE, Level.NONE);

        public final Level cpu;
        public final Level gpu;
        public final Level vram;

        private Cost(Level cpu, Level gpu, Level vram) {
            this.cpu = cpu;
            this.gpu = gpu;
            this.vram = vram;
        }

        public static Cost of(Level cpu, Level gpu, Level vram) {
            return new Cost(cpu, gpu, vram);
        }

        public static Cost cpu(Level level) {
            return of(level, Level.NONE, Level.NONE);
        }

        public static Cost gpu(Level level) {
            return of(Level.NONE, level, Level.NONE);
        }

        public boolean isFree() {
            return cpu == Level.NONE && gpu == Level.NONE && vram == Level.NONE;
        }
    }

    private final String name;
    private final String tooltip;
    private final Cost cost;
    /** Shown in the tooltip when the value only takes effect later. */
    private final String appliesWhen;

    protected VOption(String name, String tooltip, Cost cost, String appliesWhen) {
        this.name = name;
        this.tooltip = tooltip;
        this.cost = cost;
        this.appliesWhen = appliesWhen;
    }

    public String name() {
        return Lang.tr(Lang.OPTION, name, name);
    }

    public String tooltip() {
        return Lang.tr(Lang.TOOLTIP, name, tooltip);
    }

    /** The untranslated name, which is what every key here is derived from. */
    public String englishName() {
        return name;
    }

    public String englishTooltip() {
        return tooltip;
    }

    public String englishAppliesWhen() {
        return appliesWhen;
    }

    public Cost cost() {
        return cost;
    }

    public String appliesWhen() {
        return Lang.tr(Lang.APPLIES, name, appliesWhen);
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
