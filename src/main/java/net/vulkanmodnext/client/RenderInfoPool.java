package net.vulkanmodnext.client;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.EnumFacing;

import java.lang.reflect.Constructor;

/**
 * Keeps the visibility search's per-chunk records alive between walks.
 *
 * Vanilla allocates one of these for every chunk the search reaches and lets
 * the whole lot die on the next walk. At render distance 64 that is some four
 * thousand objects per walk and the walk runs on most frames, which is a steady
 * stream of short-lived garbage on the thread that has to finish the frame.
 *
 * The records are handed out from here instead and reset in place. A walk calls
 * {@link #reset()} and then {@link #take}; anything taken during the previous
 * walk is still referenced by the list the game reads until that moment, which
 * is why the reset happens at the start of a walk and not at the end of one.
 *
 * <h2>Why reflection</h2>
 *
 * The record type is a package-private inner class of {@code RenderGlobal} with
 * a private constructor, so it cannot be named, extended or called from here —
 * see {@link RenderInfo}. Reflection is the way in, and the cost is bounded: it
 * runs only when the pool grows, the pool only ever grows to the largest walk
 * of the session, and after a minute of play it never runs again. Every walk
 * after that hands back objects that already exist.
 */
public final class RenderInfoPool {

    private static final String TYPE =
            "net.minecraft.client.renderer.RenderGlobal$ContainerLocalRenderInformation";

    private static Constructor<?> constructor;
    private static boolean constructorMissing;

    private RenderInfo[] records = new RenderInfo[4096];
    private int allocated;
    private int used;

    /**
     * Begins a walk. Records handed out before this point stay valid until now
     * because the game is still reading last walk's list; taking that ordering
     * the other way round would hand out a record that is still being drawn.
     */
    public void reset() {
        used = 0;
    }

    /** How many records this walk has taken, which is the chunks it reached. */
    public int used() {
        return used;
    }

    /**
     * A record for one visited chunk, reset to the state a fresh one would have.
     *
     * @param owner the {@code RenderGlobal} the record belongs to; the type is
     *              an inner class, so every instance carries its outer object
     * @return null if the record type could not be reached at all, which leaves
     *              the caller to fall back to the game's own search
     */
    public RenderInfo take(Object owner, RenderChunk chunk, EnumFacing facing, int counter) {
        if (used == records.length) {
            RenderInfo[] grown = new RenderInfo[records.length * 2];
            System.arraycopy(records, 0, grown, 0, records.length);
            records = grown;
        }
        if (used == allocated) {
            RenderInfo fresh = create(owner);
            if (fresh == null) {
                return null;
            }
            records[allocated++] = fresh;
        }
        RenderInfo record = records[used++];
        record.vulkanmodnext$reset(chunk, facing, counter);
        return record;
    }

    private static RenderInfo create(Object owner) {
        if (constructorMissing) {
            return null;
        }
        try {
            if (constructor == null) {
                Class<?> type = Class.forName(TYPE, false, RenderInfoPool.class.getClassLoader());
                for (Constructor<?> candidate : type.getDeclaredConstructors()) {
                    if (candidate.getParameterTypes().length == 4) {
                        candidate.setAccessible(true);
                        constructor = candidate;
                        break;
                    }
                }
                if (constructor == null) {
                    constructorMissing = true;
                    return null;
                }
            }
            return (RenderInfo) constructor.newInstance(owner, null, null, 0);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            constructorMissing = true;
            return null;
        }
    }
}
