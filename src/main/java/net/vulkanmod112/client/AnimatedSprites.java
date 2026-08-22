package net.vulkanmod112.client;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.vulkanmod112.VulkanMod112;

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

    /** The mask being built, on whichever thread is building a chunk. */
    private static final ThreadLocal<long[]> BUILDING = new ThreadLocal<>();

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
        VulkanMod112.LOGGER.info("Smart animations: {} animated sprites in the block atlas",
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
        long[] mask = BUILDING.get();
        if (mask == null || mask.length != words) {
            mask = new long[words];
            BUILDING.set(mask);
        } else {
            java.util.Arrays.fill(mask, 0L);
        }
    }

    /** One block of the chunk being built on this thread. */
    public static void recordBlock(IBlockState state) {
        long[] mask = BUILDING.get();
        if (mask == null) {
            return;
        }
        long[] ofState = maskOf(state);
        for (int i = 0; i < words; i++) {
            mask[i] |= ofState[i];
        }
    }

    /** The chunk is finished; keep what it uses. */
    public static void finishChunk(RenderChunk chunk) {
        long[] mask = BUILDING.get();
        if (mask == null || !(chunk instanceof SpriteMarked)) {
            return;
        }
        BUILDING.set(null);
        ((SpriteMarked) chunk).vulkanmod112$animatedSprites(mask);
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
                    ? ((SpriteMarked) chunk).vulkanmod112$animatedSprites() : null;
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
        return "  smart animations: " + lastUpdated + " of " + sprites.length
                + " animated sprites updated per tick"
                + (fresh ? "" : " (no visible set gathered, everything updated)");
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

    /** A chunk that remembers which animated sprites its blocks use. */
    public interface SpriteMarked {

        long[] vulkanmod112$animatedSprites();

        void vulkanmod112$animatedSprites(long[] mask);
    }
}
