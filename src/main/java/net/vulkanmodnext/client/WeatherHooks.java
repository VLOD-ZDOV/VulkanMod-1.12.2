package net.vulkanmodnext.client;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;
import net.vulkanmodnext.VulkanBridge;

/**
 * Rain and snow, taken at the point the game hands them to OpenGL.
 *
 * Unlike the particle path this does not replace the loop. {@code
 * renderRainSnow} is a hundred and fifty lines of column walking, biome
 * lookups, seeded randomness and Forge hooks, and reproducing it would mean
 * owning all of that and getting it wrong the first time a mod changed the
 * weather. What is replaced is two calls inside it: which sheet is bound, and
 * the draw.
 *
 * <p>The weather quads arrive already relative to the camera — the game sets a
 * translation on the builder before filling it — which is the same origin the
 * terrain matrix is built around, so they need no adjustment.
 */
public final class WeatherHooks {

    private static final String RAIN_PATH = "textures/environment/rain.png";
    private static final String SNOW_PATH = "textures/environment/snow.png";

    /** Which sheet the game bound last, as a sprite slot. */
    private static int slot = SpriteHooks.SLOT_RAIN;

    private WeatherHooks() {
    }

    public static void noteTexture(ResourceLocation location) {
        if (location == null) {
            return;
        }
        String path = location.getPath();
        if (SNOW_PATH.equals(path)) {
            slot = SpriteHooks.SLOT_SNOW;
        } else if (RAIN_PATH.equals(path)) {
            slot = SpriteHooks.SLOT_RAIN;
        }
    }

    /**
     * @return true when Vulkan took this batch and the game must not draw it
     */
    /**
     * What the weather pass actually handed over, counted where it happens.
     *
     * Rain went missing with the sky darkening correctly, which says the
     * strength reached the shading and stops saying anything after that. The
     * two possible stories need opposite fixes — the game built no quads, or it
     * built them and this path lost them — and nothing outside this method can
     * tell them apart. So both numbers are kept here: how often the game
     * reached its own draw call, and how many vertices it had by then.
     */
    private static long passes;
    private static long vertices;
    private static long toVulkan;

    /** Read and reset, for the diagnostics report. */
    public static String stats() {
        if (passes == 0) {
            return "weather: the game drew none since the last report";
        }
        String line = String.format(
                "weather: %d draws, %d vertices, %d of them taken by Vulkan (sheet slot %d)",
                passes, vertices, toVulkan, slot);
        passes = 0;
        vertices = 0;
        toVulkan = 0;
        return line;
    }

    public static boolean take(Tessellator tessellator) {
        passes++;
        vertices += tessellator.getBuffer().getVertexCount();
        VulkanBridge bridge = SpriteHooks.target(VulkanConfig.isVulkanWeather());
        if (bridge == null) {
            return false;
        }
        toVulkan++;
        BufferBuilder builder = tessellator.getBuffer();
        try {
            SpriteHooks.submit(bridge, builder, slot, SpriteHooks.WEATHER_CUTOFF);
            return true;
        } catch (Throwable t) {
            // Taken already or not building: either way the batch is gone and
            // handing it back to the game would throw a second time. Losing one
            // frame of rain is the cheapest end to this.
            SpriteHooks.fail("Drawing weather", t);
            return true;
        }
    }
}
