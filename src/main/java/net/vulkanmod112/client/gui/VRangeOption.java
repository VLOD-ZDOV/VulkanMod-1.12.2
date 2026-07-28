package net.vulkanmod112.client.gui;

/** Slider row: click or drag anywhere along it to set the value. */
public final class VRangeOption extends VOption {

    public interface Access {
        int get();

        void set(int value);
    }

    private final int min;
    private final int max;
    private final int step;
    private final String suffix;
    /** Text shown instead of the number at the minimum, e.g. "OFF". */
    private final String minText;
    private final Access access;

    public VRangeOption(String name, String tooltip, Cost cost, String appliesWhen,
                        int min, int max, int step, String suffix, String minText, Access access) {
        super(name, tooltip, cost, appliesWhen);
        this.min = min;
        this.max = max;
        this.step = step;
        this.suffix = suffix;
        this.minText = minText;
        this.access = access;
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
        return (float) (access.get() - min) / (max - min);
    }

    @Override
    public boolean draggable() {
        return true;
    }

    @Override
    public void activate(int direction, float fraction) {
        float clamped = fraction < 0.0f ? 0.0f : (fraction > 1.0f ? 1.0f : fraction);
        int raw = Math.round(min + clamped * (max - min));
        int snapped = min + Math.round((raw - min) / (float) step) * step;
        access.set(snapped < min ? min : (snapped > max ? max : snapped));
    }

    public String englishSuffix() {
        return suffix;
    }

    public String englishMinText() {
        return minText;
    }
}
