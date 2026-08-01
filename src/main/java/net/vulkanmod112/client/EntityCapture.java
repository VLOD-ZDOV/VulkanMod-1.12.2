package net.vulkanmod112.client;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.model.PositionTextureVertex;
import net.vulkanmod112.mixin.ModelBoxAccessor;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.IdentityHashMap;

/**
 * Reads the geometry and placement of every entity part the game draws, and
 * draws nothing.
 *
 * <h2>Why this exists before anything visible</h2>
 *
 * Entities are the largest remaining piece of the world this renderer does not
 * own, and owning them is what stands between the terrain acceleration
 * structures and anything worth calling ray tracing — a reflection of a world
 * with no creatures in it reads as a bug, not as a style. They are also the
 * part of the game most heavily hooked by other mods, so the way this goes
 * wrong is not a wrong pixel but somebody else's mod no longer drawing.
 *
 * So the first step takes nothing over. It measures two things that decide the
 * whole design, and neither can be reasoned out from the source:
 *
 * <ol>
 *   <li><b>What fraction of a real scene arrives through here at all.</b>
 *       Vanilla builds every model from {@link ModelRenderer}, and so do most
 *       mods — but not all. A renderer with its own geometry path simply never
 *       calls this, which is exactly the fallback wanted, and the number says
 *       how much would be left behind.</li>
 *   <li><b>What the placement costs to read.</b> The shape of a part is fixed
 *       and can be read once; where it is lives entirely in OpenGL's matrix
 *       stack, and there are a dozen parts to a creature. Asking the driver
 *       for a matrix per part is the price of this whole approach, and it is
 *       either negligible or it rules the approach out.</li>
 * </ol>
 *
 * <h2>What is captured</h2>
 *
 * A part's boxes are quads of position, texture coordinate and normal in model
 * space — the same twenty-four bytes a vertex the game bakes into a display
 * list carries. Colour and light are not in them: those come from fixed
 * function state, and would have to be read separately when this starts
 * drawing. The shape is cached against the part, because a creature's model is
 * built once and drawn thousands of times.
 */
public final class EntityCapture {

    /** Position, texture, normal — eight floats a vertex, four vertices a quad. */
    private static final int FLOATS_PER_VERTEX = 8;

    private static boolean armed;
    private static final IdentityHashMap<Object, float[]> GEOMETRY =
            new IdentityHashMap<Object, float[]>();
    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    private static final float[] PLACEMENT = new float[16];

    private static int frameParts;
    private static int frameQuads;
    private static long frameNanos;
    private static int lastParts;
    private static int lastQuads;
    private static long lastNanos;
    private static long shapesCached;
    private static long partsSeen;

    private EntityCapture() {
    }

    /** Only during the game's own entity pass; a model shown in a GUI is not one. */
    public static void arm() {
        armed = VulkanConfig.isEntityCapture();
    }

    /**
     * Keeps the last pass that drew anything, rather than the last pass.
     *
     * The game runs this pass twice when it is going to draw entities again
     * after the water, and the second run is empty unless a shader mod asked
     * for it. Reporting whichever ran last therefore reported zero on every
     * frame — a counter that said the hook was dead while two million parts a
     * session were going through it.
     */
    public static void disarm() {
        if (!armed) {
            return;
        }
        armed = false;
        if (frameParts > 0) {
            lastParts = frameParts;
            lastQuads = frameQuads;
            lastNanos = frameNanos;
        }
        frameParts = 0;
        frameQuads = 0;
        frameNanos = 0;
    }

    /**
     * One part of one creature, at the moment the game is about to draw it.
     *
     * Called before vanilla's own draw and never instead of it. Anything that
     * throws here would take down the entity pass of a game that is rendering
     * perfectly well without us, so nothing is allowed out.
     */
    public static void part(ModelRenderer part, float scale) {
        if (!armed || part == null) {
            return;
        }
        long start = System.nanoTime();
        try {
            float[] shape = GEOMETRY.get(part);
            if (shape == null) {
                shape = bake(part, scale);
                GEOMETRY.put(part, shape);
                shapesCached++;
            }
            if (shape.length == 0) {
                return;
            }
            // Where the part is, and the only thing here that has to be asked
            // of the driver every time. The shape above is a creature's model
            // and never changes; this is the pose, and it is rebuilt by the
            // game on the matrix stack for every part of every creature in
            // every frame.
            MATRIX.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX);
            MATRIX.get(PLACEMENT).clear();
            frameParts++;
            frameQuads += shape.length / (FLOATS_PER_VERTEX * 4);
            partsSeen++;
        } catch (Throwable ignored) {
            // A model this does not understand is a model the game still draws.
        } finally {
            frameNanos += System.nanoTime() - start;
        }
    }

    /**
     * Turns a part's boxes into flat vertices, once.
     *
     * The scale is baked in, which matches what the game does: it compiles the
     * part's display list on first use and reuses it at whatever scale came
     * first. A model drawn at two scales is already wrong in vanilla, and
     * copying that is more useful than being right differently.
     */
    private static float[] bake(ModelRenderer part, float scale) {
        java.util.List<ModelBox> boxes = part.cubeList;
        if (boxes == null || boxes.isEmpty()) {
            return new float[0];
        }
        int quads = 0;
        for (ModelBox box : boxes) {
            TexturedQuad[] list = ((ModelBoxAccessor) box).vulkanmod112$quads();
            if (list != null) {
                quads += list.length;
            }
        }
        float[] out = new float[quads * 4 * FLOATS_PER_VERTEX];
        int at = 0;
        for (ModelBox box : boxes) {
            TexturedQuad[] list = ((ModelBoxAccessor) box).vulkanmod112$quads();
            if (list == null) {
                continue;
            }
            for (TexturedQuad quad : list) {
                // The same normal the game works out at bake time, from two
                // edges of the quad. Kept because a lit surface needs it and
                // because it is free here.
                net.minecraft.util.math.Vec3d edge1 =
                        quad.vertexPositions[1].vector3D.subtractReverse(quad.vertexPositions[0].vector3D);
                net.minecraft.util.math.Vec3d edge2 =
                        quad.vertexPositions[1].vector3D.subtractReverse(quad.vertexPositions[2].vector3D);
                net.minecraft.util.math.Vec3d normal = edge2.crossProduct(edge1).normalize();
                for (int i = 0; i < 4; i++) {
                    PositionTextureVertex vertex = quad.vertexPositions[i];
                    out[at++] = (float) (vertex.vector3D.x * scale);
                    out[at++] = (float) (vertex.vector3D.y * scale);
                    out[at++] = (float) (vertex.vector3D.z * scale);
                    out[at++] = vertex.texturePositionX;
                    out[at++] = vertex.texturePositionY;
                    out[at++] = (float) normal.x;
                    out[at++] = (float) normal.y;
                    out[at++] = (float) normal.z;
                }
            }
        }
        return out;
    }

    /** One line for the diagnostics report; this is the whole product so far. */
    public static String stats() {
        if (!VulkanConfig.isEntityCapture()) {
            return "entity capture: off";
        }
        return "entity capture: " + lastParts + " parts, " + lastQuads + " quads last frame, "
                + String.format("%.3f", lastNanos / 1e6) + " ms to read them ("
                + (lastParts == 0 ? "n/a" : String.format("%.0f ns a part", lastNanos / (double) lastParts))
                + "), " + shapesCached + " model shapes cached, " + partsSeen + " parts seen";
    }
}
