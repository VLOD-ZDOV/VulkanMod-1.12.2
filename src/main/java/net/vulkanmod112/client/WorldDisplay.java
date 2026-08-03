package net.vulkanmod112.client;

/**
 * What time it looks like, and what the weather looks like, on this screen only.
 *
 * <h2>What this is not</h2>
 *
 * Not a cheat and not a command. Nothing here is sent anywhere, nothing is
 * stored in the world, and no other player sees any of it. The server still
 * decides when it is night and whether it is raining — mobs still burn at
 * dawn, crops still grow, a storm still charges a creeper. This changes only
 * what the numbers look like to the code that draws the sky, and it changes
 * them on the way out of the world object rather than in it.
 *
 * That is the whole design rule, and it is what makes the feature safe to have
 * at all: the world is never written to. Switch any of this off and the next
 * frame is exactly what it would have been.
 *
 * <h2>Why it is wanted</h2>
 *
 * Every effect in this mod is judged by looking at the world, and half of them
 * only exist at particular times of day. A shadow is worth looking at in the
 * morning and invisible at noon; water reflects the sky at dusk and a flat grey
 * at midnight; bloom is a different thing in a cave than under the sun. Judging
 * a slider meant waiting for the world to come round to the right hour, which
 * is a long time to hold an impression of what the last value looked like.
 */
public final class WorldDisplay {

    /** Time modes, in the order the setting cycles through them. */
    public static final int TIME_OFF = 0;
    public static final int TIME_FROZEN = 1;
    public static final int TIME_FIXED = 2;

    /** Weather modes, likewise. */
    public static final int WEATHER_OFF = 0;
    public static final int WEATHER_CLEAR = 1;
    public static final int WEATHER_RAIN = 2;
    public static final int WEATHER_STORM = 3;

    /**
     * The moment the freeze caught, or -1 when nothing is being held.
     *
     * Taken at the first frame the mode is on rather than at the click, because
     * the click happens on a screen where the world behind is already still and
     * "freeze it here" means the world as last seen.
     */
    private static long frozenAt = -1L;

    private WorldDisplay() {
    }

    /**
     * What the world should say the time is.
     *
     * @param actual what it really is, read from the world info rather than
     *               from the getter this feeds, so nothing calls itself
     * @return the time to show
     */
    public static long worldTime(long actual) {
        int mode = VulkanConfig.getTimeControl();
        if (mode == TIME_FROZEN) {
            if (frozenAt < 0L) {
                frozenAt = actual;
            }
            return frozenAt;
        }
        frozenAt = -1L;
        if (mode != TIME_FIXED) {
            return actual;
        }
        // The day is kept and only the hour replaced. The day number is what
        // the moon phase is counted from, so throwing it away would move the
        // moon every time this slider did — and the moon is one of the things
        // people come to this screen to look at.
        long day = Math.floorDiv(actual, 24000L);
        return day * 24000L + hourToTicks(VulkanConfig.getTimeOfDay());
    }

    /**
     * Hour of the day as a clock reads it, turned into what the game counts.
     *
     * The game's zero is sunrise, not midnight — 0 is six in the morning, 6000
     * is noon, 18000 is midnight. A slider that said "0" for dawn would be
     * asking the player to learn that, so the slider is a clock and this is
     * where the two meet.
     */
    private static long hourToTicks(int hour) {
        return ((hour + 18) % 24) * 1000L;
    }

    /** Text for the slider: the same hour, as a clock face. */
    public static String hourText(int hour) {
        return (hour < 10 ? "0" : "") + hour + ":00";
    }

    public static float rain(float actual) {
        switch (VulkanConfig.getWeatherControl()) {
            case WEATHER_CLEAR:
                return 0.0f;
            case WEATHER_RAIN:
            case WEATHER_STORM:
                return 1.0f;
            default:
                return actual;
        }
    }

    /**
     * Vanilla multiplies thunder by rain, so a storm needs both and rain needs
     * the thunder half taken away — otherwise choosing rain over a real storm
     * would leave the sky flashing.
     */
    public static float thunder(float actual) {
        switch (VulkanConfig.getWeatherControl()) {
            case WEATHER_CLEAR:
            case WEATHER_RAIN:
                return 0.0f;
            case WEATHER_STORM:
                return 1.0f;
            default:
                return actual;
        }
    }

    public static boolean overridingTime() {
        return VulkanConfig.getTimeControl() != TIME_OFF;
    }

    public static boolean overridingWeather() {
        return VulkanConfig.getWeatherControl() != WEATHER_OFF;
    }

    /** Dropped on world change: a time held in one world means nothing in the next. */
    public static void forget() {
        frozenAt = -1L;
    }
}
