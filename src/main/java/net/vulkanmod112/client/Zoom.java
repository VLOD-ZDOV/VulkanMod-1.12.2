package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Hold-to-zoom, the way OptiFine does it: press and hold the key and the field
 * of view narrows; let go and it snaps back.
 *
 * Two details make the difference between this and simply lowering the FOV.
 * Mouse sensitivity is scaled by the same amount, because at a quarter of the
 * field of view an unchanged sensitivity makes aiming impossible — the view
 * sweeps four times as far across the screen for the same hand movement. And
 * the original sensitivity is never written to options.txt: it is restored the
 * moment the key comes up, and the game is never asked to save while a
 * temporary value is in place.
 *
 * The key is registered through Forge, so it appears in Controls and can be
 * rebound like any other.
 */
public final class Zoom {

    private static final KeyBinding KEY = new KeyBinding(
            "key.vulkanmod112.zoom", Keyboard.KEY_C, "key.categories.vulkanmod112");

    private static boolean active;
    private static float savedSensitivity;

    private Zoom() {
    }

    public static void register() {
        ClientRegistry.registerKeyBinding(KEY);
    }

    /** Public so the settings screen can name the key it is describing. */
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
            // No zooming out of a menu, a loading screen or an unfocused
            // window: in all three the key would read as stuck down, and the
            // clamped sensitivity would outlive the zoom. Losing focus is the
            // one that matters, because alt-tabbing away and quitting from
            // there is how a temporary value would end up saved for good.
            boolean wanted = VulkanConfig.isZoomEnabled()
                    && mc.world != null
                    && mc.currentScreen == null
                    && org.lwjgl.opengl.Display.isActive()
                    && KEY.isKeyDown();
            if (wanted == active) {
                return;
            }
            active = wanted;
            if (active) {
                savedSensitivity = mc.gameSettings.mouseSensitivity;
                mc.gameSettings.mouseSensitivity = savedSensitivity / VulkanConfig.getZoomFactor();
            } else {
                mc.gameSettings.mouseSensitivity = savedSensitivity;
            }
        }

        @SubscribeEvent
        public void onFov(EntityViewRenderEvent.FOVModifier event) {
            if (active) {
                event.setFOV(event.getFOV() / VulkanConfig.getZoomFactor());
            }
        }
    }
}
