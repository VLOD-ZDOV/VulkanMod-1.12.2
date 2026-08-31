package net.vulkanmod112.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The table that decides whether a block state has already been dealt with in
 * the chunk being built.
 *
 * <p>It is worth testing on its own because a wrong answer here is silent in
 * exactly the direction that hurts: a state reported as "seen already" when it
 * was not means its sprites never reach the chunk's mask, and the symptom is a
 * texture somewhere in the world that has stopped moving — no exception, no
 * log line, and nothing on screen pointing at this file. The two ways to get
 * that wrong are a probe that stops early and a growth that loses entries, so
 * both are asked about directly.
 */
public class AnimatedSpritesBuildTest {

    @Test
    public void tellsTheFirstSightOfAStateFromTheRest() {
        AnimatedSprites.Build build = new AnimatedSprites.Build();
        Object stone = new Object();
        Object dirt = new Object();

        assertTrue(build.remember(stone), "first sight of a state has to resolve it");
        assertFalse(build.remember(stone), "the same state again must not");
        assertTrue(build.remember(dirt), "a different state must");
        assertFalse(build.remember(dirt));
        assertFalse(build.remember(stone));
        assertEquals(2, build.size());
    }

    /**
     * Past its half-full mark the table doubles and rehashes, and that is the
     * step where an entry can quietly go missing. Six hundred distinct things
     * is several doublings from the starting hundred and twenty-eight.
     */
    @Test
    public void keepsEverythingAcrossSeveralGrowths() {
        AnimatedSprites.Build build = new AnimatedSprites.Build();
        List<Object> states = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            Object state = new Object();
            states.add(state);
            assertTrue(build.remember(state),
                    "state " + i + " was new and should have been reported so");
        }
        assertEquals(600, build.size());
        for (int i = 0; i < states.size(); i++) {
            assertFalse(build.remember(states.get(i)),
                    "state " + i + " was forgotten by a growth");
        }
        assertEquals(600, build.size(), "nothing may be added by asking again");
    }

    /** A build is reused by its thread, so the reset has to leave nothing behind. */
    @Test
    public void resetForgetsTheChunkBefore() {
        AnimatedSprites.Build build = new AnimatedSprites.Build();
        Object stone = new Object();
        assertTrue(build.remember(stone));
        build.blocks = 4096L;
        build.distinct = 37L;

        build.reset();

        assertEquals(0, build.size());
        assertEquals(0L, build.blocks);
        assertEquals(0L, build.distinct);
        assertTrue(build.remember(stone),
                "the next chunk has to resolve the state for itself");
    }

    /**
     * Identity hashes of objects allocated one after another share their low
     * bits on some machines, and those are the bits the table indexes with. A
     * batch allocated in a tight loop is the shape that produces it.
     */
    @Test
    public void doesNotDegradeOnStatesAllocatedTogether() {
        AnimatedSprites.Build build = new AnimatedSprites.Build();
        Object[] batch = new Object[200];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = new Object();
        }
        for (Object state : batch) {
            assertTrue(build.remember(state));
        }
        assertEquals(batch.length, build.size());
        for (Object state : batch) {
            assertFalse(build.remember(state));
        }
    }
}
