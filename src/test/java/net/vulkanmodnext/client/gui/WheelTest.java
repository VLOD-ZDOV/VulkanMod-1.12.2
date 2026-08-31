package net.vulkanmodnext.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The first bug anybody outside this project reported, kept from coming back.
 *
 * A Cleanroom instance answers the mouse wheel in detents where LWJGL 2 answers
 * in the units Windows uses, so the division by 120 floored every notch to
 * nothing and no list in this mod scrolled. Both readings have to work, and
 * neither library can be asked which one it is.
 */
class WheelTest {

    @Test
    void windowsUnitsAreDivided() {
        assertEquals(1, Wheel.notches(120));
        assertEquals(-1, Wheel.notches(-120));
        assertEquals(3, Wheel.notches(360));
        assertEquals(-2, Wheel.notches(-240));
    }

    /** The reported bug: one notch arrives as 1 and used to become 0. */
    @Test
    void detentsAreTakenAsWholeNotches() {
        assertEquals(1, Wheel.notches(1));
        assertEquals(-1, Wheel.notches(-1));
    }

    /**
     * Anything under a detent still moves the list by one. A wheel that reports
     * fractions of a Windows detent is the case the 120 exists for, and
     * ignoring it would be the same bug in a smaller font.
     */
    @Test
    void partOfADetentStillCounts() {
        assertEquals(1, Wheel.notches(40));
        assertEquals(-1, Wheel.notches(-40));
        assertEquals(1, Wheel.notches(119));
        assertEquals(-1, Wheel.notches(-119));
    }

    @Test
    void nothingIsNothing() {
        assertEquals(0, Wheel.notches(0));
    }

    /**
     * A fast spin batches detents into one event, and the count has to survive
     * that — taking the sign alone would make a hard scroll slower than a
     * gentle one.
     */
    @Test
    void aFastSpinKeepsItsMagnitude() {
        assertEquals(10, Wheel.notches(1200));
        assertEquals(-10, Wheel.notches(-1200));
    }
}
