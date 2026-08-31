package net.vulkanmodnext.client.gui;

/** On/off row. */
public final class VSwitchOption extends VOption {

    public interface Access {
        boolean get();

        void set(boolean value);
    }

    private final Access access;

    public VSwitchOption(String name, String tooltip, Cost cost, String appliesWhen, Access access) {
        super(name, tooltip, cost, appliesWhen);
        this.access = access;
    }

    @Override
    public String valueText() {
        return access.get() ? "ON" : "OFF";
    }

    @Override
    public void activate(int direction, float fraction) {
        access.set(!access.get());
    }
}
