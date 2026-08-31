package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Marks the diagnostics log with the things that decide what a measurement
 * means: where the camera is, and what the player just did to put it there.
 *
 * <h2>Why this is here</h2>
 *
 * A frame breakdown is a number per second with no idea what was on screen. Two
 * flights over different ground produce two sets of numbers that cannot be
 * compared, and the only record of the difference was the person's memory of
 * where they had flown. That is not a hypothetical: it happened, and it cost a
 * measurement.
 *
 * So every snapshot now carries the camera's position and heading, and the
 * events that move it are written into the same file as they happen — the
 * command that teleported, the world that loaded. A route can then be matched
 * afterwards by reading the log rather than by remembering the flight.
 *
 * <h2>What is not recorded</h2>
 *
 * Chat is not. Only lines beginning with a slash are, because those are the
 * ones that move the camera or change the world, and ordinary chat on a server
 * is other people's conversation.
 */
@SideOnly(Side.CLIENT)
public final class SessionLog {

    private SessionLog() {
    }

    /**
     * One line of camera state for a snapshot. Position, heading and the block
     * the eye is in, which is what the visibility search seeds itself from.
     */
    public static String cameraLine() {
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc == null ? null : mc.getRenderViewEntity();
        if (view == null) {
            return "camera: none";
        }
        return String.format(
                "camera: %.1f %.1f %.1f, facing %s (yaw %.0f pitch %.0f), chunk %d %d, dim %d",
                view.posX, view.posY, view.posZ,
                view.getHorizontalFacing(), wrap(view.rotationYaw), view.rotationPitch,
                net.minecraft.util.math.MathHelper.floor(view.posX) >> 4,
                net.minecraft.util.math.MathHelper.floor(view.posZ) >> 4,
                view.dimension);
    }

    /** Yaw as the F3 screen shows it, so the two can be compared by eye. */
    private static float wrap(float yaw) {
        float wrapped = yaw % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        } else if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }

    public static final class Handler {

        /**
         * Commands only. A teleport is the single most useful line there is for
         * reading a session back — it says exactly where the next few hundred
         * snapshots were taken, and it is the one way to put two runs on the
         * same ground on purpose.
         */
        @SubscribeEvent
        public void onClientChat(ClientChatEvent event) {
            String message = event.getMessage();
            if (message == null || !message.startsWith("/")) {
                return;
            }
            Diagnostics.flushNow("command " + message + " | " + cameraLine());
        }

        /**
         * The one place per frame where the chat window is certainly there and
         * certainly not being drawn. The renderer cannot say anything from
         * inside the frame that failed.
         */
        @SubscribeEvent
        public void onClientTick(net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent event) {
            if (event.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) {
                RenderNotice.flushToChat();
                // The restart notice went out from the fallback handler and
                // nowhere else, and that handler exists only where the renderer
                // never started — which is every session except the one the
                // message is for. Somebody switching ray tracing on in a
                // working game queued a line that nothing ever printed.
                RenderNotice.flushRestartToChat();
                LangDump.onceIfAsked();
                // The automated route, which needs a tick rather than a frame:
                // it moves the camera, and a camera moved from inside a frame
                // is moved halfway through the picture it is being read for.
                Flight.tick();
                // Asked on a clock as well as when a setting moves, because
                // not everything this answers is a setting. Whether another
                // mod has taken the sky is a property of the world, and a
                // world is loaded long after the switch was set — checking
                // only on change means the one session that needed the
                // sentence is the one that never printed it. It builds a
                // short string once a second and says nothing unless the
                // answer changed.
                if (++healthTicks >= 20) {
                    healthTicks = 0;
                    SettingsHealth.check();
                }
            }
        }

        /**
         * The end of a frame, which is the one moment a picture of it can be
         * taken: the world and the interface are both drawn and the buffers
         * have not been swapped away yet.
         */
        @SubscribeEvent
        public void onRenderTick(net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent
                                         event) {
            if (event.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) {
                Flight.onFrameEnd();
            }
        }

        /** Ticks since the last {@link SettingsHealth#check()}. */
        private int healthTicks;

        @SubscribeEvent
        public void onWorldLoad(WorldEvent.Load event) {
            if (event.getWorld() == null || !event.getWorld().isRemote) {
                return;
            }
            RenderNotice.reset();
            // A held hour belongs to the world it was held in. Carrying it into
            // the next one would freeze a new world at a time it never had.
            WorldDisplay.forget();
            Diagnostics.flushNow("world loaded, dimension " + event.getWorld().provider.getDimension()
                    + ", render distance " + Minecraft.getMinecraft().gameSettings.renderDistanceChunks);
        }

        @SubscribeEvent
        public void onWorldUnload(WorldEvent.Unload event) {
            if (event.getWorld() == null || !event.getWorld().isRemote) {
                return;
            }
            Diagnostics.flushNow("world unloaded, dimension "
                    + event.getWorld().provider.getDimension());
        }
    }
}
