package net.vulkanmodnext.client.gui;

/**
 * Row that performs an action instead of holding a value, used for the
 * presets: they write several other options at once, so there is nothing to
 * display as "the current value" — a preset stops being true the moment any
 * option it touched is changed by hand.
 */
public final class VActionOption extends VOption {

    public interface Action {
        void run();
    }

    private final String buttonText;
    private final Action action;

    public VActionOption(String name, String tooltip, Cost cost, String buttonText, Action action) {
        super(name, tooltip, cost, null);
        this.buttonText = buttonText;
        this.action = action;
    }

    @Override
    public String valueText() {
        return Lang.tr(Lang.VALUE, buttonText);
    }

    @Override
    public void activate(int direction, float fraction) {
        action.run();
    }

    public String englishButtonText() {
        return buttonText;
    }
}
