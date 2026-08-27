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

    /**
     * The second alpha is above the first, and the release is above both.
     *
     * The middle of those three is what the comparison got wrong before this
     * test existed: splitting the whole string on dots turned the "2" of
     * "alpha.2" into a fourth number, and a fourth number beat the absence of
     * one — so 0.10.0 stopped being above the alpha that was leading to it, and
     * every alpha after the first became the dead end the first one had been.
     */
    @Test
    void alphasAreOrderedAmongThemselves() {
        assertTrue(UpdateCheck.isNewer("0.10.0-alpha.2", "0.10.0-alpha"));
        assertFalse(UpdateCheck.isNewer("0.10.0-alpha", "0.10.0-alpha.2"));
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.10.0-alpha.2"));
        assertFalse(UpdateCheck.isNewer("0.10.0-alpha.2", "0.10.0"));
        assertFalse(UpdateCheck.isNewer("0.10.0-alpha.2", "0.10.0-alpha.2"));
    }

    /** A word is on the way to the version later than a number is. */
    @Test
    void betaIsAboveAlpha() {
        assertTrue(UpdateCheck.isNewer("0.10.0-beta", "0.10.0-alpha.9"));
        assertFalse(UpdateCheck.isNewer("0.10.0-alpha.9", "0.10.0-beta"));
        assertTrue(UpdateCheck.isNewer("0.10.0-beta.2", "0.10.0-beta"));
    }

    /** A suffix on one version never outranks a higher set of numbers. */
    @Test
    void theNumbersStillDecideFirst() {
        assertTrue(UpdateCheck.isNewer("0.11.0-alpha", "0.10.0"));
        assertFalse(UpdateCheck.isNewer("0.10.0", "0.11.0-alpha"));
        assertTrue(UpdateCheck.isNewer("0.10.0-alpha", "0.9.9"));
    }
}
