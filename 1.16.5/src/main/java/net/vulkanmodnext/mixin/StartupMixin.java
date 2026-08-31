package net.vulkanmodnext.mixin;

import net.minecraft.client.GameConfiguration;
import net.minecraft.client.Minecraft;
import net.vulkanmodnext.VulkanModNext;
import net.vulkanmodnext.client.VulkanStartup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brings Vulkan up once the game has a window and an OpenGL context.
 *
 * <h2>Why here and not in the mod's constructor</h2>
 *
 * The mod is constructed on a loading worker thread, where there is no OpenGL
 * context — and the choice of Vulkan card depends on asking OpenGL which card
 * <em>it</em> is on. Getting that wrong is not a preference between two working
 * options: sharing memory between two devices is refused outright, so the mod
 * would come up, find nothing wrong, and fail at the first shared image.
 *
 * <p>The end of the Minecraft constructor is the first moment both exist. The
 * window is open, {@code RenderSystem.setupDefaultState} has run, and this is
 * the render thread.
 *
 * <h2>This is also the proof that the patches attach at all</h2>
 *
 * It is the first mixin in the port, and it is deliberately one whose silence
 * would be noticed: if it does not attach, the log says Vulkan never started
 * rather than saying nothing. This project has shipped a release with an
 * injection that quietly did not apply, and the symptom was a dark torch.
 */
@Mixin(Minecraft.class)
public class StartupMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanmodnext$start(GameConfiguration configuration, CallbackInfo ci) {
        VulkanModNext.LOGGER.info("The game has a window; starting Vulkan");
        VulkanStartup.start();
    }
}
