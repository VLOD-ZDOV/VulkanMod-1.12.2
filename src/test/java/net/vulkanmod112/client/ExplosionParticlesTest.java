package net.vulkanmod112.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The thinning rule for explosion particles, against what it claims to do.
 *
 * The claim is specific and the code is three lines, which is exactly the
 * combination that gets a bitwise operator wrong and ships: a hole in the
 * middle of a blast reads as a rendering fault rather than as a budget, and
 * nobody would look here for it.
 */
class ExplosionParticlesTest {

    @Test
    void noLimitKeepsEverything() {
        for (int i = 1; i <= 1000; i++) {
            assertTrue(ExplosionParticles.keep(i, 0), "request " + i);
            assertTrue(ExplosionParticles.keep(i, -1), "request " + i);
        }
    }

    @Test
    void everythingUpToTheLimitIsKeptWhole() {
        for (int i = 1; i <= 500; i++) {
            assertTrue(ExplosionParticles.keep(i, 500), "request " + i);
        }
    }

    /**
     * Past the limit, one in eight. Not "roughly one in eight": the count
     * below is what stops a change to the mask from passing unnoticed.
     */
    @Test
    void pastTheLimitExactlyOneInEightSurvives() {
        int limit = 100;
        int kept = 0;
        for (int i = limit + 1; i <= limit + 800; i++) {
            if (ExplosionParticles.keep(i, limit)) {
                kept++;
            }
        }
        assertEquals(100, kept, "one in eight of eight hundred");
    }

    /**
     * The thinning has to be spread out rather than bunched, which is the
     * whole reason it is a thinning and not a cut-off. Every window of eight
     * past the limit keeps one.
     */
    @Test
    void whatSurvivesIsSpreadRatherThanBunched() {
        int limit = 64;
        for (int start = limit + 1; start < limit + 400; start += 8) {
            int kept = 0;
            for (int i = start; i < start + 8; i++) {
                if (ExplosionParticles.keep(i, limit)) {
                    kept++;
                }
            }
            assertEquals(1, kept, "window starting at " + start);
        }
    }

    @Test
    void theFirstRequestPastTheLimitIsRefused() {
        // 101 is not a multiple of eight, so it goes; this is the boundary the
        // off-by-one would land on.
        assertTrue(ExplosionParticles.keep(100, 100));
        assertFalse(ExplosionParticles.keep(101, 100));
    }
}
