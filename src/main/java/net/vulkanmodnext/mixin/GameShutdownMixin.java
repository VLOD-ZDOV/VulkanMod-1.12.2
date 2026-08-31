package net.vulkanmodnext.mixin;

import net.minecraft.client.Minecraft;
import net.vulkanmodnext.client.TerrainHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the Vulkan side a chance to close before the window is taken away.
 *
 * Until this existed nothing ever tore it down. The device, the images shared
 * with OpenGL and the semaphores imported into it were simply abandoned when
 * the process ended — which usually works, because a process exiting is a
 * driver's problem to clean up after, and it is exactly the sort of thing that
 * usually working is not good enough for. Two drivers hold references to the
 * same memory here, and whichever of them is torn down second is looking at
 * objects the first one has already released.
 *
 * The moment chosen is the head of the game's own shutdown, which is on the
 * thread that draws and before {@code Display.destroy()} takes the OpenGL
 * context with it. That order is the point: OpenGL has to still be alive to
 * give back what it imported.
 */
@Mixin(Minecraft.class)
public abstract class GameShutdownMixin {

    @Inject(method = "shutdownMinecraftApplet", at = @At("HEAD"))
    private void vulkanmodnext$closeVulkan(CallbackInfo ci) {
        TerrainHooks.shutdown();
    }
}
