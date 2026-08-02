package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Opens this mod's settings from the world, without going through two menus.
 *
 * The screen is reached from Video Settings, which means pause, options, video,
 * mod — four steps to move one slider and four back to see what it did. Every
 * setting here is judged by looking at the world, so the trip is the thing that
 * makes comparing two values tedious enough to stop doing.
 *
 * F6 by default: free in vanilla, and next to the keys that already show and
 * hide things about the world. Rebindable in Controls like any other, which
 * matters because this mod already claims one key and a pack can claim the
 * rest.
 */
public final class SettingsKey {

    private static final KeyBinding KEY = new KeyBinding(
            "key.vulkanmod112.settings", Keyboard.KEY_F6, "key.categories.vulkanmod112");

    private SettingsKey() {
    }

    public static void register() {
        ClientRegistry.registerKeyBinding(KEY);
    }

    public static KeyBinding keyBinding() {
        return KEY;
    }

    public static final class Handler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            // Only from the world. isPressed drains one queued press, so
            // asking while a screen is open would swallow presses meant for
            // whatever that screen does with them.
            // Settings changed while a screen was open are written here, once,
            // rather than on every step of a slider being dragged.
            VulkanConfig.flush();
            if (mc.currentScreen != null || mc.world == null) {
                return;
            }
            if (KEY.isPressed()) {
                mc.displayGuiScreen(new GuiVulkanSettings(null));
            }
        }
    }
}
