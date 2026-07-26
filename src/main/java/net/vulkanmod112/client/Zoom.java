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
 * of view narrows; let go and it returns.
 *
 * Two details separate this from simply lowering the FOV.
 *
 * Mouse sensitivity is scaled by the same factor, because at a quarter of the
 * field of view an unchanged sensitivity makes aiming impossible — the view
 * sweeps four times as far across the screen for the same hand movement. The
 * original is restored as soon as the zoom is fully out, and the game is never
 * asked to save options while a temporary value is in place.
 *
 * The transition is eased off the wall clock rather than snapped. Zoom state
 * is decided on the 20 Hz tick, and interpolating anything on that clock is
 * precisely what makes a zoom look stepped; timing it in real seconds also
 * keeps the transition the same length at 30 frames a second and at 300.
 *
 * The key is registered through Forge, so it appears in Controls and can be
 * rebound like any other.
 */
public final class Zoom {

    private static final KeyBinding KEY = new KeyBinding(
            "key.vulkanmod112.zoom", Keyboard.KEY_C, "key.categories.vulkanmod112");
    /** Long enough to read as a movement, short enough not to feel sluggish. */
    private static final float ZOOM_SECONDS = 0.12f;

    private static boolean active;
    private static boolean overriding;
    private static float savedSensitivity;
    /** 0 = no zoom, 1 = fully zoomed. Advanced per frame. */
    private static float progress;
    private static long lastFrameNanos;

    private Zoom() {
    }

    public static void register() {
        ClientRegistry.registerKeyBinding(KEY);
    }

    /** Public so the settings screen can name the key it is describing. */
    public static KeyBinding keyBinding() {
        return KEY;
    }

    private static void beginOverride(Minecraft mc) {
        if (!overriding) {
            savedSensitivity = mc.gameSettings.mouseSensitivity;
            overriding = true;
        }
    }

    private static void endOverride(Minecraft mc) {
        if (overriding) {
            mc.gameSettings.mouseSensitivity = savedSensitivity;
            overriding = false;
        }
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
            active = VulkanConfig.isZoomEnabled()
                    && mc.world != null
                    && mc.currentScreen == null
                    && org.lwjgl.opengl.Display.isActive()
                    && KEY.isKeyDown();
            if (mc.world == null) {
                // Back at the main menu the FOV event stops firing, so the
                // ease below would never finish putting sensitivity back.
                active = false;
                progress = 0.0f;
                endOverride(mc);
            }
        }

        @SubscribeEvent
        public void onFov(EntityViewRenderEvent.FOVModifier event) {
            Minecraft mc = Minecraft.getMinecraft();
            long now = System.nanoTime();
            float elapsed = lastFrameNanos == 0 ? 0.0f : (now - lastFrameNanos) / 1.0e9f;
            lastFrameNanos = now;
            // A long stall — world load, alt-tab — must not teleport the zoom.
            if (elapsed > 0.25f) {
                elapsed = 0.25f;
            }

            float target = active ? 1.0f : 0.0f;
            float perSecond = elapsed / ZOOM_SECONDS;
            if (progress < target) {
                progress = Math.min(target, progress + perSecond);
            } else if (progress > target) {
                progress = Math.max(target, progress - perSecond);
                // Widening the view has to invalidate the visible-chunk list.
                // RenderGlobal rebuilds it only when the player moves or turns
                // — the field of view is not in that condition at all — so
                // zooming in, letting it rebuild against the narrow frustum,
                // then zooming out left the world drawn as the narrow cone it
                // was during the zoom until something else made the player
                // turn. Narrowing needs no such call: the existing list is
                // then a superset of what is visible.
                mc.renderGlobal.setDisplayListEntitiesDirty();
            }

            if (progress <= 0.0f) {
                endOverride(mc);
                return;
            }
            beginOverride(mc);
            // Interpolating the divisor rather than the angle keeps the
            // apparent speed even; sweeping linearly from 70 to 17.5 degrees
            // rushes at the start and crawls at the end.
            float divisor = 1.0f + (VulkanConfig.getZoomFactor() - 1.0f) * smoothstep(progress);
            mc.gameSettings.mouseSensitivity = savedSensitivity / divisor;
            event.setFOV(event.getFOV() / divisor);
        }

        private static float smoothstep(float t) {
            return t * t * (3.0f - 2.0f * t);
        }
    }
}
