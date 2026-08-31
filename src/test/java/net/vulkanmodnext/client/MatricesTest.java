package net.vulkanmodnext.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic every vertex of the world goes through, against known answers.
 *
 * Written because this is the shape of bug this project keeps paying for: a
 * matrix that is wrong produces a picture, not an error, and the picture is
 * plausible enough to be argued about for a day. A refraction offset was
 * inverted once and found by reading the formula rather than by looking at the
 * screen; the depth range was wrong once and looked like a driver fault.
 */
class MatricesTest {

    /** Column-major identity. */
    private static float[] identity() {
        return new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1,
        };
    }

    /**
     * A perspective projection built the way the game's own is, so the planes
     * read back below have a right answer that is known in advance.
     */
    private static float[] perspective(float fovRadians, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(fovRadians / 2.0));
        float[] m = new float[16];
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1.0f;
        m[14] = 2.0f * far * near / (near - far);
        return m;
    }

    @Test
    void multiplyingByIdentityChangesNothing() {
        float[] a = perspective(1.2f, 16.0f / 9.0f, 0.2f, 1448.0f);
        float[] out = new float[16];
        Matrices.multiply(a, identity(), out);
        for (int i = 0; i < 16; i++) {
            assertEquals(a[i], out[i], 1.0e-6f, "element " + i);
        }
        Matrices.multiply(identity(), a, out);
        for (int i = 0; i < 16; i++) {
            assertEquals(a[i], out[i], 1.0e-6f, "element " + i);
        }
    }

    /**
     * The order matters and the convention decides it: with column vectors,
     * {@code multiply(projection, modelview)} must apply the modelview first.
     * Getting this backwards is a world drawn from a plausible wrong place.
     */
    @Test
    void multiplyAppliesTheRightHandOperandFirst() {
        // Translate by (10, 0, 0), then scale everything by two.
        float[] translate = identity();
        translate[12] = 10.0f;
        float[] scale = identity();
        scale[0] = 2.0f;
        scale[5] = 2.0f;
        scale[10] = 2.0f;
        float[] out = new float[16];
        Matrices.multiply(scale, translate, out);
        // Scale after translate: the translation is scaled too.
        assertEquals(20.0f, out[12], 1.0e-6f, "translation should be scaled");
        Matrices.multiply(translate, scale, out);
        // Translate after scale: it is not.
        assertEquals(10.0f, out[12], 1.0e-6f, "translation should not be scaled");
    }

    /**
     * The whole point of the depth fix: a point on the near plane comes out at
     * 0 and one on the far plane at 1, where OpenGL would have given -1 and 1.
     */
    @Test
    void depthFixPutsNearAtZeroAndFarAtOne() {
        float near = 0.2f;
        float far = 1448.0f;
        float[] projection = perspective(1.2f, 1.0f, near, far);
        Matrices.toVulkanDepth(projection);
        assertEquals(0.0f, depthOf(projection, near), 1.0e-4f, "near plane");
        assertEquals(1.0f, depthOf(projection, far), 1.0e-4f, "far plane");
    }

    /** Depth is monotonic between the planes, which is what a depth test needs. */
    @Test
    void depthIncreasesWithDistance() {
        float[] projection = perspective(1.2f, 1.0f, 0.2f, 1448.0f);
        Matrices.toVulkanDepth(projection);
        float previous = -1.0f;
        for (float distance = 0.2f; distance < 1448.0f; distance *= 1.5f) {
            float depth = depthOf(projection, distance);
            assertTrue(depth > previous,
                    "depth at " + distance + " should exceed depth at the step before");
            previous = depth;
        }
    }

    /**
     * A point straight ahead at the given distance, through the matrix, as the
     * depth that lands in the buffer. The camera looks down -z.
     */
    private static float depthOf(float[] m, float distance) {
        float z = -distance;
        float clipZ = m[2 * 4 + 2] * z + m[3 * 4 + 2];
        float clipW = m[2 * 4 + 3] * z + m[3 * 4 + 3];
        return clipZ / clipW;
    }

    @Test
    void planesAreRecoveredFromTheProjection() {
        float[] projection = perspective(1.2f, 16.0f / 9.0f, 0.2f, 1448.0f);
        // Read before the depth fix: this is what the reflection asks of the
        // matrix the game handed over, not of the one Vulkan is given.
        assertEquals(0.2f, Matrices.nearPlane(projection), 1.0e-3f);
        assertEquals(1448.0f, Matrices.farPlane(projection), 1.0f);
    }

    /**
     * The near plane moved from 0.05 to 0.2 to stop snow speckling at a
     * distance, and the reasoning was arithmetic on the depth step. If that
     * ever silently reverts, this is the line that notices.
     */
    @Test
    void aNearerPlaneIsReadBackAsNearer() {
        assertEquals(0.05f, Matrices.nearPlane(perspective(1.2f, 1.0f, 0.05f, 1448.0f)), 1.0e-3f);
        assertEquals(0.20f, Matrices.nearPlane(perspective(1.2f, 1.0f, 0.20f, 1448.0f)), 1.0e-3f);
    }

    @Test
    void anOrthographicMatrixIsRefusedRatherThanGuessedAt() {
        float[] ortho = identity();
        // m10 = 1 with no perspective divide: the denominator for the near
        // plane is zero, and there is no answer to give.
        assertEquals(0.0f, Matrices.nearPlane(ortho), 0.0f);
    }
}
