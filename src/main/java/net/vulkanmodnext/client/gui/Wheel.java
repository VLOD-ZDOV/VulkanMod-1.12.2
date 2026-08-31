package net.vulkanmodnext.client.gui;

/**
 * How many notches the mouse wheel just turned, whichever library is answering.
 *
 * <h2>Why this is not a division</h2>
 *
 * LWJGL 2 reports the wheel the way Windows does: one detent is 120, and the
 * number exists so that a finer wheel can report a fraction of one. Dividing by
 * 120 is therefore the obvious reading, and it is what both settings screens
 * did.
 *
 * It returns zero on a Cleanroom instance. That loader replaces LWJGL 2 with a
 * compatibility layer over LWJGL 3, and GLFW reports the wheel in detents
 * rather than in Windows units — so one notch arrives as 1, the division floors
 * to 0, and no slider in this mod scrolls at all. Reported as the first issue
 * this project received, already diagnosed by the person reporting it.
 *
 * So the magnitude is used where there is one to use and the sign where there
 * is not. A step is a step either way, and neither library is wrong: they are
 * answering in different units, and only one of them was being asked.
 *
 * <h2>Why not simply take the sign</h2>
 *
 * A wheel spun hard can batch several detents into one event, and under LWJGL 2
 * that arrives as 240 or 360. Taking the sign would throw the rest away and
 * make a fast scroll feel slower than a slow one.
 */
public final class Wheel {

    /** What one detent reports under LWJGL 2, and under Windows before it. */
    private static final int WINDOWS_DETENT = 120;

    private Wheel() {
    }

    public static int notches(int delta) {
        if (delta == 0) {
            return 0;
        }
        int whole = delta / WINDOWS_DETENT;
        return whole != 0 ? whole : Integer.signum(delta);
    }
}
