package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

import java.util.List;

/**
 * Steps through the saved settings profiles without opening anything.
 *
 * <h2>Why a key at all</h2>
 *
 * Profiles exist to be compared: a heavy one for looking at the world and a
 * light one for playing it, and the whole question a profile answers is "which
 * of these two do I actually prefer here". Answering it through the settings
 * screen means the world is hidden behind a menu at the moment of the
 * comparison, which is the moment it needs to be visible. This was asked for
 * when profiles were first proposed and never built.
 *
 * <h2>What it does</h2>
 *
 * One key, one direction: the next profile in the list, wrapping at the end,
 * and the name said in chat because a settings change that shows nothing on a
 * screen with no settings on it is indistinguishable from a key that did not
 * work. Unbound by default — this mod already claims two keys, and a third
 * taken from a pack that wanted it is worse than one the player binds when
 * they find they want it.
 *
 * <h2>What it does not do</h2>
 *
 * There is no "previous". A second binding for a list most people keep two
 * entries in would be a second thing to explain; wrapping round is the same
 * journey backwards.
 */
public final class ProfileKey {

    /**
     * Zero is "not bound", which Minecraft's own controls screen shows as
     * NONE and treats as never pressed. That is the default on purpose: see
     * the class comment.
     */
    private static final KeyBinding KEY = new KeyBinding(
            "key.vulkanmod112.nextProfile", 0, "key.categories.vulkanmod112");

    private ProfileKey() {
    }

    public static void register() {
        ClientRegistry.registerKeyBinding(KEY);
    }

    public static KeyBinding keyBinding() {
        return KEY;
    }

    /**
     * The name of the profile loaded last, so the step knows where it is.
     *
     * Not read back from the settings: two profiles can hold identical values,
     * and working out which one is current by comparing them would land on
     * whichever was saved first and then never move. Empty means the list has
     * not been walked yet this session, and the first press takes the first
     * entry — which is also what happens after a profile is renamed out from
     * under this.
     */
    private static String current = "";

    /** Called by the settings screen so both ways of loading agree. */
    public static void loaded(String name) {
        current = name == null ? "" : name;
    }

    /**
     * Loads the profile after the current one. Says what happened, always.
     *
     * @param mc the client, for the chat line and for applying video settings
     */
    static void step(Minecraft mc) {
        List<String> names = VulkanProfiles.names();
        if (names.isEmpty()) {
            say(mc, "No settings profiles saved yet");
            return;
        }
        int index = names.indexOf(current);
        // -1 when nothing has been loaded yet or the name has since been
        // deleted or renamed; both mean "start at the beginning", which the
        // arithmetic below gives for free.
        String next = names.get((index + 1) % names.size());
        if (VulkanProfiles.load(next, mc)) {
            current = next;
            say(mc, "Settings profile: " + next
                    + " (" + (names.indexOf(next) + 1) + " of " + names.size() + ")");
        } else {
            say(mc, "Could not load the settings profile " + next);
        }
    }

    private static void say(Minecraft mc, String message) {
        if (mc.player != null) {
            mc.player.sendMessage(new TextComponentString("[VulkanMod112] " + message));
        }
    }
}
