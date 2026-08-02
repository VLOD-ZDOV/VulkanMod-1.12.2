package net.vulkanmod112.client.gui;

/** Row cycling through a fixed set of values. */
public final class VCyclingOption extends VOption {

    public interface Access {
        int get();

        void set(int index);
    }

    private final String[] values;
    private final Access access;
    /**
     * True when the choices are found on the machine rather than written here.
     *
     * The pack picker lists whatever is in your folders, and those names are
     * not English text: they are filenames, they differ on every machine, and
     * they must not be translated or written into the language files. Two
     * hundred of them reached all eight of those files once, from a folder of
     * test packs on a development machine, before this existed.
     */
    private final boolean dynamic;

    public VCyclingOption(String name, String tooltip, Cost cost, String appliesWhen,
                          String[] values, Access access) {
        this(name, tooltip, cost, appliesWhen, values, access, false);
    }

    public VCyclingOption(String name, String tooltip, Cost cost, String appliesWhen,
                          String[] values, Access access, boolean dynamic) {
        super(name, tooltip, cost, appliesWhen);
        this.values = values;
        this.access = access;
        this.dynamic = dynamic;
    }

    @Override
    public String valueText() {
        int index = access.get();
        if (index < 0 || index >= values.length) {
            return "?";
        }
        return dynamic ? values[index] : Lang.tr(Lang.VALUE, values[index]);
    }

    @Override
    public void activate(int direction, float fraction) {
        int next = (access.get() + direction + values.length) % values.length;
        access.set(next);
    }

    /** Nothing, when the choices came off the machine rather than out of the source. */
    public String[] englishValues() {
        return dynamic ? new String[0] : values;
    }
}
