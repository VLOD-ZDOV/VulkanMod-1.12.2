package net.vulkanmodnext.client.gui;

/** Slider row: click or drag anywhere along it to set the value. */
public final class VRangeOption extends VOption {

    public interface Access {
        int get();

        void set(int value);
    }

    /**
     * A ceiling that can move while the screen is open, for the one row whose
     * limit another row unlocks. Without it the slider would keep the value it
     * was built with until the screen is closed and reopened, which reads as the
     * switch above it having done nothing.
     */
    public interface Ceiling {
        int max();
    }

    private final int min;
    private final int max;
    private final Ceiling ceiling;
    private final int step;
    private final String suffix;
    /** Text shown instead of the number at the minimum, e.g. "OFF". */
    private final String minText;
    private final Access access;

    public VRangeOption(String name, String tooltip, Cost cost, String appliesWhen,
                        int min, int max, int step, String suffix, String minText, Access access) {
        this(name, tooltip, cost, appliesWhen, min, max, null, step, suffix, minText, access);
    }

    public VRangeOption(String name, String tooltip, Cost cost, String appliesWhen,
                        int min, int max, Ceiling ceiling, int step, String suffix, String minText,
                        Access access) {
        super(name, tooltip, cost, appliesWhen);
        this.min = min;
        this.max = max;
        this.ceiling = ceiling;
        this.step = step;
        this.suffix = suffix;
        this.minText = minText;
        this.access = access;
    }

    private int max() {
        return ceiling == null ? max : ceiling.max();
    }

    @Override
    public String valueText() {
        int value = access.get();
        if (value == min && minText != null) {
            return Lang.tr(Lang.VALUE, minText);
        }
        return value + Lang.tr(Lang.UNIT, suffix, suffix);
    }

    @Override
    public float fill() {
        int top = max();
        float filled = (float) (access.get() - min) / (top - min);
        return filled < 0.0f ? 0.0f : (filled > 1.0f ? 1.0f : filled);
    }

    @Override
    public boolean draggable() {
        return true;
    }

    @Override
    public void activate(int direction, float fraction) {
        int top = max();
        float clamped = fraction < 0.0f ? 0.0f : (fraction > 1.0f ? 1.0f : fraction);
        int raw = Math.round(min + clamped * (top - min));
        int snapped = min + Math.round((raw - min) / (float) step) * step;
        access.set(snapped < min ? min : (snapped > top ? top : snapped));
    }

    public String englishSuffix() {
        return suffix;
    }

    public String englishMinText() {
        return minText;
    }
}
