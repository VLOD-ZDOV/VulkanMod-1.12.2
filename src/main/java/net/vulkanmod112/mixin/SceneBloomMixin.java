package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import net.vulkanmod112.client.TerrainHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one moment in the frame when everything in the world has been drawn.
 *
 * This mod finishes its terrain and glues it into the game's frame before the
 * game has drawn a single entity, which is early enough that any effect over
 * the whole picture would have been over a picture with no creatures, no
 * particles, no weather and no water in it. The frame does hold all of those
 * eventually, and the profiler names the moment: the section called "hand" is
 * opened once the world is finished and before the arm and whatever it is
 * holding are drawn over it.
 *
 * Injecting on that string rather than on a line number is deliberate. It is
 * the game's own label for the boundary, it survives the method being changed
 * around it, and it is what any other mod reading this frame would recognise.
 *
 * Nothing is drawn here when bloom is off, and nothing is drawn twice: the
 * renderer only has a glow waiting if it made one this frame.
 */
@Mixin(EntityRenderer.class)
public abstract class SceneBloomMixin {

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING",
                    target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V",
                    args = "ldc=hand"))
    private void vulkanmod112$sceneBloom(int pass, float partialTicks, long finishTimeNano,
                                         CallbackInfo ci) {
        TerrainHooks.applySceneBloom();
    }
}
