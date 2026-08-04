package net.vulkanmod112.client;

/**
 * The matrix arithmetic that stands between OpenGL's conventions and Vulkan's.
 *
 * <h2>Why it is its own class</h2>
 *
 * This was four loops inside {@code TerrainHooks}, which imports the game and
 * holds a frame's worth of state, so none of it could be run without a client.
 * That matters more here than the size of the code suggests: every number the
 * terrain shader places in the world comes through these lines, an error in
 * them looks like a plausible picture drawn slightly wrong, and this project
 * has twice spent a day on arithmetic that read correctly — a refraction
 * offset that was inverted and a depth range that was not.
 *
 * <p>Nothing here touches OpenGL, Vulkan or Minecraft. That is the point: it
 * can be checked against known answers rather than against a screenshot.
 *
 * <h2>Column-major</h2>
 *
 * OpenGL hands back matrices in column-major order and so does everything
 * here: element {@code m[col * 4 + row]}. Vulkan's shaders read the same
 * layout, so the arrays are passed through untouched apart from what is
 * documented below.
 */
public final class Matrices {

    private Matrices() {
    }

    /**
     * Column-major 4x4 multiply: {@code out = a * b}.
     *
     * @param out may not be either input; the result is written as it is
     *            computed, so aliasing would read values already overwritten
     */
    public static void multiply(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                out[col * 4 + row] = sum;
            }
        }
    }

    /**
     * Rewrites a clip matrix so depth comes out in Vulkan's range.
     *
     * OpenGL's clip space puts the near plane at z = -w and the far plane at
     * z = +w; Vulkan puts the near plane at 0 and the far plane at +w. The
     * conversion is {@code z' = 0.5z + 0.5w}, and doing it to the matrix
     * rather than to every vertex is what makes it free.
     *
     * <p>Applied in place, to the third row of each column, because that row
     * is what produces z.
     */
    public static void toVulkanDepth(float[] mvp) {
        for (int col = 0; col < 4; col++) {
            float z = mvp[col * 4 + 2];
            float w = mvp[col * 4 + 3];
            mvp[col * 4 + 2] = 0.5f * z + 0.5f * w;
        }
    }

    /**
     * The near plane of a perspective projection, read back out of it.
     *
     * The shader is given depths in [0,1] and needs distances in blocks to
     * tell "the ray crossed this surface" from "the ray sailed a long way
     * behind it". Both planes are recoverable from the matrix that produced
     * the depth, which is better than being told them separately: the game
     * changes them for the zoom, for the void and for render distance, and a
     * second copy of a number is a second thing to keep in step.
     *
     * <p>Returns 0 for a matrix that is not a perspective projection — an
     * orthographic one has no far plane at infinity to divide by, and the
     * caller has nothing useful to do with a made-up answer.
     */
    public static float nearPlane(float[] projection) {
        float m10 = projection[2 * 4 + 2];
        float m14 = projection[3 * 4 + 2];
        float denominator = m10 - 1.0f;
        return denominator == 0.0f ? 0.0f : m14 / denominator;
    }

    /** The far plane of a perspective projection; see {@link #nearPlane}. */
    public static float farPlane(float[] projection) {
        float m10 = projection[2 * 4 + 2];
        float m14 = projection[3 * 4 + 2];
        float denominator = m10 + 1.0f;
        return denominator == 0.0f ? 0.0f : m14 / denominator;
    }
}
