package net.vulkanmod112.client;

import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The flood fill that decides which chunks are on screen, walked over flat
 * arrays instead of over the chunk objects.
 *
 * <h2>Why this exists</h2>
 *
 * The game's own profiler puts this search at 25% to 48% of the frame at render
 * distance 64, against 4.5% for drawing the world. Two ways of attacking it
 * were measured and both failed:
 *
 * <ul>
 * <li><b>Run it less often.</b> 9 794 of every 10 100 requests come from the
 *     camera moving, which legitimately changes what is visible.</li>
 * <li><b>Compute it more cheaply.</b> Replacing vanilla's eight-corner frustum
 *     test with the one corner that decides it — provably the same answer, 3.4
 *     times fewer dot products — moved a walked chunk from 163 ns to 139 and
 *     did not move the search's share of the frame at all.</li>
 * </ul>
 *
 * That second measurement is what this class is built on. If three times less
 * arithmetic buys 15% of a node, the node is not spending its time on
 * arithmetic. At ~150 ns it is spending it waiting for memory, and vanilla's
 * inner loop asks for a lot of it: for each of the six neighbours of an
 * accepted chunk it dereferences a {@code MutableBlockPos} held by the current
 * chunk, then indexes a 266 000-element array of {@code RenderChunk} at a
 * scattered position, then writes a frame stamp into that object, then follows
 * a second pointer to its {@code AxisAlignedBB}. Three cache lines in three
 * unrelated places, six times per accepted chunk, and only one neighbour in six
 * is accepted.
 *
 * <h2>What replaces it</h2>
 *
 * None of that memory has to be touched, because all of it is derivable:
 *
 * <ul>
 * <li><b>Positions.</b> A neighbour's world position is the current one plus 16
 *     on one axis. Nothing to look up.</li>
 * <li><b>Bounding boxes.</b> {@code RenderChunk.setPosition} builds the box as
 *     exactly {@code (x, y, z) .. (x+16, y+16, z+16)}, so the box is the
 *     position.</li>
 * <li><b>Grid slots.</b> The grid is a torus: slot {@code i} holds whichever
 *     world position congruent to {@code i*16} modulo the grid width is nearest
 *     the player. Stepping one chunk over is stepping one slot over, wrapped.</li>
 * <li><b>The visited stamp.</b> An {@code int} per slot in one flat array,
 *     rather than a field inside each chunk object.</li>
 * </ul>
 *
 * So a rejected neighbour costs one int read and the frustum test, and the
 * chunk object is touched only for a neighbour that was accepted.
 *
 * <h2>Why the derived positions are exactly vanilla's</h2>
 *
 * This is the one step that could quietly disagree, so it is worth stating as a
 * proof rather than an intention. Vanilla looks the neighbour up by world
 * position and then uses the position that chunk <em>actually</em> holds, which
 * is only the same thing while the grid is centred on the player.
 * {@code updateChunkPositions} recentres it whenever the player crosses any
 * chunk boundary or moves more than four blocks, at the top of the same method,
 * before the search runs — so at the moment of the search each slot holds the
 * position in its residue class within half a grid width of the player. Half a
 * grid width is {@code (renderDistance + 0.5) * 16}, and vanilla's own range
 * check rejects anything further than {@code renderDistance * 16}, which is
 * strictly less. Every position this search accepts is therefore inside the
 * window where the mapping is one-to-one, and stepping by 16 lands on the same
 * chunk the lookup would have returned.
 *
 * <h2>Vanilla behaviour kept deliberately</h2>
 *
 * <ul>
 * <li>The stamp is written <b>before</b> the frustum test, as vanilla writes it
 *     inside the {@code &&} chain. A chunk the frustum rejects still counts as
 *     visited, and that is what stops the search walking it again from another
 *     direction.</li>
 * <li>Records are appended to the visible list when they are <b>polled</b>, not
 *     when they are queued, which is what fixes the order the world is drawn
 *     in.</li>
 * <li>The direction mask and the {@code isVisible} test are only consulted when
 *     the game's own "advanced OpenGL" flag is on, exactly as in the original.</li>
 * <li>Everything this class does not implement — a camera outside the world's
 *     height, a fixed debug frustum, a camera type other than the game's own —
 *     is handed back to vanilla rather than approximated.</li>
 * </ul>
 */
public final class VisibilityWalk {

    private static final EnumFacing[] FACES = EnumFacing.VALUES;
    private static final int[] OFFSET_X = new int[6];
    private static final int[] OFFSET_Y = new int[6];
    private static final int[] OFFSET_Z = new int[6];
    private static final int[] OPPOSITE = new int[6];

    /**
     * Checks every derived position against the position the chunk in that slot
     * actually holds — that is, against the answer vanilla's lookup would have
     * given. It costs the very dereference this class exists to avoid, so it is
     * a switch rather than an assertion, and it is the switch to reach for
     * before believing any timing from here: the derivation is the one step
     * that could disagree with vanilla silently, and disagreeing means chunks
     * drawn in the wrong place or not at all.
     */
    private static final boolean VERIFY =
            Boolean.getBoolean("vulkanmod112.verifyWalk");

    static {
        for (int i = 0; i < 6; i++) {
            EnumFacing face = FACES[i];
            OFFSET_X[i] = face.getXOffset();
            OFFSET_Y[i] = face.getYOffset();
            OFFSET_Z[i] = face.getZOffset();
            OPPOSITE[i] = face.getOpposite().ordinal();
        }
    }

    /** Stride of one queue entry: x, y, z, slotX, slotY, slotZ, counter, spare. */
    private static final int STRIDE = 8;

    /**
     * One visited stamp per grid slot. Sized to the grid, never cleared between
     * walks — {@link #epoch} moves instead, so a walk costs nothing to start.
     */
    private int[] stamp = new int[0];
    private int epoch;

    /**
     * The index the visible list is missing.
     *
     * The list itself answers "which sections are on screen", walked from the
     * front. Two passes of the game's own frame ask the opposite question —
     * "is this one section on screen, and where in the list does it sit" — and
     * answer it today by walking all of the list. These three arrays, written
     * as a section is appended, answer it in one read: {@link #emitted} says
     * whether the slot went into <em>this</em> walk's list, {@link #emittedInfo}
     * is the record that went in, and {@link #emittedOrder} is its index, which
     * is what lets a subset be handed back in the order vanilla would have
     * visited it.
     *
     * They are stamped with {@link #epoch} exactly as {@link #stamp} is, so a
     * walk costs nothing to start and a stale slot reads as absent.
     */
    private int[] emitted = new int[0];
    private RenderInfo[] emittedInfo = new RenderInfo[0];
    private int[] emittedOrder = new int[0];

    /**
     * Marks for one caller picking sections out of the index, on its own epoch
     * so that picking twice in a frame — the game renders entities once per
     * render pass — does not need the array cleared.
     */
    private int[] picked = new int[0];
    private int pickEpoch;

    /** True once a walk has finished and its index describes the visible list. */
    private boolean indexed;

    /**
     * The visible sections whose compiled chunk holds block entities, in the
     * order they were appended to the visible list.
     *
     * The game's block-entity pass reaches this answer by asking all seventeen
     * thousand visible sections every frame; the answer changes only when a
     * chunk is rebuilt, and a rebuild arms the next walk, so it is gathered
     * here instead — the compiled chunk is already in a register at that point.
     */
    private final List<RenderInfo> tileSections = new ArrayList<RenderInfo>();

    private int[] queue = new int[4096 * STRIDE];
    private RenderInfo[] queued = new RenderInfo[4096];
    private int head;
    private int tail;

    private final RenderInfoPool pool = new RenderInfoPool();

    /** Neighbours examined, which is what a walk's cost divides by. */
    private int tested;

    private int countX;
    private int countY;
    private int countZ;

    /**
     * Prepares a walk over a grid of the given shape.
     *
     * @return false if the grid is not one this can walk, leaving the caller to
     *         hand the frame back to vanilla
     */
    public boolean begin(int countXIn, int countYIn, int countZIn, int slots) {
        if (slots <= 0 || countXIn <= 0 || countYIn <= 0 || countZIn <= 0
                || countXIn * countYIn * countZIn != slots) {
            return false;
        }
        countX = countXIn;
        countY = countYIn;
        countZ = countZIn;
        if (stamp.length != slots) {
            stamp = new int[slots];
            emitted = new int[slots];
            emittedInfo = new RenderInfo[slots];
            emittedOrder = new int[slots];
            picked = new int[slots];
            epoch = 0;
            pickEpoch = 0;
        }
        // Bumping past the end of the range would make stale stamps look
        // current, so the one walk that wraps pays for a clear.
        if (++epoch == Integer.MAX_VALUE) {
            java.util.Arrays.fill(stamp, 0);
            java.util.Arrays.fill(emitted, 0);
            epoch = 1;
        }
        indexed = false;
        tileSections.clear();
        // Everything reported before this moment is about to be answered again
        // from the chunks themselves, so the backlog is not worth carrying.
        TileEntityArrivals.clear();
        head = 0;
        tail = 0;
        tested = 0;
        pool.reset();
        return true;
    }

    /** The grid slot a world position falls in, or -1 if it is outside the world. */
    public int slotOf(int x, int y, int z) {
        if (y < 0 || y >= countY << 4) {
            return -1;
        }
        int i = floorMod(x >> 4, countX);
        int j = y >> 4;
        int k = floorMod(z >> 4, countZ);
        return (k * countY + j) * countX + i;
    }

    /**
     * The chunk the search starts from: marked visited and queued, the way
     * vanilla seeds it.
     *
     * @return null if a record could not be produced, which means the walk
     *         cannot run at all
     */
    public RenderInfo seed(Object owner, RenderChunk chunk, int slot, int x, int y, int z) {
        RenderInfo record = pool.take(owner, chunk, null, 0);
        if (record == null) {
            return null;
        }
        record.vulkanmod112$setGridSlot(slot);
        stamp[slot] = epoch;
        push(record, x, y, z, floorMod(x >> 4, countX), y >> 4, floorMod(z >> 4, countZ), 0);
        return record;
    }

    /** A record for a chunk that is visible but from which nothing is reachable. */
    public RenderInfo lone(Object owner, RenderChunk chunk) {
        return pool.take(owner, chunk, null, 0);
    }

    /**
     * Runs the search, appending every visible chunk's record to {@code out} in
     * the order vanilla would have appended it.
     *
     * @param out the game's own visible-chunk list, already emptied
     * @return false if a record ran out mid-walk, in which case {@code out} is
     *         incomplete and the caller must fall back
     */
    @SuppressWarnings("unchecked")
    public boolean iterate(Object owner, RenderChunk[] chunks, Frustum camera, List out,
                           int playerX, int playerZ, int renderDistanceChunks,
                           boolean renderChunksMany) {
        int reach = renderDistanceChunks << 4;
        int worldHeight = countY << 4;
        int order = 0;
        // Every record queued here had its slot written before it was queued.
        // If one ever does not, the index cannot describe the list, and saying
        // so leaves the frame on the long lists rather than losing a creature
        // out of a short one.
        boolean everySlotKnown = true;

        while (head < tail) {
            int entry = head++;
            int base = entry * STRIDE;
            int x = queue[base];
            int y = queue[base + 1];
            int z = queue[base + 2];
            int slotX = queue[base + 3];
            int slotY = queue[base + 4];
            int slotZ = queue[base + 5];
            int counter = queue[base + 6];

            RenderInfo info = queued[entry];
            out.add(info);

            int emitSlot = info.vulkanmod112$gridSlot();
            if (emitSlot < 0) {
                everySlotKnown = false;
            } else {
                emitted[emitSlot] = epoch;
                emittedInfo[emitSlot] = info;
                emittedOrder[emitSlot] = order;
            }
            order++;

            // The only two pointers a node follows, and only once each.
            EnumFacing entered = info.vulkanmod112$facing();
            byte mask = info.vulkanmod112$facingMask();
            // Read for every section now rather than only when direction
            // culling wants it. It is the same pointer either way, and it also
            // carries whether this section holds block entities — an answer the
            // game's own pass currently pays for once per section per frame.
            CompiledChunk compiled = info.vulkanmod112$chunk().getCompiledChunk();
            if (!compiled.getTileEntities().isEmpty()) {
                tileSections.add(info);
            }
            boolean cullByVisibility = renderChunksMany && entered != null;
            int enteredBack = entered == null ? -1 : OPPOSITE[entered.ordinal()];

            for (int face = 0; face < 6; face++) {
                tested++;
                if (renderChunksMany) {
                    if ((mask & 1 << OPPOSITE[face]) != 0) {
                        continue;
                    }
                    if (cullByVisibility
                            && !compiled.isVisible(FACES[enteredBack], FACES[face])) {
                        continue;
                    }
                }

                int nx = x + (OFFSET_X[face] << 4);
                int ny = y + (OFFSET_Y[face] << 4);
                int nz = z + (OFFSET_Z[face] << 4);

                // getRenderChunkOffset: out of the grid's reach, or out of the
                // world's height, is no chunk at all.
                if (ny < 0 || ny >= worldHeight) {
                    continue;
                }
                if (abs(playerX - nx) > reach || abs(playerZ - nz) > reach) {
                    continue;
                }

                int nSlotX = slotX + OFFSET_X[face];
                if (nSlotX < 0) {
                    nSlotX += countX;
                } else if (nSlotX >= countX) {
                    nSlotX -= countX;
                }
                int nSlotZ = slotZ + OFFSET_Z[face];
                if (nSlotZ < 0) {
                    nSlotZ += countZ;
                } else if (nSlotZ >= countZ) {
                    nSlotZ -= countZ;
                }
                int nSlotY = slotY + OFFSET_Y[face];
                int slot = (nSlotZ * countY + nSlotY) * countX + nSlotX;

                if (VERIFY) {
                    VanillaFrame.countWalkDerivation(chunks[slot], nx, ny, nz);
                }
                if (stamp[slot] == epoch) {
                    continue;
                }
                // Marked before the frustum decides, as vanilla marks it: a
                // chunk off the edge of the screen must not be walked again
                // from the next direction that reaches it.
                stamp[slot] = epoch;

                if (!camera.isBoxInFrustum(nx, ny, nz, nx + 16, ny + 16, nz + 16)) {
                    continue;
                }

                RenderInfo child = pool.take(owner, chunks[slot], FACES[face], counter + 1);
                if (child == null) {
                    return false;
                }
                child.vulkanmod112$setDirection(mask, FACES[face]);
                // Free: the slot is already in a register here, and carrying it
                // is what lets the rebuild loop at the end of setupTerrain ask
                // about this chunk without touching the chunk.
                child.vulkanmod112$setGridSlot(slot);
                push(child, nx, ny, nz, nSlotX, nSlotY, nSlotZ, counter + 1);
            }
        }
        indexed = everySlotKnown;
        return true;
    }

    /**
     * Whether the index describes the list the game is holding.
     *
     * False until a walk has run to the end. Every path that hands the frame
     * back to vanilla leaves it false, because vanilla then builds a list this
     * has never seen and answering questions about it would be answering about
     * the wrong list.
     */
    public boolean indexed() {
        return indexed;
    }

    /**
     * The visible-list record for one section, or null if that section is not
     * on screen.
     *
     * Takes chunk coordinates — {@code blockX >> 4}, the section index within
     * the column, {@code blockZ >> 4} — because every caller has them in that
     * form already.
     *
     * The grid is a torus, so the slot a far-away chunk maps to belongs to a
     * different chunk that shares its residue. The record's own position is
     * therefore checked rather than assumed: outside the render window the
     * mapping is not one-to-one, and the honest answer there is "not visible".
     */
    public RenderInfo visibleSection(int chunkX, int sectionY, int chunkZ) {
        if (!indexed || sectionY < 0 || sectionY >= countY) {
            return null;
        }
        int slot = (floorMod(chunkZ, countZ) * countY + sectionY) * countX
                + floorMod(chunkX, countX);
        if (emitted[slot] != epoch) {
            return null;
        }
        RenderInfo info = emittedInfo[slot];
        BlockPos position = info.vulkanmod112$chunk().getPosition();
        if (position.getX() >> 4 != chunkX || position.getY() >> 4 != sectionY
                || position.getZ() >> 4 != chunkZ) {
            return null;
        }
        return info;
    }

    /** Where a record sits in the visible list, so a subset can keep that order. */
    public int visibleOrder(RenderInfo info) {
        int slot = info.vulkanmod112$gridSlot();
        return slot < 0 || emitted[slot] != epoch ? Integer.MAX_VALUE : emittedOrder[slot];
    }

    /** Starts a round of {@link #pick}, which is how a caller drops duplicates. */
    public void beginPick() {
        if (++pickEpoch == Integer.MAX_VALUE) {
            java.util.Arrays.fill(picked, 0);
            pickEpoch = 1;
        }
    }

    /** True the first time this slot is picked in the current round. */
    public boolean pick(int slot) {
        if (slot < 0 || slot >= picked.length || picked[slot] == pickEpoch) {
            return false;
        }
        picked[slot] = pickEpoch;
        return true;
    }

    /**
     * The visible sections holding block entities, in visible-list order.
     *
     * Gathered while the walk runs and topped up by {@link #applyArrivals()},
     * which is what keeps it exact rather than nearly so: a section already on
     * the list can finish building long after the walk that put it there.
     */
    public List<RenderInfo> tileEntitySections() {
        return tileSections;
    }

    /**
     * Folds in the sections that finished building since the walk.
     *
     * Each one is checked against the index rather than trusted: a chunk that
     * arrived may not be on screen, and a slot may have been given to a
     * different chunk since. What survives both checks is inserted where the
     * walk would have put it, so the game still visits block entities in the
     * order it would have.
     */
    public void applyArrivals() {
        if (!indexed) {
            TileEntityArrivals.clear();
            return;
        }
        RenderChunk chunk;
        while ((chunk = TileEntityArrivals.poll()) != null) {
            if (chunk.getCompiledChunk().getTileEntities().isEmpty()) {
                continue;
            }
            BlockPos position = chunk.getPosition();
            RenderInfo info = visibleSection(position.getX() >> 4, position.getY() >> 4,
                    position.getZ() >> 4);
            if (info == null || info.vulkanmod112$chunk() != chunk) {
                continue;
            }
            insertInVisibleOrder(info);
        }
    }

    private void insertInVisibleOrder(RenderInfo info) {
        int order = visibleOrder(info);
        int at = tileSections.size();
        for (int i = 0; i < tileSections.size(); i++) {
            RenderInfo other = tileSections.get(i);
            if (other == info) {
                return;
            }
            if (at == tileSections.size() && visibleOrder(other) > order) {
                at = i;
            }
        }
        tileSections.add(at, info);
    }

    /** Neighbours examined by the last walk. */
    public int tested() {
        return tested;
    }

    private void push(RenderInfo info, int x, int y, int z,
                      int slotX, int slotY, int slotZ, int counter) {
        if (tail == queued.length) {
            RenderInfo[] grownInfo = new RenderInfo[queued.length * 2];
            System.arraycopy(queued, 0, grownInfo, 0, queued.length);
            queued = grownInfo;
            int[] grown = new int[queue.length * 2];
            System.arraycopy(queue, 0, grown, 0, queue.length);
            queue = grown;
        }
        int base = tail * STRIDE;
        queue[base] = x;
        queue[base + 1] = y;
        queue[base + 2] = z;
        queue[base + 3] = slotX;
        queue[base + 4] = slotY;
        queue[base + 5] = slotZ;
        queue[base + 6] = counter;
        queued[tail++] = info;
    }

    private static int floorMod(int value, int modulus) {
        int result = value % modulus;
        return result < 0 ? result + modulus : result;
    }

    private static int abs(int value) {
        return value < 0 ? -value : value;
    }
}
