package net.vulkanmod112.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comparison behind "a newer build exists", which has two traps in it.
 *
 * The first is that versions are not text: 0.10.0 is above 0.9.0 and sorts
 * below it in every alphabet there is. The second arrived with the first alpha
 * — a build published on the way to a version carries the same three numbers as
 * the version itself, so without a rule for the suffix the people most worth
 * telling about the finished release are the only ones who never hear about it.
 */
class UpdateCheckTest {

    @Test
    void tenIsAboveNine() {
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.9.0"));
        assertFalse(UpdateCheck.isNewer("0.9.0", "0.10.0"));
    }

    @Test
    void theSameVersionIsNotNewer() {
        assertFalse(UpdateCheck.isNewer("0.10.0", "0.10.0"));
        assertFalse(UpdateCheck.isNewer("0.9.1", "0.9.1"));
    }

    @Test
    void aMissingPartCountsAsZero() {
        assertTrue(UpdateCheck.isNewer("0.10.1", "0.10"));
        assertFalse(UpdateCheck.isNewer("0.10", "0.10.0"));
    }

    /** The release is above the alpha it was published to collect faults for. */
    @Test
    void theVersionIsAboveTheBuildsLeadingToIt() {
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.10.0-alpha"));
        assertFalse(UpdateCheck.isNewer("0.10.0-alpha", "0.10.0"));
        assertFalse(UpdateCheck.isNewer("0.10.0-alpha", "0.10.0-alpha"));
    }

    /** And a later version is above it whichever of them carries a suffix. */
    @Test
    void numbersStillDecideFirst() {
        assertTrue(UpdateCheck.isNewer("0.11.0", "0.10.0-alpha"));
        assertFalse(UpdateCheck.isNewer("0.9.0", "0.10.0-alpha"));
        assertTrue(UpdateCheck.isNewer("0.10.1-rc1", "0.10.0"));
    }
}
