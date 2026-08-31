package net.vulkanmodnext.client.gui;

/** Row cycling through a fixed set of values. */
public final class VCyclingOption extends VOption {

    public interface Access {
        int get();

        void set(int index);
    }

    /**
     * The choices, and where they came from.
     *
     * <h2>Why this is a type and not a boolean</h2>
     *
     * Some of these lists are English written in the source, which has to be
     * translated and has to appear in the language files. Others are found on
     * the machine — the names of the packs in your folders — which are
     * filenames, differ on every machine, and must never be translated or
     * written into a language file. Two hundred of them reached all eight of
     * those files once, from a folder of test packs on a development machine.
     *
     * <p>That was fixed with a trailing boolean, which fixes the instance and
     * not the class of mistake: the next list built from the filesystem is one
     * forgotten argument away from doing it again, and the argument is at the
     * end of a seven-parameter call. Here the question is asked where the
     * array is made, by whoever knows the answer, and there is no way to
     * construct the option without answering it.
     */
    public static final class Choices {
        private final String[] values;
        private final boolean translatable;

        private Choices(String[] values, boolean translatable) {
            this.values = values;
            this.translatable = translatable;
        }

        /** English written in the source: translated, and dumped for translators. */
        public static Choices of(String... english) {
            return new Choices(english, true);
        }

        /**
         * Names read off this machine: shown as they are, and never dumped.
         *
         * @param found whatever was there when the screen was built
         */
        public static Choices fromMachine(String[] found) {
            return new Choices(found, false);
        }
    }

    private final Choices choices;
    private final Access access;

    public VCyclingOption(String name, String tooltip, Cost cost, String appliesWhen,
                          Choices choices, Access access) {
        super(name, tooltip, cost, appliesWhen);
        this.choices = choices;
        this.access = access;
    }

    @Override
    public String valueText() {
        int index = access.get();
        if (index < 0 || index >= choices.values.length) {
            return "?";
        }
        return choices.translatable
                ? Lang.tr(Lang.VALUE, choices.values[index])
                : choices.values[index];
    }

    @Override
    public void activate(int direction, float fraction) {
        int length = choices.values.length;
        if (length == 0) {
            return;
        }
        int next = (access.get() + direction + length) % length;
        access.set(next);
    }

    /** Nothing, when the choices came off the machine rather than out of the source. */
    public String[] englishValues() {
        return choices.translatable ? choices.values : new String[0];
    }
}
