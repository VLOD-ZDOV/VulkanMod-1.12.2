package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.WorldRenderer;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.math.vector.Matrix4f;
import net.vulkanmodnext.client.TerrainFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where the world's terrain is drawn, and where this mod will take it over.
 *
 * <p>The equivalent of 1.12.2's {@code renderBlockLayer}: called once per layer,
 * walking the chunks the game has decided are visible.
 *
 * <p>It hands the layer to Vulkan and cancels vanilla's draw only when Vulkan
 * says it drew it. Every path that is not a plain success — the setting off,
 * the renderer not up, no chunks, or anything thrown — leaves the method to run
 * as it always did. That is the whole safety of this hook: the failure mode is
 * "no gain", never "no world".
 */
@Mixin(WorldRenderer.class)
public abstract class TerrainDrawMixin {

    /**
     * The projection, taken where the game states it rather than read back.
     *
     * The first attempt asked OpenGL for {@code GL_PROJECTION_MATRIX}, which is
     * how the 1.12.2 mod does it and is wrong here: it came back NaN while the
     * model-view came back as the identity. Both readings were honest. On this
     * version the camera lives in the game's own {@code MatrixStack} and only
     * reaches OpenGL at the moment a buffer is drawn, so at the top of a layer
     * there is nothing on that stack to read yet.
     *
     * <p>The projection is a parameter of this very method, which makes the
     * readback not merely unreliable but unnecessary.
     */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void vulkanmodnext$frame(MatrixStack matrices, float partialTicks, long limitTime,
                                    boolean outline, ActiveRenderInfo camera,
                                    GameRenderer renderer, LightTexture lightmap,
                                    Matrix4f projection, CallbackInfo ci) {
        TerrainFrame.beginFrame(projection);
    }

    /**
     * After the layer has set up its OpenGL state, not before it.
     *
     * At the head of the method the state still belongs to whatever drew last —
     * the sky, or the previous layer. The composite that puts our frame into
     * the game's is ordinary OpenGL and is subject to all of it: the depth
     * test, the blend mode, which texture unit is bound. Cancelling before the
     * setup meant compositing under somebody else's state and, as far as the
     * screen was concerned, not compositing at all.
     */
    @Inject(method = "renderChunkLayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderType;setupRenderState()V",
            shift = At.Shift.AFTER), cancellable = true)
    private void vulkanmodnext$watchLayer(RenderType layer, MatrixStack matrices,
                                         double viewX, double viewY, double viewZ,
                                         CallbackInfo ci) {
        if (TerrainFrame.layer((WorldRenderer) (Object) this, layer, matrices,
                viewX, viewY, viewZ)) {
            // Vulkan drew it. Cancelling here also skips the layer's own
            // OpenGL state setup, which is right: nothing of vanilla's runs for
            // this layer at all, so there is no half-applied state left behind.
            ci.cancel();
        }
    }
}
