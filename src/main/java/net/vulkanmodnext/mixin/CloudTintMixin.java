package net.vulkanmodnext.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets the clouds take the colour of the sky they are hanging in.
 *
 * <h2>What vanilla does</h2>
 *
 * {@code World.getCloudColour} returns white, darkened for night and for rain,
 * and that is all it knows. At sunset the sky below the clouds turns orange and
 * the clouds stay the colour they were at noon — which is the one thing about a
 * vanilla sky that reads as wrong rather than as plain, because a cloud is lit
 * by the same sun as everything else and is the last thing that sun reaches.
 *
 * <h2>What this does instead</h2>
 *
 * Mixes in two colours the game has already worked out for this exact moment:
 * the sky colour, which carries the biome and the weather, and the sunrise and
 * sunset colours, which the game computes for the band around the horizon and
 * which are non-null only while there is a sunset to have. Nothing is invented
 * — this is the game's own palette applied to a surface it was never applied
 * to, which is why it cannot disagree with the sky behind it.
 *
 * <h2>Why the redirect names WorldClient</h2>
 *
 * {@code getCloudColour} is declared on {@code World}, but the field it is
 * called on is {@code RenderGlobal.world}, declared {@code WorldClient} — so
 * the invoke the compiler wrote names {@code WorldClient}, and a redirect
 * aimed at {@code World} matches nothing at all. That exact mistake cost this
 * mod a release with a hook that was never attached and said nothing about it.
 * It would now stop the game at startup instead, which is why every injection
 * in this mod is required to find its target.
 *
 * <h2>What it costs</h2>
 *
 * Two calls a frame, on the two lines that already ask for the cloud colour
 * once each. The volumetric clouds a shader pack draws are a different thing
 * entirely and a large one; this is the half of that look which is free.
 */
@Mixin(RenderGlobal.class)
public class CloudTintMixin {

    @Redirect(
            method = {"renderClouds", "renderCloudsFancy"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/WorldClient;"
                            + "getCloudColour(F)Lnet/minecraft/util/math/Vec3d;"))
    private Vec3d vulkanmodnext$tintClouds(WorldClient world, float partialTicks) {
        Vec3d colour = world.getCloudColour(partialTicks);
        int strength = VulkanConfig.getCloudTint();
        if (strength <= 0) {
            return colour;
        }
        float amount = strength / 100.0f;
        try {
            return tint(world, colour, amount, partialTicks);
        } catch (Throwable t) {
            // A mod with its own sky provider, or a world without a render
            // view entity for a frame. The vanilla colour is always an answer.
            return colour;
        }
    }

    private static Vec3d tint(WorldClient world, Vec3d colour, float amount, float partialTicks) {
        double r = colour.x;
        double g = colour.y;
        double b = colour.z;
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view != null) {
            // The sky's own colour, which already carries the biome, the time
            // and the weather. Half weight: clouds are lit by the sky rather
            // than made of it, and at full weight they stop being white at
            // noon, which is the one thing about them nobody wants changed.
            Vec3d sky = world.getSkyColor(view, partialTicks);
            double w = amount * 0.5;
            r += (sky.x - r) * w;
            g += (sky.y - g) * w;
            b += (sky.z - b) * w;
        }
        // And the sunset, which is the whole point. The game computes this for
        // the band it paints around the horizon and returns null whenever
        // there is no sunrise or sunset happening, so this costs nothing for
        // most of the day and needs no clock of its own. The fourth component
        // is how much of it the sky itself is using, so following it is what
        // keeps the clouds turning at the same rate the horizon does.
        float angle = world.getCelestialAngle(partialTicks);
        float[] sunset = world.provider.calcSunriseSunsetColors(angle, partialTicks);
        if (sunset != null) {
            double w = amount * sunset[3];
            r += (sunset[0] - r) * w;
            g += (sunset[1] - g) * w;
            b += (sunset[2] - b) * w;
        }
        return new Vec3d(r, g, b);
    }
}
