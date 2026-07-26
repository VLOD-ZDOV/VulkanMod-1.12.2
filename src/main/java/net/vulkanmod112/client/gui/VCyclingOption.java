package net.vulkanmod112.client.gui;

/** Row cycling through a fixed set of values. */
public final class VCyclingOption extends VOption {

    public interface Access {
        int get();

        void set(int index);
    }

    private final String[] values;
    private final Access access;

    public VCyclingOption(String name, String tooltip, Impact impact, String appliesWhen,
                          String[] values, Access access) {
        super(name, tooltip, impact, appliesWhen);
        this.values = values;
        this.access = access;
    }

    @Override
    public String valueText() {
        int index = access.get();
        return index >= 0 && index < values.length ? values[index] : "?";
    }

    @Override
    public void activate(int direction, float fraction) {
        int next = (access.get() + direction + values.length) % values.length;
        access.set(next);
    }
}
