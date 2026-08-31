package net.vulkanmodnext.client;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.vulkanmodnext.VulkanModNext;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which animated sprites are actually in front of you, so that the rest can be
 * left alone.
 *
 * <h2>What vanilla does</h2>
 *
 * Once a tick the block atlas walks every sprite that has an animation and
 * uploads its next frame to the card. Every one, every tick, whether or not a
 * single block using it is on screen — water, lava, fire, portals, sea
 * lanterns, prismarine, magma, and in a modpack several hundred machines
 * besides. The upload is small and the count is what costs: on a weak machine
 * it is a visible slice of every twentieth of a second.
 *
 * <h2>What this does instead</h2>
 *
 * A chunk records which animated sprites its blocks use while it is being
 * built, which is the one moment the answer is knowable at all — after that
 * the geometry is texture coordinates in a buffer and the sprite is gone. Each
 * frame the chunks that are actually being drawn contribute their sprites to
 * one set, and the tick updates that set rather than all of them.
 *
 * <h2>The two rules that keep it honest</h2>
 *
 * A chunk that has no record contributes <em>everything</em>. That covers the
 * chunks built before this was switched on, and it covers the case where the
 * patch that records them is disabled: the failure is "no saving", never "the
 * world stopped moving".
 *
 * A sprite that no chunk has ever reported is always updated. That is what
 * keeps the item in your hand, the fire on a burning creature and the texture
 * in a menu moving — none of them are terrain, so none of them are in any
 * chunk's record, and a rule that only knew about terrain would freeze them.
 * Fluids are always updated for the same reason and one more: they are what a
 * player looks at when they want to know whether the game is still running.
 *
 * <p>What remains, and it is why this ships switched off: a texture that is
 * both a block and an item freezes in your hand while no such block is in
 * sight. Holding a block of magma in a world without one placed is the shape
 * of it.
 */
public final class AnimatedSprites {

    /** Bits per word of the sets below. */
    private static final int BITS = 64;

    /** The animated sprites of the block atlas, in the order the atlas holds them. */
    private static TextureAtlasSprite[] sprites = new TextureAtlasSprite[0];

    /** Where each of them sits in that order. */
    private static Map<TextureAtlasSprite, Integer> position = new IdentityHashMap<>();

    /** Words in each set. */
    private static int words;

    /**
     * Sprites that must be updated whatever is on screen: the ones no chunk
     * has ever used, and the fluids.
     */
    private static long[] always = new long[0];

    /** What the chunks drawn in the last frame between them use. */
    private static volatile long[] wanted = new long[0];

    /** Rebuilt each frame, so that the set in use is never half-written. */
    private static long[] gathering = new long[0];

    /**
     * Sprites an item model has drawn with lately, in two buckets.
     *
     * The reason there are two: an item is drawn when it is in your hand, in a
     * menu, in a frame or on the ground, and none of that is terrain, so no
     * chunk will ever vouch for it. Marking it as it is drawn is exact where
     * guessing is not — but a set cleared every tick would lose the item the
     * moment a menu closes for a frame. Two buckets swapped once a second give
     * between one and two seconds of memory, which is longer than any gap
     * between two draws of the same item and short enough to be worth having.
     */
    private static long[] itemsNow = new long[0];
    private static long[] itemsBefore = new long[0];
    private static long itemsSwappedAt;

    /** Sprites of a baked item model, worked out once per model. */
    private static final Map<Object, long[]> BY_ITEM_MODEL = new IdentityHashMap<>();

    /** What is being recorded on this thread while a chunk builds. */
    private static final ThreadLocal<Build> BUILDING = new ThreadLocal<>();

    /**
     * Blocks handed to {@link #recordBlock} and how many of them were a state
     * not seen before in the same chunk.
     *
     * The counter sits inside the change it justifies. Every block used to
     * take a lock on the shared state table and or a mask word by word; now
     * only the first block of each distinct state does either. The ratio
     * between these two numbers is exactly the work that stopped happening,
     * and it is a property of the world rather than of the machine, so it can
     * be read off a tester's report without a stopwatch.
     *
     * They are added to once per chunk, from the thread that built it — a
     * shared counter touched per block is the thing being removed here, and
     * this project has already paid for that lesson once.
     */
    private static final java.util.concurrent.atomic.AtomicLong blocksRecorded =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong statesResolved =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * The sprites of a block state, worked out once.
     *
     * States are singletons in this version, so identity is the right
     * comparison and the cheapest one. A state whose model refuses to answer
     * is stored as a mask with everything set, which is the safe direction.
     */
    private static final Map<IBlockState, long[]> BY_STATE = new IdentityHashMap<>();

    private static boolean ready;

    /**
     * When the visible set was last gathered.
     *
     * A sentinel, not bookkeeping. The set is filled in by the terrain hook,
     * and that hook belongs to a patch group somebody can switch off — after
     * which the last set would stand for the rest of the session and every
     * sprite outside it would be frozen for good. Older than a second and it
     * is treated as unknown, which means everything moves.
     */
    private static volatile long gatheredAt;

    /** Sprites updated and skipped in the last tick, for the diagnostics line. */
    private static volatile int lastUpdated;

    /**
     * How many item models were looked at — a sentinel, not a statistic.
     *
     * The hook that feeds this is the one injection in the mod that is allowed
     * to apply to nothing (the signature it targets is one Forge has changed
     * before). If it ever stops applying, every animated texture in the hand
     * and in the inventory freezes and nothing anywhere says why: the setting
     * is on, the terrain half still works, and the only visible symptom is a
     * lava bucket that has stopped moving. That has already cost a release
     * once, in a different hook.
     *
     * So the count goes in the report. Zero here with the setting on and a
     * world open is not "nothing to draw" — it is the hook missing.
     */
    private static volatile long itemModelCalls;

    private AnimatedSprites() {
    }

    /**
     * Takes the atlas's list of animated sprites and numbers them.
     *
     * Called from the tick that updates them, because that is the one place
     * the list is certainly complete and certainly the current one — a
     * resource reload builds a new atlas and every sprite object with it.
     */
    public static synchronized void index(List<TextureAtlasSprite> animated) {
        if (ready && sprites.length == animated.size()
                && (sprites.length == 0 || sprites[0] == animated.get(0))) {
            return;
        }
        sprites = animated.toArray(new TextureAtlasSprite[0]);
        position = new IdentityHashMap<>(sprites.length * 2);
        for (int i = 0; i < sprites.length; i++) {
            position.put(sprites[i], i);
        }
        words = (sprites.length + BITS - 1) / BITS;
        always = new long[words];
        itemsNow = new long[words];
        itemsBefore = new long[words];
        gathering = new long[words];
        wanted = new long[words];
        BY_STATE.clear();
        synchronized (BY_ITEM_MODEL) {
            BY_ITEM_MODEL.clear();
        }
        // Fluids first and permanently. Named rather than found through a
        // model, because water and lava are drawn by a renderer of their own
        // that never asks a model for a quad, so nothing else here would ever
        // see them.
        for (int i = 0; i < sprites.length; i++) {
            String name = sprites[i].getIconName();
            if (name != null && (name.contains("water") || name.contains("lava")
                    || name.contains("fire") || name.contains("portal"))) {
                set(always, i);
            }
        }
        ready = true;
        VulkanModNext.LOGGER.info("Smart animations: {} animated sprites in the block atlas",
                sprites.length);
    }

    /** Whether the sprite table has been read yet. */
    public static boolean ready() {
        return ready;
    }

    /** A chunk is about to be built on this thread. */
    public static void beginChunk() {
        if (!ready) {
            return;
        }
        Build build = BUILDING.get();
        if (build == null) {
            build = new Build();
            BUILDING.set(build);
        }
        // A fresh array every time. The one from the previous build was handed
        // to that chunk at the end of it and belongs to the chunk now.
        build.mask = new long[words];
        build.reset();
    }

    /**
     * One block of the chunk being built on this thread.
     *
     * <p>Called for every block of every chunk — a few million times over a
     * minute of flying — so what it does per call is the whole question. It
     * used to take a lock on the shared state table and or a mask word by
     * word, both of them per block. A chunk is thousands of blocks made of a
     * few dozen distinct states, so almost every one of those was the same
     * answer fetched again, and the lock was shared between all the chunk
     * building threads at once.
     *
     * <p>Now the states already seen in this chunk are remembered on the
     * thread that is building it, in a table with no lock and no allocation,
     * and the shared map is asked once per distinct state. What the common
     * case costs is one identity probe.
     */
    public static void recordBlock(IBlockState state) {
        Build build = BUILDING.get();
        if (build == null || state == null) {
            return;
        }
        long[] mask = build.mask;
        if (mask == null) {
            return;
        }
        build.blocks++;
        if (!build.remember(state)) {
            return;
        }
        build.distinct++;
        long[] ofState = maskOf(state);
        // Bounded by both, not by the words field: a resource reload replaces
        // the atlas and the word count with it, and a chunk that began before
        // it would otherwise run off the end of one array or the other.
        int shared = Math.min(mask.length, ofState.length);
        for (int i = 0; i < shared; i++) {
            mask[i] |= ofState[i];
        }
    }

    /** The chunk is finished; keep what it uses. */
    public static void finishChunk(RenderChunk chunk) {
        Build build = BUILDING.get();
        if (build == null || build.mask == null) {
            return;
        }
        long[] mask = build.mask;
        build.mask = null;
        blocksRecorded.addAndGet(build.blocks);
        statesResolved.addAndGet(build.distinct);
        build.reset();
        if (chunk instanceof SpriteMarked) {
            ((SpriteMarked) chunk).vulkanmodnext$animatedSprites(mask);
        }
    }

    /**
     * The chunks being drawn this frame.
     *
     * Called from the first terrain layer of the frame; the four layers hold
     * the same chunks and asking once is enough.
     */
    public static void markVisible(List<RenderChunk> chunks) {
        if (!ready || chunks == null) {
            return;
        }
        long[] gather = gathering;
        if (gather.length != words) {
            return;
        }
        java.util.Arrays.fill(gather, 0L);
        boolean anyUnknown = false;
        for (int i = 0; i < chunks.size(); i++) {
            RenderChunk chunk = chunks.get(i);
            long[] mask = chunk instanceof SpriteMarked
                    ? ((SpriteMarked) chunk).vulkanmodnext$animatedSprites() : null;
            if (mask == null || mask.length != words) {
                // Built before this was switched on, or built with the patch
                // that records them switched off. Either way nothing is known
                // about it and nothing may be skipped on its account.
                anyUnknown = true;
                break;
            }
            for (int w = 0; w < words; w++) {
                gather[w] |= mask[w];
            }
        }
        if (anyUnknown) {
            java.util.Arrays.fill(gather, -1L);
        }
        long[] swap = wanted;
        wanted = gather;
        gathering = swap;
        gatheredAt = System.currentTimeMillis();
    }

    /**
     * Updates the sprites that are wanted and leaves the rest alone.
     *
     * @return how many were updated
     */
    public static int updateWanted(List<TextureAtlasSprite> animated) {
        index(animated);
        long[] visible = wanted;
        boolean fresh = System.currentTimeMillis() - gatheredAt < 1000L;
        int updated = 0;
        for (int i = 0; i < sprites.length; i++) {
            boolean needed = !fresh
                    || get(always, i)
                    || get(itemsNow, i)
                    || get(itemsBefore, i)
                    || (visible.length == words && get(visible, i));
            if (needed) {
                sprites[i].updateAnimation();
                updated++;
            }
        }
        lastUpdated = updated;
        return updated;
    }

    /** One line for the diagnostics snapshot, or null when this is switched off. */
    public static String stats() {
        if (!ready || sprites.length == 0) {
            return null;
        }
        boolean fresh = System.currentTimeMillis() - gatheredAt < 1000L;
        long blocks = blocksRecorded.get();
        long states = statesResolved.get();
        return "  smart animations: " + lastUpdated + " of " + sprites.length
                + " animated sprites updated per tick"
                + (fresh ? "" : " (no visible set gathered, everything updated)")
                + (blocks == 0 ? ""
                        : ", recorded " + blocks + " blocks as " + states + " distinct states ("
                                + String.format("%.1f", 100.0 * states / blocks)
                                + "% reached the shared table)")
                + ", item models seen " + itemModelCalls
                + (itemModelCalls == 0
                        ? " — WARNING: the item hook never ran, held and inventory textures "
                                + "will be frozen"
                        : "");
    }

    /**
     * An item model is being drawn: whatever it is made of has to keep moving.
     *
     * Called from the item renderer, which covers the hand, the inventory, a
     * dropped stack and an item frame in one place. The model's own sprites
     * are worked out once and remembered against the model object, because the
     * same few models are drawn every frame.
     */
    public static void recordItemModel(Object model, List<BakedQuad> quads) {
        if (!ready) {
            return;
        }
        itemModelCalls++;
        long[] mask;
        synchronized (BY_ITEM_MODEL) {
            mask = BY_ITEM_MODEL.get(model);
        }
        if (mask == null) {
            mask = new long[words];
            for (int i = 0; i < quads.size(); i++) {
                Integer at = position.get(quads.get(i).getSprite());
                if (at != null) {
                    set(mask, at);
                }
            }
            synchronized (BY_ITEM_MODEL) {
                BY_ITEM_MODEL.put(model, mask);
            }
        }
        long now = System.currentTimeMillis();
        if (now - itemsSwappedAt > 1000L) {
            itemsSwappedAt = now;
            long[] swap = itemsBefore;
            itemsBefore = itemsNow;
            java.util.Arrays.fill(swap, 0L);
            itemsNow = swap;
        }
        for (int i = 0; i < words; i++) {
            itemsNow[i] |= mask[i];
        }
    }

    /** How many of the atlas's animated sprites there are. */
    public static int total() {
        return sprites.length;
    }

    /**
     * Which animated sprites a block state draws with.
     *
     * Asked of the baked model rather than of the block, because a block does
     * not know its textures — a model does, and one block state can carry a
     * different model in every resource pack. Everything is set on any failure
     * at all: a state whose model throws must cost a saving, never an
     * animation.
     */
    private static long[] maskOf(IBlockState state) {
        long[] known;
        synchronized (BY_STATE) {
            known = BY_STATE.get(state);
        }
        if (known != null) {
            return known;
        }
        long[] mask = new long[words];
        try {
            if (state.getMaterial() == Material.WATER || state.getMaterial() == Material.LAVA) {
                // Drawn by the fluid renderer, which never asks for a quad.
                // Already in the always set; nothing to add here.
                java.util.Arrays.fill(mask, 0L);
            } else {
                IBakedModel model = Minecraft.getMinecraft().getBlockRendererDispatcher()
                        .getModelForState(state);
                collect(model, state, null, mask);
                for (EnumFacing face : EnumFacing.VALUES) {
                    collect(model, state, face, mask);
                }
            }
        } catch (Throwable t) {
            java.util.Arrays.fill(mask, -1L);
        }
        synchronized (BY_STATE) {
            BY_STATE.put(state, mask);
        }
        return mask;
    }

    private static void collect(IBakedModel model, IBlockState state, EnumFacing face,
                                long[] mask) {
        List<BakedQuad> quads = model.getQuads(state, face, 0L);
        for (int i = 0; i < quads.size(); i++) {
            TextureAtlasSprite sprite = quads.get(i).getSprite();
            Integer at = position.get(sprite);
            if (at != null) {
                set(mask, at);
            }
        }
    }

    private static void set(long[] bits, int index) {
        bits[index >> 6] |= 1L << (index & 63);
    }

    private static boolean get(long[] bits, int index) {
        return (bits[index >> 6] & (1L << (index & 63))) != 0L;
    }

    /**
     * One chunk build, on one thread.
     *
     * <h2>Why a table of its own and not a set</h2>
     *
     * What is wanted is "have I already dealt with this state in this chunk",
     * asked once per block. A {@code HashSet} would answer it by calling
     * {@code hashCode} on the state, and in this version a state's hash is the
     * hash of its property map — a walk over every property of the block,
     * every time. That is more work than the lookup saves. Identity is both
     * the correct comparison here (states are singletons) and the cheap one,
     * and open addressing over a plain array asks nothing of the key beyond
     * its identity hash.
     *
     * <p>Nothing here is shared with another thread, so nothing here needs a
     * lock, and the table is kept between chunks so a build allocates only the
     * mask it is going to hand over.
     */
    static final class Build {

        /** What this chunk's blocks use; handed to the chunk when it finishes. */
        long[] mask;

        /**
         * The distinct states seen so far, open-addressed by identity.
         *
         * A vanilla chunk is a few dozen states; a modded one can be several
         * hundred. It starts big enough for the first and grows for the
         * second, and never shrinks — a build thread that has met a busy chunk
         * once will meet another.
         */
        private Object[] seen = new Object[128];
        private int seenCount;

        /** Counted per thread and handed over once, at the end of the chunk. */
        long blocks;
        long distinct;

        void reset() {
            if (seenCount != 0) {
                java.util.Arrays.fill(seen, null);
                seenCount = 0;
            }
            blocks = 0L;
            distinct = 0L;
        }

        /** How many distinct things it holds, for the test that proves it holds them. */
        int size() {
            return seenCount;
        }

        /** @return true when this state has not been seen in this chunk before */
        boolean remember(Object state) {
            Object[] table = seen;
            int wrap = table.length - 1;
            int at = spread(System.identityHashCode(state)) & wrap;
            while (true) {
                Object there = table[at];
                if (there == null) {
                    table[at] = state;
                    seenCount++;
                    // Kept at most half full: past that, open addressing spends
                    // its time walking runs of occupied slots.
                    if (seenCount * 2 > table.length) {
                        grow();
                    }
                    return true;
                }
                if (there == state) {
                    return false;
                }
                at = (at + 1) & wrap;
            }
        }

        private void grow() {
            Object[] older = seen;
            Object[] bigger = new Object[older.length * 2];
            int wrap = bigger.length - 1;
            for (Object state : older) {
                if (state == null) {
                    continue;
                }
                int at = spread(System.identityHashCode(state)) & wrap;
                while (bigger[at] != null) {
                    at = (at + 1) & wrap;
                }
                bigger[at] = state;
            }
            seen = bigger;
        }

        /**
         * Identity hashes are addresses on some machines, and addresses of
         * objects allocated together share their low bits — exactly the bits
         * the table indexes with. Folding the high half down first is what
         * keeps two states born in the same batch out of the same run.
         */
        private static int spread(int hash) {
            return hash ^ (hash >>> 16);
        }
    }

    /** A chunk that remembers which animated sprites its blocks use. */
    public interface SpriteMarked {

        long[] vulkanmodnext$animatedSprites();

        void vulkanmodnext$animatedSprites(long[] mask);
    }
}
