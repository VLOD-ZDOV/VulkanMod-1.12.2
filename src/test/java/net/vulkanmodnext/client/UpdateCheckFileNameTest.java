package net.vulkanmodnext.client;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a version out of a published file name.
 *
 * <p>The name gained a game version in the middle of it at 0.11.0, when one
 * repository started building for two versions of Minecraft. That is the sort
 * of change that keeps working until it does not: a pattern loose enough to
 * skip "something that looks like a version" reads
 * {@code vulkanmodnext-1.12.2-1.0.0.jar} as version 1.12.2, and then quietly
 * offers everybody an upgrade to a version that came out in 2017.
 *
 * <p>Nothing in the game exercises this — it only runs against whatever a web
 * service happens to be serving that day — so it is worth a test of its own.
 */
public class UpdateCheckFileNameTest {

    private static String versionIn(String fileName) {
        Matcher found = UpdateCheck.FILE.matcher(fileName);
        return found.find() ? found.group(1) : null;
    }

    @Test
    public void readsTheCurrentShape() {
        assertEquals("0.11.0-alpha.1", versionIn("vulkanmodnext-1.12.2-0.11.0-alpha.1.jar"));
        assertEquals("0.11.0", versionIn("vulkanmodnext-1.12.2-0.11.0.jar"));
        assertEquals("0.2.0", versionIn("vulkanmodnext-1.16.5-0.2.0.jar"));
    }

    /** Everything published up to 0.10.0 had no game version in its name. */
    @Test
    public void readsTheOldShapesStill() {
        assertEquals("0.10.0-alpha.3", versionIn("vulkanmod112-0.10.0-alpha.3.jar"));
        assertEquals("0.9.1", versionIn("vulkanmod112-0.9.1.jar"));
        assertEquals("0.10.0-alpha.3", versionIn("vulkanmodnext-0.10.0-alpha.3.jar"));
    }

    /**
     * The failure this pattern was rewritten to avoid, asked directly: the mod's
     * own number will reach 1 one day, and the two must still not be confused.
     */
    @Test
    public void doesNotMistakeTheGameVersionForTheMods() {
        assertEquals("1.0.0", versionIn("vulkanmodnext-1.12.2-1.0.0.jar"));
        assertEquals("2.0.0-beta.1", versionIn("vulkanmodnext-1.16.5-2.0.0-beta.1.jar"));
    }

    @Test
    public void ignoresSomebodyElsesFiles() {
        assertFalse(UpdateCheck.FILE.matcher("mixinbooter-10.7.jar").find());
        assertFalse(UpdateCheck.FILE.matcher("VulkanMod-0.5.0.jar").find());
        assertTrue(UpdateCheck.FILE.matcher("vulkanmodnext-1.12.2-0.11.0.jar").find());
    }
}
