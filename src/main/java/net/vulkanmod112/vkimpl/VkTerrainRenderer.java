package net.vulkanmod112.vkimpl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkSpecializationInfo;
import org.lwjgl.vulkan.VkSpecializationMapEntry;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;

/**
 * Stage 3.3: Vulkan draws the world's terrain.
 *
 * Per frame the three opaque-ish layers (SOLID, CUTOUT_MIPPED, CUTOUT) are
 * drawn from the VkChunkMirror buffers into a shared color+depth VRAM frame,
 * which a small GL shader then composites into the game's framebuffer —
 * writing gl_FragDepth so entities and translucent geometry keep correct
 * occlusion. Layer state machine: SOLID begins the command buffer, CUTOUT
 * submits it and runs the composite.
 *
 * v0 compromises (documented, deliberate): no fog and no mipmaps on the
 * atlas. Chunk draws are batched through Vulkan indirect commands.
 */
final class VkTerrainRenderer {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Terrain");
    private static final int LIGHTMAP_SIZE = 16;
    private static final int INITIAL_INDIRECT_DRAWS = 4096;
    /**
     * Ceiling on the growth below. 4096 used to be the fixed size, and at
     * render distance 64 a layer has far more visible chunks than that — the
     * surplus was dropped, so the world had holes and the GPU was handed less
     * work than the scene actually contained.
     */
    private static final int MAX_INDIRECT_DRAWS = 1 << 18;
    private static final int DRAW_ORIGIN_BYTES = 16;
    private static final int DRAW_COMMAND_BYTES = 20;

    private final VulkanContextImpl ctx;

    /**
     * Two frames in flight: the CPU waits for frame N-2 instead of N-1, so it
     * no longer sits out the whole GPU render of the previous frame. GPU-side
     * ordering (terrain N → composite N → terrain N+1) is still enforced by
     * the shared-image semaphore chain.
     */
    private final int framesInFlight = resolveFramesInFlight();

    /**
     * How many terrain frames the CPU may prepare before it has to wait for
     * the GPU. Two means waiting on frame N-2 instead of N-1, so the CPU no
     * longer sits out the whole previous render; three gives it more room
     * again at the cost of a frame of input delay and another set of
     * per-frame buffers. Exposed in the settings screen, read once here
     * because every per-frame array is sized from it.
     */
    private static int resolveFramesInFlight() {
        try {
            int value = Integer.parseInt(System.getProperty("vulkanmod112.framesInFlight", "2"));
            return value < 1 ? 1 : (value > 3 ? 3 : value);
        } catch (NumberFormatException ignored) {
            return 2;
        }
    }

    // Stable resources
    private long commandPool;
    private VkCommandBuffer[] commandBuffers;
    private long[] fences;
    /** Aliases of the current slot's entries, set at the top of beginFrame. */
    private VkCommandBuffer commandBuffer;
    private long fence;
    /** Slot selected for the frame currently being recorded. */
    private int activeFrameSlot;
    private long vkSignalSemaphore;
    private long vkWaitSemaphore;
    private int glWaitSemaphore;
    private int glSignalSemaphore;
    /**
     * Running counters for the two semaphore pairs above, used only when they
     * are exported as D3D12 fence handles (see {@link Interop#D3D12_FENCE_SEMAPHORES}).
     *
     * A binary Vulkan semaphore exported this way is a monotonically increasing
     * value underneath, starting at 0 and advancing by one on every signal —
     * Vulkan tracks that itself on its side of a submit, so nothing changes
     * there. GL has no such implicit tracking: EXT_semaphore_win32 requires the
     * target value to be set explicitly with glSemaphoreParameterui64EXT before
     * every wait or signal on a fence-backed semaphore. Each field here counts
     * how many times its own side has signalled its half of the pair, which is
     * exactly the value the other side's next wait must be told to expect —
     * signalFenceValue is bumped where vkSignalSemaphore is signalled
     * (submitFrame) and read where its GL twin waits (composite); waitFenceValue
     * is bumped where glSignalSemaphore is signalled (composite) and is what
     * vkWaitSemaphore's next Vulkan-side wait (submitFrame, next frame)
     * implicitly expects to have reached.
     */
    private long signalFenceValue;
    private long waitFenceValue;

    /**
     * A second hand-off, for the translucent pass, and it has to be its own.
     *
     * The pair above is spent by the time the translucent layer is asked for:
     * OpenGL signals it at the end of the opaque composite, which happens
     * before the game draws entities. The translucent pass needs to wait for
     * something signalled <em>after</em> that — after the depth OpenGL now owns
     * has been copied back — and reusing a semaphore already signalled would
     * let the pass read a depth buffer that is still being written. A race of
     * exactly that kind cost a lost device and a revert on the upload ring.
     */
    private long vkTranslucentSignalSemaphore;
    private long vkTranslucentWaitSemaphore;
    private int glTranslucentWaitSemaphore;
    private int glTranslucentSignalSemaphore;
    /** Same bookkeeping as {@link #signalFenceValue}/{@link #waitFenceValue}, for the pair above. */
    private long translucentSignalFenceValue;
    private long translucentWaitFenceValue;
    private VkCommandBuffer[] translucentCommandBuffers;
    private long[] translucentFences;
    private int translucentCompositeProgram;
    private int translucentInvSizeUniform = -1;
    /**
     * How long the card spends on the OpenGL half of our work.
     *
     * The Vulkan half has been timed since the query pool went in, and it reads
     * as fractions of a millisecond — which was quietly taken to mean the
     * renderer is cheap. It measures the wrong half on the path where the cost
     * lives: composing the terrain into the game's frame, exporting depth per
     * fragment, and now importing it back, all happen in OpenGL and none of it
     * was ever timed. A card is not asked how long it took; it is asked to
     * count for itself and answer a frame later, which is why the result is
     * read on the following pass rather than waited for.
     */
    private final GlTimer compositeTimer = new GlTimer();
    private final GlTimer depthImportTimer = new GlTimer();
    /**
     * The two halves the composite figure was hiding.
     *
     * One number covered the whole of it, and the log said so — "includes
     * waiting for Vulkan". At sixty frames a second that is two per cent of a
     * frame and nobody needs the split. At six hundred it is a fifth of one,
     * and the split is the whole question: a fifth of a frame spent waiting is
     * cured by letting the two sides overlap, and a fifth spent working is
     * cured by doing less work. Those are different projects, and one of them
     * is the largest and riskiest thing on the list.
     *
     * Elapsed-time queries cannot nest, so these cannot sit inside the figure
     * above; they sit beside it, and the wait is taken out of it rather than
     * counted twice.
     */
    private final GlTimer compositeWaitTimer = new GlTimer();
    private boolean compositeTimerRunning;
    /** Handing depth back, which the log has been calling untimed. */
    private final GlTimer depthBlitTimer = new GlTimer();

    // ------------------------------------------------------------------
    // Sprites: particles, rain and snow
    // ------------------------------------------------------------------

    /** Vanilla PARTICLE_POSITION_TEX_COLOR_LMAP: pos 3f | uv 2f | colour 4ub | light 2s. */
    private static final int SPRITE_VERTEX_STRIDE = 28;
    /** 0 is the block atlas, then the particle sheet, rain and snow. */
    /**
     * Sheets that can be in Vulkan at once.
     *
     * Three of these are the game's permanent sheets — particles, rain, snow —
     * and the rest are creature skins, handed out as they are first seen. The
     * number comes from a measurement rather than a guess: a real session drew
     * creatures with sixteen or seventeen distinct textures totalling two
     * tenths of a megabyte, so there is nothing here to evict and no cache to
     * build. If a scene ever needs more than this, the ones past it are drawn
     * by the game as they always were.
     */
    private static final int SPRITE_SLOTS = 48;
    /** Slots below this belong to the game's permanent sheets. */
    private static final int FIRST_SKIN_SLOT = 4;
    /**
     * Ceiling on one frame's sprite geometry, in vertices.
     *
     * The game caps itself at 16 384 particles in each of six queues, which at
     * four vertices each is just under 400 000 — and weather at fancy graphics
     * adds a few thousand more. This is that ceiling with room over it, and it
     * exists so that a mod spawning particles without limit costs a dropped
     * batch and a line in the log rather than an allocation the size of the
     * card.
     */
    private static final int MAX_SPRITE_VERTICES = 1 << 20;

    private long spritePipeline;
    /**
     * The same shaders with the state a solid model needs.
     *
     * Particles and weather are what the sprite pass was built for, and they
     * want the opposite of what a creature wants: blended, writing no depth,
     * both faces drawn. Handing a creature to that state gives exactly what was
     * reported — a mob you can see through, its far side drawn over its near
     * one, and skins that look half transparent. Nothing about the geometry or
     * the shaders is wrong; it is the state around them.
     *
     * Face culling is deliberately still off. The winding of a model quad after
     * our matrix is not yet established, and this renderer already draws with
     * front faces clockwise because of the Y flip — turning culling on with the
     * wrong sense would remove precisely the faces that are currently visible,
     * which is a worse bug than drawing a few extra.
     */
    private long spriteOpaquePipeline;
    /**
     * The same shaders again, in the subpass where depth can be written.
     *
     * Everything about a creature that is not its depth was already solved and
     * measured — the bone poses, the skins, the batching. This is the one state
     * that could not be asked for while the pass had a single read-only depth
     * attachment, and it is the difference between a mob and a mob-shaped
     * arrangement of faces in no particular order.
     */
    private long creaturePipeline;
    private long creatureGlintPipeline;
    private long spritePipelineLayout;
    private long spriteSetLayout;
    private long spriteDescriptorPool;
    private long spriteSampler;
    private final long[] spriteSets = new long[SPRITE_SLOTS];
    private final long[] spriteImages = new long[SPRITE_SLOTS];
    private final long[] spriteMemories = new long[SPRITE_SLOTS];
    private final long[] spriteViews = new long[SPRITE_SLOTS];
    private final int[] spriteGlIds = new int[SPRITE_SLOTS];

    private long[] spriteVertexBuffers;
    private long[] spriteVertexMemories;
    private long[] spriteVertexMapped;
    private long[] spriteVertexCapacity;

    /**
     * This frame's sprite vertices, gathered on the processor before the pass
     * that draws them exists.
     *
     * The game hands particles over a third of the way through the frame and
     * weather right after, but the pass they are drawn in does not begin until
     * the translucent layer — and the buffer that pass reads from may still be
     * in use by a frame two behind. Rather than wait on that fence early, at a
     * point in the frame chosen by nothing in particular, the bytes are parked
     * here and copied across in one move once the fence has been waited for
     * anyway. A few hundred kilobytes of memcpy against a stall of unknown
     * length is not a close call.
     */
    private ByteBuffer spriteScratch;
    private int spriteScratchVertices;
    /** Triples: first vertex, vertex count, texture slot. */
    private int[] spriteBatches = new int[192];
    private float[] spriteCutoffs = new float[64];
    /**
     * A colour laid over each batch's skin, packed ARGB; 0 for none.
     *
     * Beside the batches rather than inside them, the way the cutoffs already
     * are. The batch array is read with a stride in four separate loops and
     * widening it means getting all four right for one number that is zero
     * almost always.
     */
    private int[] spriteOverlays = new int[64];
    /** Which batches are the shimmer of enchanted armour rather than a skin. */
    private boolean[] spriteGlints = new boolean[64];
    private int spriteBatchCount;
    private int spriteFrameVertices;
    private int spriteFrameBatches;
    private long spriteDropped;
    private boolean spriteOverflowLogged;

    /** Shader path for handing the game's depth back to Vulkan; see {@link #buildDepthImportProgram}. */
    private int depthImportProgram;
    private int depthImportInvSizeUniform = -1;
    /** The game's depth, copied where a shader can read it. Its own format, not ours. */
    private int gameDepthTexture;
    /** Draw target whose depth attachment is the shared image. */
    private int glDepthWriteFbo = -1;
    private long renderPass;
    private long descriptorSetLayout;
    private long descriptorPool;
    private long descriptorSet;
    /**
     * Indirect batches and descriptor sets per frame in flight: one for every
     * layer this renderer can draw, translucent included. It was three, which
     * is what {@code activeFrameSlot * 3 + layerOrdinal} indexed by — an
     * arrangement that stops working the moment a fourth layer arrives.
     */
    private static final int BATCHES_PER_FRAME = 4;
    private final long[] drawBatchBuffers = new long[framesInFlight * BATCHES_PER_FRAME];
    private final long[] drawBatchMemories = new long[framesInFlight * BATCHES_PER_FRAME];
    private final long[] drawBatchMapped = new long[framesInFlight * BATCHES_PER_FRAME];
    private final long[] drawDescriptorSets = new long[framesInFlight * BATCHES_PER_FRAME];
    /** Draws each batch buffer can hold; grown to fit the scene, never shrunk. */
    private int indirectDrawCapacity = INITIAL_INDIRECT_DRAWS;
    private long drawCommandOffset = (long) INITIAL_INDIRECT_DRAWS * DRAW_ORIGIN_BYTES;
    private long drawBatchBytes = drawCommandOffset
            + (long) INITIAL_INDIRECT_DRAWS * DRAW_COMMAND_BYTES;
    /** Largest layer of the previous frame; the batch is grown to fit it. */
    private int peakDrawsNeeded;
    /** Whether the indirect batches landed in BAR memory; diagnostics only. */
    private boolean indirectMemoryIsDeviceLocal;
    /** Per-layer lookup results, reused across frames. */
    private VkChunkMirror.Entry[] lookupScratch = new VkChunkMirror.Entry[INITIAL_INDIRECT_DRAWS];
    private long pipelineLayout;
    /**
     * Remembers compiled pipelines between runs. Buys startup time only; see
     * {@link VkPipelineCacheStore} for why every failure in it is silent.
     */
    private final VkPipelineCacheStore pipelineCache = new VkPipelineCacheStore();
    private long pipelineCacheHandle = VK_NULL_HANDLE;
    /**
     * Two specialisations of the same shader modules: index 0 has the alpha
     * test compiled out for SOLID, index 1 keeps it for the CUTOUT layers.
     * Index with {@code layerOrdinal == 0 ? 0 : 1}.
     */
    /**
     * How one terrain pipeline differs from the others.
     *
     * There used to be two of these built by a loop over a bare index, chosen
     * at draw time by {@code layerOrdinal == 0 ? 0 : 1}. That works for exactly
     * as long as the only difference between them is the alpha test, and the
     * translucent layer differs in blending and in depth writes as well —
     * neither of which a specialisation constant can express, because both are
     * pipeline state rather than shader code.
     */
    private static final class TerrainPipeline {
        final String name;
        /** Compiled into the shader; see the constant in terrain.frag. */
        final boolean alphaTest;
        final boolean blend;
        /**
         * Off for anything blended: a translucent surface must not stop what
         * is behind it from being drawn, and the layer is already sorted back
         * to front when the chunk is built.
         */
        final boolean depthWrite;

        TerrainPipeline(String name, boolean alphaTest, boolean blend, boolean depthWrite) {
            this.name = name;
            this.alphaTest = alphaTest;
            this.blend = blend;
            this.depthWrite = depthWrite;
        }
    }

    /**
     * The pipelines this renderer builds, in the order they are created.
     *
     * Adding one is an entry here plus a line in {@link #pipelineForLayer}.
     */
    private static final TerrainPipeline[] TERRAIN_PIPELINES = {
            new TerrainPipeline("solid", false, false, true),
            new TerrainPipeline("cutout", true, false, true),
            new TerrainPipeline("translucent", false, true, false),
    };
    /** Index into {@link #TERRAIN_PIPELINES}; also the layer ordinal vanilla uses. */
    private static final int LAYER_TRANSLUCENT = 3;
    private static final int PIPELINE_TRANSLUCENT = 2;
    /** Vanilla's second opaque layer: leaves, and what casts a canopy's shadow. */
    private static final int LAYER_CUTOUT_MIPPED = 1;
    /** And its third: grass, flowers, crops, rails — cut out of their quads. */
    private static final int LAYER_CUTOUT = 2;
    /**
     * The two subpasses of the translucent pass.
     *
     * Creatures go first because they are the only thing here that writes
     * depth, and everything after them is tested against what they wrote.
     */
    private static final int SUBPASS_CREATURES = 0;
    private static final int SUBPASS_TRANSLUCENT = 1;

    /**
     * Vanilla's layer ordinals: SOLID, CUTOUT_MIPPED, CUTOUT, TRANSLUCENT.
     * The two cutout layers share a pipeline and differ only in their cutoff,
     * which is a push constant. -1 means the layer is not ours to draw.
     */
    private static final int[] LAYER_PIPELINE = {0, 1, 1, PIPELINE_TRANSLUCENT};
    private static final float[] LAYER_CUTOFF = {0.0f, 0.5f, 0.1f, 0.0f};

    private final long[] pipelines = new long[TERRAIN_PIPELINES.length];
    private long atlasSampler;
    private long lightmapSampler;
    /** Clamped and unfiltered, for the reflection reading the finished frame. */
    private long sceneSampler;

    // Atlas / lightmap
    private long atlasImage;
    /**
     * One staging buffer per frame in flight, because the frame that copies out
     * of it is still running when the next tick wants to write.
     *
     * This used to be one buffer, filled and copied inside a submission of its
     * own that the render thread then waited on with vkWaitForFences. Every tick
     * in which any atlas sprite animates — lava, water, fire, a portal, which is
     * to say nearly every scene — the thread drawing the frame stopped until the
     * card had finished the copy. Twenty times a second, before anything else in
     * the frame could happen.
     *
     * Nothing waits now. The pixels are converted where they arrive, into
     * ordinary memory, and the copy is recorded into the frame's own command
     * buffer alongside the lightmap's — where ordering against the shaders that
     * read the atlas is a pipeline barrier rather than a stalled processor.
     */
    private long[] atlasStagingBuffer;
    private long[] atlasStagingMemory;
    private long[] atlasStagingMapped;
    private long[] atlasStagingCapacity;

    /**
     * Pixels waiting for a frame to carry them, already in the image's byte
     * order, plus the regions they belong to.
     *
     * More than one tick can arrive between two frames, and the second one does
     * not replace the first: two ticks touch different sprites, and dropping
     * either freezes an animation. So they accumulate, and the frame records
     * them in the order they came — where two ticks did touch the same sprite,
     * the later copy lands last, which is the right answer.
     */
    private java.nio.ByteBuffer atlasPendingPixels;
    private int atlasPendingBytes;
    private int[] atlasPendingHeader = new int[6 * 128];
    private int atlasPendingHeaderCount;
    /**
     * The most that may pile up before the oldest is thrown away.
     *
     * Reached only when ticks keep coming and frames do not — the window losing
     * focus, a long stall elsewhere. An animation frame missed while nothing is
     * being drawn cannot be seen, and unbounded growth here would be a leak that
     * only shows up on the machine that was already in trouble.
     */
    private static final int ATLAS_PENDING_MAX_BYTES = 8 << 20;
    private long atlasTicksQueued;
    private long atlasFramesCarried;
    private long atlasPendingDropped;
    private long atlasMemory;
    private long atlasView;
    private int atlasWidth;
    private int atlasHeight;
    private int atlasLevels = 1;
    private int lightmapGlId = -1;
    private long lightmapImage;
    private long lightmapMemory;
    private long lightmapView;
    /**
     * Per-frame shader constants: the view-projection matrix and the fog the
     * game set up, one buffer per frame in flight and permanently mapped.
     *
     * These used to be push constants, which put them at 96 of the 128 bytes
     * Vulkan guarantees — with a 16-byte draw parameter on top, that was the
     * room gone. Nothing further could be given to the shaders at all, and
     * everything this version is meant to add needs exactly that. They are also
     * per frame rather than per draw, so pushing them was work repeated for
     * every layer to say the same thing.
     *
     * The write happens while recording the frame that will read it, and the
     * slot's fence has already been waited on by then, so no frame still in
     * flight can be reading the bytes being overwritten.
     */
    private final long[] frameUniformBuffers = new long[framesInFlight];
    private final long[] frameUniformMemories = new long[framesInFlight];
    private final long[] frameUniformMapped = new long[framesInFlight];
    /**
     * mat4 mvp | vec4 fogColor | vec4 fogParams | vec4 lightInfo | vec4 lights[32],
     * padded to a round size. Uniform buffers are guaranteed at least 16 KiB,
     * so there is room here for a good deal more than this holds.
     */
    private static final int MAX_DYNAMIC_LIGHTS = 32;
    /**
     * How much room the frame's uniform block gets.
     *
     * Grown from 1024, which had one vec4 left in it and five new fields
     * wanting a home. Vulkan guarantees at least sixteen kilobytes for a
     * uniform buffer, so this is a constant and not a budget — the reason to
     * keep it tight is that it is written every frame, not that it is scarce.
     * The layout itself is documented in {@code SHADERS.md}, and the shader's
     * own declaration is what has to agree with it.
     */
    private static final int FRAME_UNIFORM_BYTES = 1152;
    private final float[] dynamicLights = new float[MAX_DYNAMIC_LIGHTS * 4];
    private int dynamicLightCount;

    private final long[] lightmapStagingBuffer = new long[framesInFlight];
    private final long[] lightmapStagingMemory = new long[framesInFlight];
    private final long[] lightmapStagingMapped = new long[framesInFlight];
    private ByteBuffer lightmapReadBuffer;
    private boolean lightmapImageInitialized;
    /** Hash of the last uploaded lightmap; see beginFrame. */
    private int lightmapHash;
    private boolean lightmapDirty = true;
    /**
     * How often the lightmap actually changed, against how many frames were
     * drawn. The game recomputes it once a tick, so a healthy ratio is about
     * 20 a second regardless of framerate — which is also why lighting changes
     * look stepped underwater, where the brightness ramps continuously.
     */
    private long lightmapUploads;
    private long lightmapFrames;
    private int[] lightmapData;
    /**
     * rgb + mode, then start/end/density/unused. Mode 0 means the game has fog
     * switched off, and the shader skips the blend entirely.
     */
    private final float[] fogState = new float[8];
    /** Start of the clock the shaders animate from; see writeFrameUniforms. */
    private final long startedNanos = System.nanoTime();
    /** 0 leaves dynamic light exactly as vanilla has it; 1 is the full effect. */
    private float directionalDynamicLight = 1.0f;
    /** Whether this frame has a material buffer bound; see writeFrameUniforms. */
    private boolean materialsBound;
    /** Diagnostic: paint the terrain by material instead of by texture. */
    private boolean showMaterials;
    /** How much of a water surface becomes sky at a grazing angle; 0 is off. */
    private float waterReflection;
    private float screenReflections;
    /** Set when the sets must be rewritten because the effect went on or off. */
    private boolean reflectionBindingsDirty;
    /** Diagnostic: paint the water with what the ray found and nothing else. */
    private boolean showReflections;
    private float nearPlane = 0.05f;
    private float farPlane = 256.0f;

    /**
     * The near and far planes of the projection the game is drawing with.
     *
     * Read rather than carried, for the same reason the corner shading reads
     * it: this runs inside the game's own world pass, so it is still set, and
     * two numbers out of it are all a depth needs to become a distance again.
     * Left at the last good pair if the matrix is not a perspective one, which
     * is what the menu background is.
     */
    private void readProjectionPlanes() {
        projectionMatrix.clear();
        GL11C.glGetFloatv(org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX, projectionMatrix);
        projectionMatrix.get(projectionValues).clear();
        // The two divisions live in Matrices, where they can be run against
        // known answers without a client. They were written out here as well,
        // which is two copies of a formula that decides whether a reflection
        // believes it crossed a surface — and the copy nothing tests is the
        // one that would be quietly edited.
        float n = net.vulkanmod112.client.Matrices.nearPlane(projectionValues);
        float f = net.vulkanmod112.client.Matrices.farPlane(projectionValues);
        // Left at the last good pair for anything that is not a perspective
        // projection, which is what the menu background is: nearPlane returns
        // zero there rather than inventing a number, and zero fails this test.
        if (n > 0.0f && f > n) {
            nearPlane = n;
            farPlane = f;
        }
    }

    /** The projection as a plain array, for the arithmetic above. */
    private final float[] projectionValues = new float[16];
    /** How far the water surface is tilted by the wave pattern; 0 is off. */
    private float waterWaves;
    /** How far the top of a plant leans in the wind; 0 is off. */
    private float foliageSway;
    /**
     * The camera in world coordinates, kept as doubles.
     *
     * Everything else here is camera-relative, which is what the shaders want
     * and what keeps them in single precision. The waves are the one thing that
     * must not be: a pattern anchored to the camera swims along behind the
     * player. Only the remainder modulo the wave lattice ever leaves this side.
     */
    private double viewWorldX;
    private double viewWorldY;
    private double viewWorldZ;

    /**
     * Atlas rectangles the translucent shader classifies its fragments by.
     *
     * Eight is a ceiling with room to spare: water still and flowing, ice, lava
     * still and flowing is five. Each entry is minU, minV, maxU, maxV and the
     * material as a fifth float, laid out as two vec4s in the frame's uniform
     * buffer so the shader can walk them without a second binding.
     */
    private static final int MAX_MATERIAL_SPRITES = 8;
    private final float[] materialSprites = new float[MAX_MATERIAL_SPRITES * 8];
    private int materialSpriteCount;

    synchronized void setMaterialSprites(int[] materials, float[] rects, int count) {
        int used = Math.min(count, MAX_MATERIAL_SPRITES);
        for (int i = 0; i < used; i++) {
            materialSprites[i * 8] = rects[i * 4];
            materialSprites[i * 8 + 1] = rects[i * 4 + 1];
            materialSprites[i * 8 + 2] = rects[i * 4 + 2];
            materialSprites[i * 8 + 3] = rects[i * 4 + 3];
            materialSprites[i * 8 + 4] = materials[i];
            materialSprites[i * 8 + 5] = 0.0f;
            materialSprites[i * 8 + 6] = 0.0f;
            materialSprites[i * 8 + 7] = 0.0f;
        }
        materialSpriteCount = used;
        LOGGER.info("Material sprite table: {} entries", used);
    }
    private float heightFogStrength;
    private float heightFogFalloff = 2.0f / 24.0f;

    // Size-dependent shared targets
    private int width;
    private int height;
    /**
     * Where the translucent layer is drawn, kept apart from the opaque colour.
     *
     * It cannot share it. By the time the game asks for translucent terrain it
     * has already drawn entities, particles and weather into its own
     * framebuffer, and the opaque colour here was composited over there long
     * before any of that. Blending water into this image and compositing it
     * again would draw the terrain twice and lose everything OpenGL added in
     * between. So this one is cleared to fully transparent, receives only the
     * translucent layer, and is composited over the game's frame as a layer of
     * its own.
     */
    private long translucentImage;
    private long translucentMemory;
    private long translucentView;
    private long translucentFramebuffer;
    private long translucentRenderPass;
    private int glTranslucentMemoryObject;
    private int glTranslucentTexture = -1;

    private long colorImage;
    private long colorMemory;
    private long colorView;
    private long depthImage;
    private long depthMemory;
    private long depthView;
    private long framebuffer;
    private int glColorTexture = -1;
    private int glDepthTexture = -1;
    private int glColorMemoryObject;
    private int glDepthMemoryObject;

    // Composite GL programs: [0] writes gl_FragDepth, [1] colour only (depth
    // came from the hardware blit). Index with depthBlit ? 1 : 0.
    private final int[] compositePrograms = new int[2];
    private final int[] compositeAoUniforms = new int[2];
    /**
     * Bloom, done on the OpenGL side because the composite already is.
     *
     * The Vulkan colour target is exported as a GL texture and drawn as a
     * fullscreen quad, so light spilling off a bright surface is three more
     * quads over the same texture rather than a second renderer: pull out what
     * is glowing, blur it across, add it back. Two half-resolution targets,
     * ping-ponged, because a separable blur needs somewhere to put the first
     * half — and blurring at half resolution is most of the blur for a quarter
     * of the work, which matters because what a blur costs is reading
     * neighbours.
     */
    private final int[] bloomTexture = new int[2];
    private final int[] bloomFbo = new int[2];
    /**
     * A second, tighter blur at half resolution, summed with the wide one.
     *
     * One scale cannot do this. A blur spreads a source's light over its area,
     * so the wider it reaches the dimmer it gets, and a lamp post — a column of
     * glowstone a few pixels across at an eighth of the screen — has so little
     * light to spread that a wide blur leaves nothing anyone can see. It was
     * reported as a fifth of a block of glow where a sphere of light was
     * expected. Two scales added together give what a real one looks like: a
     * bright core close in, from the tight blur, and a faint reach from the
     * wide one.
     */
    private final int[] bloomNearTexture = new int[2];
    private final int[] bloomNearFbo = new int[2];
    private int bloomNearWidth;
    private int bloomNearHeight;
    /**
     * The third and widest scale, a thirty-second of the screen.
     *
     * Reach is bought by shrinking rather than by more passes: a blur of a
     * fixed number of taps covers four times the frame for every quartering of
     * the target, and costs a sixteenth as much doing it. Two scales gave a
     * glow about half a block across, which was visible and still not what a
     * lamp looks like in the dark; this one reaches a couple of blocks and
     * carries almost nothing per pixel, which is what the far part of a glow
     * is.
     */
    private final int[] bloomFarTexture = new int[2];
    private final int[] bloomFarFbo = new int[2];
    private int bloomFarWidth;
    private int bloomFarHeight;
    private int bloomWidth;
    private int bloomHeight;
    /**
     * A copy of the emissive mask, taken while the Vulkan target may still be
     * read, at half resolution.
     *
     * The glow is now added after the game has drawn its whole world, and by
     * then the colour target has been handed back to Vulkan through a
     * semaphore — reading it there would be a race with the next frame. The
     * mask is the only thing the last pass still needed from it, so it is taken
     * inside the window and kept here. Half resolution because all it decides
     * is how much of the glow a pixel is allowed to receive, and a boundary two
     * pixels soft on that is better than a hard one, not worse.
     */
    private int bloomMaskTexture;
    private int bloomMaskFbo;
    private int bloomMaskProgram;
    private int bloomMaskInvSize = -1;
    private int bloomMaskAoUniform = -1;
    private int bloomExtractTexel = -1;
    private int bloomDownProgram;
    private int bloomDownInvSize = -1;
    private int bloomDownTexel = -1;
    /** Set when the glow is blurred and waiting; cleared when it is added. */
    private boolean bloomReady;
    private int bloomExtractProgram;
    private int bloomBlurProgram;
    private int bloomAddProgram;
    private int bloomExtractInvSize = -1;
    private int bloomBlurStep = -1;
    private int bloomBlurInvSize = -1;
    private int bloomAddInvSize = -1;
    private int bloomAddStrength = -1;
    /** How much of the glow is added back; 0 is off and skips every pass. */
    private float bloomStrength;
    /** Set once if anything about the bloom targets fails; never retried. */
    private boolean bloomFailed;
    /**
     * Whether the blur targets hold more than eight bits a channel.
     *
     * They have to. A blur spreads a small source's light thin, and the far
     * part of a glow is a very small number — below one step of an eight-bit
     * channel, which rounds it to nothing. Three passes of that in a row and
     * the reach is gone entirely while the bright core survives, which is
     * exactly what was reported: a lamp post lighting its own edges and
     * nothing beyond them. Dropped to eight bits only if the driver refuses.
     */
    private boolean bloomFloat = true;

    /**
     * Ambient occlusion, worked out from the depth this renderer already has.
     *
     * The game shades a block face by which way it points and by nothing else,
     * so an inside corner is lit exactly like an open wall and a room has no
     * shape to it. What is missing is how much of the sky a point can actually
     * see, and that is a question about the neighbourhood rather than about the
     * surface — which means the depth buffer answers it, and the depth buffer
     * is already here as a texture.
     *
     * Half resolution and blurred, because the answer is low-frequency: it is
     * about corners and crevices, not about texels, and sampling it densely
     * would buy noise rather than detail.
     */
    private int aoTexture;
    private int aoFbo;
    private int aoBlurTexture;
    private int aoBlurFbo;
    private int aoProgram;
    private int aoInvSize = -1;
    private int aoProjUniform = -1;
    private int aoRadiusUniform = -1;
    private int aoSunUniform = -1;
    private int aoContactUniform = -1;
    private int aoCloudShadowUniform = -1;
    private int aoCloudUvUniform = -1;
    private int aoSunWorldUniform = -1;
    private int aoCamWrapUniform = -1;
    private int aoCol0Uniform = -1;
    private int aoCol1Uniform = -1;
    private int aoCol2Uniform = -1;
    private int aoStrengthUniform = -1;
    private int aoWidth;
    private int aoHeight;
    private float aoStrength;
    /**
     * How dark a short shadow cast along the ground towards the sun may go.
     *
     * Shares the occlusion pass rather than opening one of its own: both
     * answer a question about the neighbourhood from the same depth image, and
     * the second one costs a loop rather than a pass. Which depth image that
     * is decides what casts — the game's finished one holds chests, creatures
     * and other mods' machines, this renderer's own holds blocks alone.
     */
    private float contactShadows;
    /**
     * How much a creature's own faces shade themselves against the sun.
     *
     * Separate from every other shadow setting because it is not a shadow: it
     * asks which way a face is turned, not what stands between it and the sun.
     */
    private float creatureLight;
    /**
     * How dark the shadow of the game's own clouds may go on the world.
     *
     * Rides the occlusion pass for the same reason the contact shadows do,
     * and is by some distance the cheapest thing on this page: one texture
     * read of a sheet the game already has loaded. What it buys is out of
     * proportion to that — a sky with clouds in it that leave no mark on the
     * ground is the flattest thing in the picture, and moving cloud shade
     * across a landscape reads as depth in the sky without drawing a single
     * cloud of our own.
     */
    private float cloudShadows;

    /** Whether the occlusion pass has anything to do at all. */
    private boolean aoWanted() {
        return aoStrength > 0.0f || contactShadows > 0.0f || cloudShadows > 0.0f;
    }
    /**
     * How much of a gradient the sky is given, 0 for the sky the game drew.
     *
     * Vanilla's sky is one colour with a warm band at the horizon at dawn and
     * dusk, and nothing between: straight up is the same blue as thirty degrees
     * up. Every shader pack deepens the zenith, and it is the largest single
     * area of the screen this mod had never touched.
     */
    private float skyGradient;

    /**
     * The animation clock, held at this value when it is not negative.
     *
     * {@code -Dvulkanmod112.frozenSeconds=<n>}. See where it is read for why a
     * moving clock makes two frames incomparable.
     */
    private final float frozenSeconds =
            Float.parseFloat(System.getProperty("vulkanmod112.frozenSeconds", "-1"));
    /**
     * How see-through leaves and plants are to a shadow ray, 0 for solid.
     *
     * A ray cannot read a texture, so this is a probability rather than a
     * cut-out: at full strength a leaf quad stops light about as often as a leaf
     * texture is opaque, and the frame accumulation turns the speckle into
     * dapple. Off by default like every other effect here, and worth saying why
     * it has a slider rather than a switch — halfway between is a canopy that
     * is thinner than it looks, which some people will prefer to either end.
     */
    private float leafShadows;
    private int skyGradientProgram;
    private int skyGradientStrengthUniform;
    private int skyGradientTopUniform;
    private int skyGradientGlowUniform;
    private int skyGradientSunUniform;
    private int skyGradientDayUniform;
    private int skyGradientInvMvpUniform;
    private int skyGradientInvSizeUniform;
    private final float[] skyInverse = new float[16];
    private final java.nio.FloatBuffer skyMatrixBuffer =
            org.lwjgl.BufferUtils.createFloatBuffer(16);
    /** How far a corner's shadow reaches, in blocks. Set from the menu. */
    private float aoRadius = 2.0f;
    private boolean aoFailed;
    /**
     * Where each pixel of this frame stood in the last one.
     *
     * Nothing on screen changes because of this. It is what every effect that
     * wants to remember something needs and none of them can have without it: a
     * reflection or a shadow worked out from a handful of samples is too noisy
     * to use on its own, and the way that is made usable is by adding this
     * frame's answer to the ones before it — which cannot be done until it is
     * known which pixel of the last frame was looking at the same place in the
     * world.
     *
     * Two matrices and a distance are the whole of it. The position a depth
     * comes back to is measured from the camera, so between frames the origin
     * itself has moved, and the camera's own step has to be added back before
     * last frame's matrix is asked where that point was. That step is taken in
     * double and crosses as a small number — the same reason the wave lattice
     * exists, and for once the two effects want exactly the same thing.
     */
    private int motionTexture;
    private int motionFbo;
    private int motionProgram;
    private int motionInvSizeUniform = -1;
    private int motionReprojectUniform = -1;
    private int motionWidth;
    private int motionHeight;
    private boolean motionFailed;
    /** Diagnostic: paint the frame with the motion instead of the world. */
    private boolean showMotion;
    private final float[] currentMvp = new float[16];
    private final float[] previousMvp = new float[16];
    private boolean hasPreviousFrame;
    private double previousViewX;
    private double previousViewY;
    private double previousViewZ;
    private final float[] reprojectMatrix = new float[16];
    private final java.nio.FloatBuffer reprojectBuffer =
            org.lwjgl.BufferUtils.createFloatBuffer(16);

    /**
     * The frame averaged into the ones before it, which is how a shadow traced
     * with one ray per pixel stops looking like sand.
     *
     * <h2>Why one ray and an average rather than more rays</h2>
     *
     * A soft shadow edge is an average over the source's width, and a fragment
     * shader can only take that average by tracing more rays — which multiplies
     * the cost of the one thing in this renderer that is already the expensive
     * part. Spreading the samples over time instead costs one extra fullscreen
     * pass, total, however many frames are averaged.
     *
     * <h2>The two halves, and that neither works alone</h2>
     *
     * The terrain shader turns its dither pattern by a different amount each
     * frame, so successive frames trace *different* rays; this pass finds where
     * each pixel was last frame and mixes what was there into what is here. Put
     * one in without the other and it is strictly worse than doing nothing: a
     * turning pattern with no averaging is a crawling shadow edge, and averaging
     * a pattern that does not turn averages a hundred copies of one answer.
     *
     * <h2>What keeps it from smearing the world</h2>
     *
     * Three things, in order of how much they matter. The history is clamped
     * into the range of the nine pixels around this one, so a pixel that has
     * genuinely changed cannot keep showing what used to be there — this is what
     * makes it safe without a depth test. Reprojection off the edge of the
     * screen, or onto sky, takes no history at all. And the weight falls away
     * with how fast the pixel is moving across the screen, because history
     * resampled through a bilinear filter every frame is history slowly turning
     * to blur, and standing still — where a moving camera is not hiding the
     * grain anyway — is exactly the case worth the most.
     */
    private final int[] accumTexture = new int[2];
    private final int[] accumFbo = new int[2];
    private int accumWidth;
    private int accumHeight;
    private int accumIndex;
    private boolean accumHasHistory;
    private boolean accumFailed;
    private int accumProgram;
    private int accumInvSizeUniform = -1;
    private int accumBlendUniform = -1;
    private int accumShowUniform = -1;
    /** 0 = off; how much of the history a still pixel keeps. */
    private float accumStrength;
    /** Diagnostic: paint the weight the history was given instead of the world. */
    private boolean showAccumulation;
    /** Set by the pass, read by the composite and by the bloom mask. */
    private boolean accumApplied;
    /** Turned each frame while accumulating, so the shader traces a new ray. */
    private float ditherTurn;
    /**
     * How far apart the blur's taps stand when it is smoothing occlusion rather
     * than a glow.
     */
    private static final float AO_BLUR_SPREAD = 2.0f;
    /**
     * Wider than the occlusion's, because what is being smoothed is coarser.
     *
     * A shaft is gathered by asking twenty-four points along a line whether the
     * sky is visible there, and the answer at each is yes or no — so the whole
     * effect can only take twenty-five values, and along the edge of a shaft it
     * steps down through them one at a time. The per-pixel offset that starts
     * each walk turns those steps into grain rather than removing them, and the
     * grain then arrives at full size through a half-resolution texture. The
     * occlusion beside it has been blurred for exactly this reason since it was
     * written; the shafts never were, and stairs in a beam of light was the
     * first thing anyone said about them.
     */
    private static final float RAY_BLUR_SPREAD = 3.0f;
    private final java.nio.FloatBuffer projectionMatrix =
            org.lwjgl.BufferUtils.createFloatBuffer(16);
    /**
     * The modelview the world is being drawn with, for turning the sun from
     * world axes into the ones the occlusion pass reconstructs positions in.
     *
     * Read from the driver rather than carried, exactly as the projection
     * beside it is, and for the same reason: this runs inside the game's own
     * world pass, where both are still set to what drew the picture.
     */
    private final java.nio.FloatBuffer modelViewMatrix =
            org.lwjgl.BufferUtils.createFloatBuffer(16);
    private final int[] compositeAoOnlyUniforms = {-1, -1};
    private final int[] compositeMotionUniforms = {-1, -1};
    private final int[] compositeMotionGhostUniforms = {-1, -1};
    private final int[] compositeAccumUniforms = {-1, -1};
    /** Diagnostic: show the motion over a ghost of the world instead of black. */
    private boolean motionOverWorld;
    private final int[] compositeInvSizeUniforms = {-1, -1};
    /** Diagnostic: draw the occlusion on its own instead of applying it. */
    private boolean showOcclusion;
    private boolean showCreatureLight;
    /**
     * Copying depth with glBlitFramebuffer instead of writing gl_FragDepth
     * lets the composite quad keep early-Z and skips a per-pixel depth export.
     * Requires our depth target to match the game's depth format, so it is
     * only attempted when the D24 target was created, and switched off for
     * good if the driver rejects the blit.
     */
    private boolean depthBlit;
    private int glDepthBlitFbo = -1;
    /** 0 until the first format query; see depthFormat(MemoryStack). */
    private int depthFormat;

    // Shared quad→triangle index buffer (pattern 0,1,2 / 0,2,3 per quad):
    // vanilla chunk VBOs hold GL_QUADS, Vulkan only rasterizes triangles
    private long quadIndexBuffer;
    private long quadIndexMemory;
    private int quadIndexCapacityQuads;

    private boolean baseReady;
    private boolean firstFrame = true;

    /**
     * Cleared once a whole frame has been through the composite, so this costs
     * one frame's worth of log lines and nothing afterwards.
     *
     * It exists because the machines that die here die between two log lines
     * that are eleven method calls apart, taking the process with them — no
     * exception, no crash report, nothing after "Quad index buffer sized". A
     * log that stops is not evidence of where it stopped, and on a driver that
     * is killed by the operating system there is no second chance to ask.
     */
    private boolean tracingFirstFrame = true;

    /**
     * Whether the two APIs are allowed to hand the shared images to each other
     * with semaphores rather than by both going idle.
     *
     * Semaphores are the whole point of the interop: neither side stalls, the
     * card stays fed. But a driver that accepts an imported semaphore and never
     * signals it does not fail — it stops, and Windows eventually takes the
     * process with the card. Turning this off replaces the handshake with a
     * full stop on each side per frame. That is slower by a wide margin and it
     * is correct, which beats a machine that cannot open a world at all.
     */
    private static final boolean SHARED_SEMAPHORES =
            !"false".equals(System.getProperty("vulkanmod112.interopSemaphores"));

    /**
     * The layout the images shared with OpenGL are left in between frames.
     *
     * The semaphore calls are the only place the two APIs ever agree on a
     * layout: glWaitSemaphoreEXT and glSignalSemaphoreEXT carry one per texture
     * and nothing else does. Switching them off therefore removes the agreement
     * along with the wait, and leaves OpenGL reading images in a layout it was
     * never told about — which was a defect in that fallback rather than an
     * observation about the driver it was written for.
     *
     * GENERAL is the layout that is valid for every access and the one an
     * importing API assumes when it has been told nothing. It costs a little on
     * the Vulkan side, which is beside the point on a path already stopping
     * both APIs dead once a frame.
     */
    private static int sharedLayout() {
        return SHARED_SEMAPHORES
                ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                : VK_IMAGE_LAYOUT_GENERAL;
    }

    /**
     * The layout the shared depth image is in when OpenGL hands it back for the
     * translucent pass — which is not the one it was lent out in.
     *
     * It goes out for the composite to sample, comes back written as an
     * attachment, and the name OpenGL signals has to be the name Vulkan
     * expects. Kept beside {@link #sharedLayout} so the pair cannot drift, and
     * paired in turn with the layout named in {@link #importGlDepth}: those two
     * places are the entire agreement, and there is no third place where a
     * disagreement between them would show up as anything but a dead card.
     */
    private static int depthHandoffLayout() {
        return SHARED_SEMAPHORES
                ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                : VK_IMAGE_LAYOUT_GENERAL;
    }

    /** The same layout under the name OpenGL knows it by. */
    private static int glDepthHandoffLayout() {
        return SHARED_SEMAPHORES
                ? EXTSemaphore.GL_LAYOUT_DEPTH_STENCIL_ATTACHMENT_EXT
                : EXTSemaphore.GL_LAYOUT_GENERAL_EXT;
    }

    /**
     * Whether the shared images change hands between Vulkan and OpenGL by an
     * explicit ownership transfer.
     *
     * They are created VK_SHARING_MODE_EXCLUSIVE, and an exclusive image handed
     * to another API has to be released to VK_QUEUE_FAMILY_EXTERNAL and taken
     * back afterwards — the specification says so and this renderer never did
     * it. Two drivers let that pass and one does not, which is the whole story
     * of a machine that draws the world under one operating system and stops
     * the card under the other.
     *
     * Windows only by default: the drivers this has been shown to work on have
     * been running without it for every version so far, and there is no reason
     * to hand them a change they cannot benefit from.
     */
    private static final boolean EXTERNAL_QUEUE_TRANSFER = queueTransferWanted();

    private static boolean queueTransferWanted() {
        String setting = System.getProperty("vulkanmod112.externalQueueTransfer");
        if (setting != null) {
            return !"false".equals(setting);
        }
        return Interop.WINDOWS;
    }

    /**
     * Releases the shared colour and depth images to OpenGL, or takes them back.
     *
     * Both sit in SHADER_READ_ONLY_OPTIMAL between frames, which is where the
     * render pass leaves them and what the composite samples. The layout does
     * not move here — only the ownership does, and the pair has to match: a
     * release without its acquire leaves the next frame writing to an image it
     * does not hold.
     */
    private void transferSharedImages(MemoryStack stack, VkCommandBuffer cmd, boolean release) {
        if (!EXTERNAL_QUEUE_TRANSFER || colorImage == 0 || depthImage == 0) {
            return;
        }
        int external = org.lwjgl.vulkan.VK11.VK_QUEUE_FAMILY_EXTERNAL;
        int owner = ctx.getGraphicsQueueFamily();
        VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(2, stack);
        long[] images = {colorImage, depthImage};
        int[] aspects = {VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_ASPECT_DEPTH_BIT};
        for (int i = 0; i < 2; i++) {
            barriers.get(i)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(release ? VK_ACCESS_SHADER_READ_BIT : 0)
                    .dstAccessMask(release ? 0 : VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(sharedLayout())
                    .newLayout(sharedLayout())
                    .srcQueueFamilyIndex(release ? owner : external)
                    .dstQueueFamilyIndex(release ? external : owner)
                    .image(images[i]);
            barriers.get(i).subresourceRange()
                    .aspectMask(aspects[i])
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        }
        vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, null, null, barriers);
    }

    private void firstFrameStage(String stage) {
        if (tracingFirstFrame) {
            LOGGER.info("First terrain frame: {}", stage);
        }
    }
    private boolean frameOpen;

    /**
     * The startup coverage readback, off unless asked for.
     *
     * On the second and hundred-and-twentieth frame this used to stall the
     * pipeline with {@code glFinish} and pull the whole colour attachment back
     * across the bus — at 4K that is some thirty megabytes and a full stop of
     * both processors, twice, in the seconds where the world is being built and
     * the frame budget matters most. It answered one question, once: whether
     * this renderer was putting anything on screen at all. That question has
     * been answered, and the check for it sat in the per-draw path of every
     * frame ever since.
     *
     * {@code -Dvulkanmod112.startupReadback=true} brings it back for the next
     * time something is drawing nothing.
     */
    private static final boolean STARTUP_READBACK =
            Boolean.getBoolean("vulkanmod112.startupReadback");

    // Diagnostics (first frames are logged with a coverage readback)
    private long frameCounter;
    private int frameChunks;
    private int frameVertices;
    private int frameSkipped;
    private boolean glErrorLogged;
    // Frame-time breakdown, averaged and logged every TIMING_WINDOW frames
    private static final int TIMING_WINDOW = 600;
    private long fenceWaitNanos;
    private long recordNanos;
    /**
     * Time spent waiting for the card before the translucent pass, apart.
     *
     * Kept out of the recording figure rather than added to it: the two say
     * opposite things about where a slow frame went, and adding them together
     * makes the sum mean neither.
     */
    private long translucentWaitNanos;
    private long previousTranslucentWaitNanos;
    private long submitCompositeNanos;
    private long timingWindowStartNanos;
    // GPU-side cost of the terrain pass, read back from timestamp queries one
    // frame late (the fence for a slot guarantees its queries have landed)
    private long queryPool;
    private float timestampPeriod;
    private boolean timestampsSupported;
    private long gpuNanos;
    private long gpuTranslucentNanos;
    private int gpuTranslucentSamples;
    private int gpuSamples;
    // VK-side readback of a horizontal strip of the color target: tells apart
    // "Vulkan drew nothing" from "GL cannot see what Vulkan drew"
    private static final int READBACK_ROWS = 8;
    private long readbackBuffer;
    private long readbackMemory;
    private long readbackMapped;
    private boolean readbackRecorded;

    VkTerrainRenderer(VulkanContextImpl ctx) {
        this.ctx = ctx;
    }

    private VkDevice device() {
        return ctx.getDevice();
    }

    // ------------------------------------------------------------------
    // Public entry points (called via the bridge, client thread)
    // ------------------------------------------------------------------

    /**
     * Replaces rectangles of the atlas with the animation frames of one tick.
     *
     * The copy of the atlas here is made once, out of OpenGL, and the game goes
     * on writing new frames into its own texture for as long as the world is
     * open. Without this the terrain shows whichever frame the atlas happened
     * to hold when it was read: lava, water, fire, portals and sea lanterns all
     * standing still, while the same block held in the hand — drawn by OpenGL
     * from the game's own texture — animates as it always did.
     *
     * Everything the tick produced arrives together and leaves in one
     * submission. Per sprite it would be a queue submission and a wait apiece,
     * twenty times a second, for a few kilobytes each.
     */
    synchronized void updateAtlasRegions(int[] header, int headerCount, int[] pixels, int pixelCount) {
        if (atlasImage == 0 || headerCount == 0 || pixelCount == 0) {
            return;
        }
        int bytes = pixelCount * 4;
        if (atlasPendingBytes + bytes > ATLAS_PENDING_MAX_BYTES) {
            // Frames have stopped coming. Start again from this tick rather than
            // growing without limit; what is thrown away is animation nobody is
            // looking at.
            atlasPendingBytes = 0;
            atlasPendingHeaderCount = 0;
            atlasPendingDropped++;
        }
        ensurePendingCapacity(atlasPendingBytes + bytes);
        if (atlasPendingHeaderCount + headerCount > atlasPendingHeader.length) {
            atlasPendingHeader = java.util.Arrays.copyOf(atlasPendingHeader,
                    Math.max(atlasPendingHeaderCount + headerCount, atlasPendingHeader.length * 2));
        }
        // The game's pixels are 0xAARRGGBB in an int; the image wants the bytes
        // in the order red, green, blue, alpha. Written straight into the
        // scratch rather than through a ByteBuffer view, because this runs every
        // tick and the conversion is the whole cost.
        long dst = MemoryUtil.memAddress(atlasPendingPixels) + atlasPendingBytes;
        for (int i = 0; i < pixelCount; i++) {
            int argb = pixels[i];
            MemoryUtil.memPutByte(dst++, (byte) (argb >> 16));
            MemoryUtil.memPutByte(dst++, (byte) (argb >> 8));
            MemoryUtil.memPutByte(dst++, (byte) argb);
            MemoryUtil.memPutByte(dst++, (byte) (argb >>> 24));
        }
        // The offsets in the header count pixels from the start of this tick's
        // array; they have to count from the start of everything waiting.
        int pixelsAlready = atlasPendingBytes / 4;
        int kept = 0;
        for (int i = 0; i < headerCount; i += 6) {
            // Into this side's numbering, and dropped rather than remapped when
            // it lands below the bottom of the mirror. Under flat colours the
            // full-size levels are not in the image at all, so a copy still
            // carrying the game's level number would write into whichever level
            // happens to hold that number here — the wrong sprite, at the wrong
            // size, once a tick.
            int level = header[i] - atlasBaseLevel;
            if (level < 0) {
                continue;
            }
            int at = atlasPendingHeaderCount + kept;
            atlasPendingHeader[at] = level;
            atlasPendingHeader[at + 1] = header[i + 1];
            atlasPendingHeader[at + 2] = header[i + 2];
            atlasPendingHeader[at + 3] = header[i + 3];
            atlasPendingHeader[at + 4] = header[i + 4];
            atlasPendingHeader[at + 5] = header[i + 5] + pixelsAlready;
            kept += 6;
        }
        atlasPendingHeaderCount += kept;
        atlasPendingBytes += bytes;
        atlasTicksQueued++;
    }

    /**
     * Whether every waiting region still lands inside the atlas.
     *
     * Cheap — a handful of comparisons per animated sprite — and it is the only
     * thing standing between a resource reload arriving between two frames and
     * a copy that writes past the end of an image.
     */
    private boolean pendingFitsAtlas() {
        for (int base = 0; base < atlasPendingHeaderCount; base += 6) {
            int level = atlasPendingHeader[base];
            if (level < 0 || level >= atlasLevels) {
                return false;
            }
            int levelWidth = Math.max(1, atlasWidth >> level);
            int levelHeight = Math.max(1, atlasHeight >> level);
            int x = atlasPendingHeader[base + 1];
            int y = atlasPendingHeader[base + 2];
            int w = atlasPendingHeader[base + 3];
            int h = atlasPendingHeader[base + 4];
            if (x < 0 || y < 0 || w <= 0 || h <= 0
                    || x + w > levelWidth || y + h > levelHeight) {
                return false;
            }
            long need = ((long) atlasPendingHeader[base + 5] + (long) w * h) * 4L;
            if (need > atlasPendingBytes) {
                return false;
            }
        }
        return true;
    }

    private void ensurePendingCapacity(int bytes) {
        if (atlasPendingPixels != null && atlasPendingPixels.capacity() >= bytes) {
            return;
        }
        int want = Math.max(bytes, atlasPendingPixels == null
                ? 1 << 20 : atlasPendingPixels.capacity() * 2);
        java.nio.ByteBuffer grown = MemoryUtil.memAlloc(want);
        if (atlasPendingPixels != null) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(atlasPendingPixels),
                    MemoryUtil.memAddress(grown), atlasPendingBytes);
            MemoryUtil.memFree(atlasPendingPixels);
        }
        atlasPendingPixels = grown;
    }

    /**
     * Puts whatever the ticks have piled up into this frame's command buffer.
     *
     * Called where the lightmap's upload is called, and for the same reason:
     * inside one command buffer, a barrier is enough to order a copy against the
     * shaders that read what it wrote, and nothing on the processor has to wait
     * to find that out.
     */
    private void recordAtlasUpload(MemoryStack stack) {
        if (atlasPendingBytes == 0 || atlasImage == 0) {
            return;
        }
        if (!pendingFitsAtlas()) {
            // The regions were measured against an atlas that no longer exists —
            // a resource pack changed, or the mipmap slider moved. Copying them
            // into the new one would write outside it, and a write outside an
            // image is not a wrong pixel, it is the card faulting.
            atlasPendingBytes = 0;
            atlasPendingHeaderCount = 0;
            atlasPendingDropped++;
            return;
        }
        int slot = activeFrameSlot;
        if (!ensureAtlasStaging(stack, slot, atlasPendingBytes)) {
            // No staging, no upload. The pixels stay pending rather than being
            // thrown away: the next frame may well find the memory.
            return;
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(atlasPendingPixels),
                atlasStagingMapped[slot], atlasPendingBytes);

        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(atlasImage)
                .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        barrier.get(0).subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS)
                .baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

        int regions = atlasPendingHeaderCount / 6;
        VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(regions, stack);
        for (int r = 0; r < regions; r++) {
            int base = r * 6;
            final int level = atlasPendingHeader[base];
            final int x = atlasPendingHeader[base + 1];
            final int y = atlasPendingHeader[base + 2];
            final int w = atlasPendingHeader[base + 3];
            final int h = atlasPendingHeader[base + 4];
            copy.get(r)
                    .bufferOffset((long) atlasPendingHeader[base + 5] * 4L)
                    .bufferRowLength(0)
                    .bufferImageHeight(0)
                    .imageOffset(o -> o.x(x).y(y).z(0))
                    .imageExtent(e -> e.width(w).height(h).depth(1));
            copy.get(r).imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level).baseArrayLayer(0).layerCount(1);
        }
        vkCmdCopyBufferToImage(commandBuffer, atlasStagingBuffer[slot], atlasImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);

        barrier.get(0)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier);

        atlasPendingBytes = 0;
        atlasPendingHeaderCount = 0;
        atlasFramesCarried++;
    }

    private boolean ensureAtlasStaging(MemoryStack stack, int slot, long bytes) {
        if (atlasStagingBuffer == null) {
            atlasStagingBuffer = new long[framesInFlight];
            atlasStagingMemory = new long[framesInFlight];
            atlasStagingMapped = new long[framesInFlight];
            atlasStagingCapacity = new long[framesInFlight];
        }
        if (atlasStagingBuffer[slot] != 0 && bytes <= atlasStagingCapacity[slot]) {
            return true;
        }
        // Only this slot's buffer, and only when this slot's fence has already
        // been waited on — which is true wherever this is called from.
        destroyAtlasStaging(slot);
        VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(bytes)
                .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer pBuffer = stack.mallocLong(1);
        if (vkCreateBuffer(device(), info, null, pBuffer) != VK_SUCCESS) {
            return false;
        }
        atlasStagingBuffer[slot] = pBuffer.get(0);
        VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
        vkGetBufferMemoryRequirements(device(), atlasStagingBuffer[slot], req);
        VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
        LongBuffer pMemory = stack.mallocLong(1);
        if (vkAllocateMemory(device(), alloc, null, pMemory) != VK_SUCCESS) {
            vkDestroyBuffer(device(), atlasStagingBuffer[slot], null);
            atlasStagingBuffer[slot] = 0;
            return false;
        }
        atlasStagingMemory[slot] = pMemory.get(0);
        check(vkBindBufferMemory(device(), atlasStagingBuffer[slot], atlasStagingMemory[slot], 0),
                "vkBindBufferMemory(atlas staging)");
        PointerBuffer pMapped = stack.mallocPointer(1);
        check(vkMapMemory(device(), atlasStagingMemory[slot], 0, req.size(), 0, pMapped),
                "vkMapMemory(atlas staging)");
        atlasStagingMapped[slot] = pMapped.get(0);
        atlasStagingCapacity[slot] = bytes;
        return true;
    }

    private void destroyAtlasStaging(int slot) {
        if (atlasStagingBuffer == null) {
            return;
        }
        if (atlasStagingMemory[slot] != 0) {
            vkUnmapMemory(device(), atlasStagingMemory[slot]);
            vkFreeMemory(device(), atlasStagingMemory[slot], null);
            atlasStagingMemory[slot] = 0;
        }
        if (atlasStagingBuffer[slot] != 0) {
            vkDestroyBuffer(device(), atlasStagingBuffer[slot], null);
            atlasStagingBuffer[slot] = 0;
        }
        atlasStagingMapped[slot] = 0;
        atlasStagingCapacity[slot] = 0;
    }

    private void destroyAtlasStaging() {
        if (atlasStagingBuffer != null) {
            for (int i = 0; i < atlasStagingBuffer.length; i++) {
                destroyAtlasStaging(i);
            }
        }
        if (atlasPendingPixels != null) {
            MemoryUtil.memFree(atlasPendingPixels);
            atlasPendingPixels = null;
        }
        atlasPendingBytes = 0;
        atlasPendingHeaderCount = 0;
    }

    synchronized void updateAtlas(int atlasGlId) {
        ctx.ensureGlCapabilities();
        // Remembered so that the flat-colour switch can rebuild this copy on
        // the spot. It decides how much of the chain is mirrored, which is not
        // something a sampler can be told after the fact.
        this.atlasGlId = atlasGlId;
        destroyAtlas();
        // The atlas is rebuilt on a resource reload, and so is every other
        // sheet the game owns: their GL names are handed out again from
        // scratch. Keeping copies made from the old ones would draw last
        // pack's rain.
        forgetSpriteSheets();
        try (MemoryStack stack = stackPush()) {
            int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, atlasGlId);
            atlasWidth = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
            atlasHeight = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
            // Minecraft builds the atlas mip chain per sprite, so colours never
            // bleed between neighbouring textures. Copying those levels is both
            // cheaper and more correct than generating our own with vkCmdBlitImage.
            ByteBuffer[] levels = readAtlasLevels();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
            try {
                long[] imageOut = new long[3];
                createSampledImage(stack, atlasWidth, atlasHeight, levels, imageOut);
                atlasImage = imageOut[0];
                atlasMemory = imageOut[1];
                atlasView = imageOut[2];
            } finally {
                for (ByteBuffer level : levels) {
                    MemoryUtil.memFree(level);
                }
            }
        }
        updateDescriptors();
        VertexLayout.noteAtlas(Math.max(atlasWidth, atlasHeight));
        LOGGER.info("Block atlas copied to Vulkan: {}x{}, {} mip level(s)",
                atlasWidth, atlasHeight, atlasLevels);
    }

    /**
     * Reads the mip levels of the bound GL atlas that this renderer will
     * actually sample, and only those.
     *
     * Normally that is all of them. Under flat colours it is the last two: the
     * sampler is pinned near the end of the chain, so everything below is
     * copied into video memory to be read exactly never. On a bare game that
     * waste is a megabyte and not worth a line of code; on a three-hundred-mod
     * pack the atlas is 8192x4096 and the chain is 179 MB, of which the two
     * levels in use are 2.6. The rest is 176 MB held against a machine whose
     * whole reason for being on this preset is that it has none to spare —
     * and on the integrated graphics that preset is written for, that memory
     * is the system's, taken from the game rather than from a card.
     *
     * The count is taken first, by asking each level its width and reading no
     * pixels, because the point is not to pull the full-size level across at
     * all — not to pull it and then drop it.
     */
    private ByteBuffer[] readAtlasLevels() {
        int levelCount = 0;
        int w = atlasWidth;
        int h = atlasHeight;
        while (w >= 1 && h >= 1) {
            if (levelCount > 0
                    && GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, levelCount,
                            GL11C.GL_TEXTURE_WIDTH) != w) {
                break; // mipmaps turned off in video settings, or the chain ends here
            }
            levelCount++;
            w /= 2;
            h /= 2;
        }
        // One level short of the end, which is where the sampler sits: at the
        // very end a sprite is a single texel and an ore block averages into
        // stone. Two levels is what makes that four texels instead of one.
        samplerFlatColours = flatBlockColours();
        atlasBaseLevel = samplerFlatColours ? Math.max(0, levelCount - 2) : 0;
        atlasLevels = levelCount - atlasBaseLevel;
        // From here on these are the mirrored image's dimensions rather than
        // the game's. Nothing that samples cares — texture coordinates are
        // fractions of the whole sheet either way — and everything that writes
        // a rectangle into it needs these and not the game's.
        atlasWidth = Math.max(1, atlasWidth >> atlasBaseLevel);
        atlasHeight = Math.max(1, atlasHeight >> atlasBaseLevel);
        ByteBuffer[] levels = new ByteBuffer[atlasLevels];
        w = atlasWidth;
        h = atlasHeight;
        for (int i = 0; i < atlasLevels; i++) {
            ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
            GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, atlasBaseLevel + i,
                    GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
            levels[i] = pixels;
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
        return levels;
    }

    /**
     * The game's mip level that became level zero of the mirrored atlas.
     *
     * Zero unless flat colours trimmed the chain. Anything given a level number
     * in the game's numbering has to come through this before it means anything
     * here, and anything below it does not exist on this side at all.
     */
    private int atlasBaseLevel;

    synchronized void setLightmap(int glTextureId) {
        this.lightmapGlId = glTextureId;
        if (lightmapImage == 0) {
            createLightmapResources();
            updateDescriptors();
        }
    }

    /** CPU-side lightmap colors (256 ARGB ints); preferred over glGetTexImage. */
    /** rgb, mode, start, end, density, unused — see {@link #fogState}. */
    synchronized void setFogState(float[] fog) {
        if (fog != null && fog.length >= 7) {
            System.arraycopy(fog, 0, fogState, 0, 7);
        }
    }

    synchronized void setLightmapData(int[] argb) {
        if (argb != null && argb.length == LIGHTMAP_SIZE * LIGHTMAP_SIZE) {
            this.lightmapData = argb;
        }
    }

    /**
     * @return true when the layer was consumed by Vulkan.
     */
    synchronized boolean renderLayer(int layerOrdinal, int[] chunks, int chunkCount, float[] mvp,
                                     double viewX, double viewY, double viewZ,
                                     int fbWidth, int fbHeight, VkChunkMirror mirror) {
        if (mirror == null || atlasImage == 0 || lightmapGlId == -1) {
            return false;
        }
        ctx.ensureGlCapabilities();
        ensureBaseResources();
        // Before the first layer opens the frame, because that is where the
        // uniforms are written and the translucent pass reuses them.
        viewWorldX = viewX;
        viewWorldY = viewY;
        viewWorldZ = viewZ;
        // Before anything is recorded, and only then — which is the half this
        // was missing.
        //
        // A descriptor set that is rewritten while a command buffer holding it
        // is open does not merely race: the specification says that command
        // buffer is invalid from that moment, and every call recorded into it
        // afterwards is a call into nothing. The layers arrive one at a time
        // and the frame is opened by the first of them, so a setting changed
        // between two layers landed here with the buffer already recording.
        // Found by the validation layer the moment somebody switched presets
        // mid-frame — the driver had been quietly carrying on, and the frame
        // looked right, which is how this survived.
        //
        // Skipped rather than deferred by a queue: both of these already ask
        // whether anything changed, so leaving the flag up costs one more test
        // next frame and nothing else. The price is that a settings change can
        // arrive a frame later than the click, which nobody can see.
        if (!frameOpen) {
            if (reflectionBindingsDirty) {
                reflectionBindingsDirty = false;
                updateDescriptors();
            }
            refreshSamplerIfNeeded();
        }
        if (layerOrdinal == LAYER_TRANSLUCENT) {
            // Its own pass, its own submission, and it runs after the opaque
            // frame has already been composited — so none of the state machine
            // below applies to it.
            if (frameOpen || colorImage == 0) {
                return false;
            }
            long t = System.nanoTime();
            long waitBefore = translucentWaitNanos;
            boolean taken = renderTranslucent(chunks, chunkCount, mvp, viewX, viewY, viewZ, mirror);
            // Minus the wait, which this pass does first thing and which is not
            // work at all.
            recordNanos += System.nanoTime() - t - (translucentWaitNanos - waitBefore);
            return taken;
        }
        if (layerOrdinal == 0) {
            firstFrameStage("creating the shared colour and depth targets");
            ensureTargets(fbWidth, fbHeight);
            firstFrameStage("targets shared with OpenGL");
            // Sized from the previous frame's largest layer as well, so a growth
            // step is not spent on SOLID only to be undone by CUTOUT.
            ensureDrawBatchCapacity(Math.max(chunkCount, peakDrawsNeeded));
            peakDrawsNeeded = chunkCount;
            beginFrame(mvp, mirror);
            // Solid and leaves together, which is not what this did at first:
            // it passed the solid list alone and called it "the whole of the
            // terrain", and it is one of three opaque layers. What that looked
            // like in the world is exactly what it was — a forest casting the
            // shadows of its trunks and nothing else, long lone sticks lying
            // across the ground with no canopy over them.
            //
            // Leaves and nothing further. The layer after this one is crossed
            // quads — grass, flowers, torches, rails — and a ray sees the quad
            // rather than the texture on it, so a tuft of grass would throw the
            // shadow of the whole square it is drawn on. A leaf block is a cube
            // and reads correctly as one.
            //
            // The leaf list is the previous frame's, because this layer is
            // drawn first and that one has not arrived yet. A frame of lag in
            // which chunks cast shadows is not something anybody can see; the
            // alternative is building the structures a layer later and having
            // the solid pass trace against structures that do not include it.
            int traced = combineForTracing(chunks, chunkCount);
            updateRayTracing(tracedChunks, traced, mirror, viewX, viewY, viewZ);
        } else if ((layerOrdinal == LAYER_CUTOUT_MIPPED || layerOrdinal == LAYER_CUTOUT)
                && TRACE_FOLIAGE && ctx.isRayTracingEnabled()) {
            rememberForTracing(layerOrdinal, chunks, chunkCount);
            if (chunkCount > peakDrawsNeeded) {
                peakDrawsNeeded = chunkCount;
            }
        } else if (chunkCount > peakDrawsNeeded) {
            peakDrawsNeeded = chunkCount;
        }
        if (!frameOpen) {
            return false; // out-of-order layer call (frame not started): let GL draw it
        }
        long t0 = System.nanoTime();
        drawChunks(layerOrdinal, chunks, chunkCount, mvp, viewX, viewY, viewZ, mirror);
        recordNanos += System.nanoTime() - t0;
        if (layerOrdinal == 2) {
            long t1 = System.nanoTime();
            submitFrame();
            composite();
            rememberFrame();
            submitCompositeNanos += System.nanoTime() - t1;
            frameCounter++;
            notePacing();
            logFrameDiagnostics();
            logFrameTimings();
        }
        return true;
    }

    /**
     * How long each frame actually took, so that a stutter stops being a word.
     *
     * The averages printed elsewhere cannot show this. A frame that takes forty
     * milliseconds once a second is invisible in a mean over three hundred
     * frames and is the single thing a player calls a lag. What is kept here is
     * the distribution — the middle, the worst one in twenty, the worst one at
     * all — and, for the worst frame, where its time went.
     *
     * The clock is read at the end of our own work, so the gap between two
     * readings is the whole frame including everything the game does that this
     * renderer has no part in. That is deliberate: a stall in vanilla's chunk
     * queue and a stall in our upload both show up, and telling them apart is
     * exactly what the breakdown beside the worst frame is for.
     */
    private final long[] frameGaps = new long[1024];
    private int frameGapAt;
    private int frameGapCount;
    private long lastFrameEndNanos;
    private long previousFenceWaitNanos;
    private long previousRecordNanos;
    private long previousSubmitNanos;
    private long worstGapNanos;
    private long worstGapFrame;
    private long worstGapFence;
    private long worstGapRecord;
    private long worstGapSubmit;
    private long worstGapWait;
    /**
     * Collector pauses inside the worst frame, and across the interval.
     *
     * The one explanation that fits a stall on an unchanged scene once in a
     * thousand frames, and the one nothing here ever measured. The machine
     * stops every thread when it collects, so a frame that contains a
     * collection is not this renderer's frame to explain — and every hour
     * spent looking for the defect elsewhere was spent because nobody asked.
     */
    private long worstGapCollections;
    private long worstGapCollectMillis;
    private long previousCollections;
    private long previousCollectMillis;
    private long intervalCollections;
    private long intervalCollectMillis;
    /** How long the render thread waits for the mirror's monitor. */
    private long mirrorLookupNanos;
    private long mirrorLookups;
    private long worstMirrorLookupNanos;

    /** Frames the background cap held back; they are sleeps, not stalls. */
    private boolean frameThrottled;
    private long throttledFrames;

    private void notePacing() {
        long end = System.nanoTime();
        long fence = fenceWaitNanos - previousFenceWaitNanos;
        long record = recordNanos - previousRecordNanos;
        long waited = translucentWaitNanos - previousTranslucentWaitNanos;
        long collections = net.vulkanmod112.client.JvmPauses.collections();
        long collectMillis = net.vulkanmod112.client.JvmPauses.collectionMillis();
        long collected = previousCollections == 0L ? 0L : collections - previousCollections;
        long collectedMs = previousCollectMillis == 0L
                ? 0L : collectMillis - previousCollectMillis;
        previousCollections = collections;
        previousCollectMillis = collectMillis;
        intervalCollections += collected;
        intervalCollectMillis += collectedMs;
        long submit = submitCompositeNanos - previousSubmitNanos;
        previousFenceWaitNanos = fenceWaitNanos;
        previousRecordNanos = recordNanos;
        previousTranslucentWaitNanos = translucentWaitNanos;
        previousSubmitNanos = submitCompositeNanos;
        if (frameThrottled) {
            // The gap about to be measured contains a sleep this renderer asked
            // for. Recording it would put the cap in every number here, and the
            // clock still has to move on so the next frame is measured from now.
            throttledFrames++;
            lastFrameEndNanos = end;
            return;
        }
        if (lastFrameEndNanos != 0L) {
            long gap = end - lastFrameEndNanos;
            frameGaps[frameGapAt] = gap;
            frameGapAt = (frameGapAt + 1) % frameGaps.length;
            if (frameGapCount < frameGaps.length) {
                frameGapCount++;
            }
            if (gap > worstGapNanos) {
                worstGapNanos = gap;
                worstGapFrame = frameCounter;
                worstGapFence = fence;
                worstGapRecord = record;
                worstGapWait = waited;
                worstGapCollections = collected;
                worstGapCollectMillis = collectedMs;
                worstGapSubmit = submit;
            }
        }
        lastFrameEndNanos = end;
    }

    /**
     * The pacing line, and it resets itself: every number here describes the
     * interval since the last report, not the session. A worst frame from ten
     * minutes ago answers nothing about what is happening now.
     */
    private void appendPacing(StringBuilder sb) {
        if (frameGapCount < 8) {
            sb.append("  frame pacing: not enough frames yet")
                    .append(throttledFrames > 0
                            ? " (" + throttledFrames + " held back by the background cap)" : "")
                    .append('\n');
            throttledFrames = 0;
            return;
        }
        long[] sorted = new long[frameGapCount];
        System.arraycopy(frameGaps, 0, sorted, 0, frameGapCount);
        java.util.Arrays.sort(sorted);
        long median = sorted[frameGapCount / 2];
        // The worst one in twenty and the worst one in a hundred. Named from the
        // player's side — a "1% low" is the frame rate at the moment it feels
        // worst — rather than as a percentile of a time.
        long p95 = sorted[(int) (frameGapCount * 0.95)];
        long p99 = sorted[Math.min(frameGapCount - 1, (int) (frameGapCount * 0.99))];
        int overThreshold = 0;
        long threshold = median * 3;
        for (int i = frameGapCount - 1; i >= 0 && sorted[i] > threshold; i--) {
            overThreshold++;
        }
        sb.append(String.format(
                "  frame pacing: median %.1f ms (%.0f fps), 5%% low %.1f ms (%.0f fps), "
                        + "1%% low %.1f ms (%.0f fps)\n",
                median / 1e6, 1e9 / Math.max(1, median),
                p95 / 1e6, 1e9 / Math.max(1, p95),
                p99 / 1e6, 1e9 / Math.max(1, p99)));
        sb.append(String.format(
                "    worst frame %.1f ms at frame %d (of it: fence wait %.2f, "
                        + "translucent wait %.2f, record %.2f, submit+composite %.2f); "
                        + "%d frames over three times the median\n",
                worstGapNanos / 1e6, worstGapFrame, worstGapFence / 1e6,
                worstGapWait / 1e6, worstGapRecord / 1e6, worstGapSubmit / 1e6,
                overThreshold));
        // What is left when our three numbers are taken off the worst frame is
        // everything else in it — the game's own work, the driver, the operating
        // system. Printed as one number because it is one question: was the
        // worst frame ours at all?
        long ours = worstGapFence + worstGapWait + worstGapRecord + worstGapSubmit;
        sb.append(String.format("    of that worst frame, %.0f%% was this renderer\n",
                100.0 * ours / Math.max(1, worstGapNanos)));
        appendVerdict(sb);
        // Asked of the machine rather than of this renderer, and printed even
        // when it is zero: "no collection ran" is the answer that sends the
        // search back here, and it is worth as much as the other one.
        sb.append(String.format(
                "    the collector ran %d times in that worst frame (%d ms), and %d times over the interval (%d ms)\n",
                worstGapCollections, worstGapCollectMillis,
                intervalCollections, intervalCollectMillis));
        intervalCollections = 0L;
        intervalCollectMillis = 0L;
        sb.append(String.format(
                "    mirror lookup %.3f ms average over %d layer draws, worst %.1f ms%s\n",
                mirrorLookupNanos / Math.max(1.0, mirrorLookups) / 1e6, mirrorLookups,
                worstMirrorLookupNanos / 1e6,
                worstMirrorLookupNanos > 5_000_000L
                        ? " — this is the stall, and it is the monitor a building thread holds"
                        : ""));
        mirrorLookupNanos = 0;
        mirrorLookups = 0;
        worstMirrorLookupNanos = 0;
        sb.append(String.format(
                "    settings: read %d times over %d frames\n", settingsReads, settingsFrames));
        settingsReads = 0;
        settingsFrames = 0;
        if (throttledFrames > 0) {
            sb.append("    ").append(throttledFrames)
                    .append(" further frames held back by the background cap, not counted here\n");
        }
        throttledFrames = 0;
        frameGapCount = 0;
        frameGapAt = 0;
        worstGapNanos = 0;
        worstGapFence = 0;
        worstGapRecord = 0;
        worstGapSubmit = 0;
    }

    private void logFrameTimings() {
        if (frameCounter % TIMING_WINDOW != 0) {
            return;
        }
        long now = System.nanoTime();
        if (timingWindowStartNanos != 0) {
            double frames = TIMING_WINDOW;
            LOGGER.info("Terrain timings over {} frames: fence wait {} ms, record {} ms, "
                            + "submit+composite {} ms, GPU {} per frame; {} fps overall",
                    TIMING_WINDOW,
                    String.format("%.2f", fenceWaitNanos / frames / 1e6),
                    String.format("%.2f", recordNanos / frames / 1e6),
                    String.format("%.2f", submitCompositeNanos / frames / 1e6),
                    gpuTimeText(),
                    String.format("%.0f", frames * 1e9 / (now - timingWindowStartNanos)));
        }
        timingWindowStartNanos = now;
        fenceWaitNanos = 0;
        recordNanos = 0;
        translucentWaitNanos = 0;
        submitCompositeNanos = 0;
        gpuNanos = 0;
        gpuTranslucentNanos = 0;
        gpuTranslucentSamples = 0;
        gpuSamples = 0;
        // The marks the per-frame breakdown subtracts from have to go back to
        // zero with the totals they are subtracted from. Left behind, the first
        // frame after every window computed a large total minus a larger mark
        // and reported a negative share of itself — once per window, for as
        // long as the line has existed.
        previousFenceWaitNanos = 0;
        previousRecordNanos = 0;
        previousTranslucentWaitNanos = 0;
        previousSubmitNanos = 0;
    }

    /**
     * Keeps the acceleration structures level with the geometry.
     *
     * Guarded rather than checked once, because ray tracing can turn itself off
     * at any point — a failed build takes the whole subsystem down and leaves
     * the renderer drawing exactly as before, which is the only behaviour worth
     * having from something nothing depends on yet.
     */
    /**
     * Last frame's leaf chunks, packed the way the draw list packs them.
     *
     * Kept because the layers arrive in vanilla's order and the structures are
     * built on the first of them: by the time the leaves are drawn, everything
     * that was going to trace against them this frame already has.
     */
    private int[] foliageChunks = new int[0];
    private int foliageCount;
    private int[] cutoutChunks = new int[0];
    private int cutoutCount;
    /** Where each kind ends in the combined list; see {@link #combineForTracing}. */
    private int tracedSolidCount;
    private int tracedFoliageEnd;
    /**
     * A way back out, because this doubles what the structures hold.
     *
     * Every acceleration structure is memory and a build, and the builds are
     * the expensive half of tracing here — so if a canopy's shadow turns out to
     * cost more than it is worth on some machine, that has to be answerable
     * without a new build of the mod.
     */
    private static final boolean TRACE_FOLIAGE =
            !"false".equalsIgnoreCase(System.getProperty("vulkanmod112.rayTracingFoliage", "true"));
    /** Solid and leaves in one array, which is what the structures are built from. */
    private int[] tracedChunks = new int[0];

    private void rememberForTracing(int layerOrdinal, int[] chunks, int chunkCount) {
        boolean leaves = layerOrdinal == LAYER_CUTOUT_MIPPED;
        int[] into = leaves ? foliageChunks : cutoutChunks;
        int needed = chunkCount * 4;
        if (into.length < needed) {
            into = new int[Math.max(needed, into.length * 2)];
            if (leaves) {
                foliageChunks = into;
            } else {
                cutoutChunks = into;
            }
        }
        System.arraycopy(chunks, 0, into, 0, needed);
        if (leaves) {
            foliageCount = chunkCount;
        } else {
            cutoutCount = chunkCount;
        }
    }

    /**
     * Joins this frame's solid list to the last frame's leaves and plants.
     *
     * A chunk is one entry of four ints — the mirror slot and the origin — and
     * the slot is per layer, so the lists can simply follow one another: no
     * chunk appears twice, because a chunk's solid geometry, its leaves and its
     * grass live in different vertex buffers with slots of their own.
     *
     * The order is the whole of how the kinds are told apart afterwards: solid
     * first, then leaves, then everything else that is cut out. A structure
     * built from a leaf quad has to be marked as see-through and one built from
     * stone must not be, and this is where that is decided.
     *
     * @return how many chunks the combined array holds
     */
    private int combineForTracing(int[] chunks, int chunkCount) {
        tracedSolidCount = chunkCount;
        tracedFoliageEnd = chunkCount + foliageCount;
        int total = tracedFoliageEnd + cutoutCount;
        if (total == chunkCount) {
            tracedChunks = chunks;
            return chunkCount;
        }
        if (tracedChunks.length < total * 4 || tracedChunks == chunks) {
            tracedChunks = new int[Math.max(total * 4, tracedChunks.length * 2)];
        }
        System.arraycopy(chunks, 0, tracedChunks, 0, chunkCount * 4);
        System.arraycopy(foliageChunks, 0, tracedChunks, chunkCount * 4, foliageCount * 4);
        System.arraycopy(cutoutChunks, 0, tracedChunks, tracedFoliageEnd * 4, cutoutCount * 4);
        return total;
    }

    private void updateRayTracing(int[] chunks, int chunkCount, VkChunkMirror mirror,
                                  double viewX, double viewY, double viewZ) {
        if (!ctx.isRayTracingEnabled()) {
            return;
        }
        if (rayTracing == null) {
            rayTracing = new VkRayTracing(ctx);
        }
        // The index buffer can be rebuilt underneath, and its address goes with
        // it; handing it over every frame costs one query and removes a way for
        // the structures to be built from an address that no longer exists.
        rayTracing.setIndexBuffer(quadIndexBuffer);
        // The creatures of the previous frame, for the same reason the leaves
        // are the previous frame's: this runs on the first layer of the frame
        // and they are not drawn until the last. Their slot is not the one
        // being written now, so the geometry is still there to be read.
        int previous = (activeFrameSlot + framesInFlight - 1) % framesInFlight;
        if (creatureVertexCount != null && creatureVertexCount[previous] > 0
                && spriteVertexBuffers != null && spriteVertexBuffers[previous] != 0) {
            rayTracing.setCreatureGeometry(spriteVertexBuffers[previous],
                    (long) creatureFirstVertex[previous] * SPRITE_VERTEX_STRIDE,
                    creatureVertexCount[previous]);
        } else {
            rayTracing.setCreatureGeometry(0L, 0L, 0);
        }
        rayTracing.setKindBounds(tracedSolidCount, tracedFoliageEnd);
        rayTracing.update(chunks, chunkCount, mirror, frameCounter,
                activeFrameSlot, framesInFlight, viewX, viewY, viewZ);
        // Each frame slot has a structure of its own and descriptor sets of its
        // own, so the two are tied together here and nowhere else. A handle
        // only changes when the structure has to grow, which is rare — and
        // pointing descriptors at it stops the device, so it is done on change
        // rather than every frame.
        long current = rayTracing.topLevel(activeFrameSlot);
        if (current != structureWritten[activeFrameSlot] && ctx.isRayQuerySupported()) {
            structureWritten[activeFrameSlot] = current;
            writeStructureDescriptors(activeFrameSlot, current);
        }
    }

    private void writeStructureDescriptors(int slot, long structure) {
        if (structure == 0 || descriptorSet == 0) {
            return;
        }
        vkDeviceWaitIdle(device());
        try (MemoryStack stack = stackPush()) {
            LongBuffer handle = stack.longs(structure);
            org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR structureInfo =
                    org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                            .sType(org.lwjgl.vulkan.KHRAccelerationStructure
                                    .VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                            .pAccelerationStructures(handle);
            // Only this slot's sets: the other slots name the structures their
            // own frames are still reading.
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(BATCHES_PER_FRAME, stack);
            for (int i = 0; i < BATCHES_PER_FRAME; i++) {
                writes.get(i)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .pNext(structureInfo.address())
                        .dstSet(drawDescriptorSets[slot * BATCHES_PER_FRAME + i]).dstBinding(7)
                        // Not taken from pAccelerationStructures: the count in
                        // the write is what the driver reads, and the chained
                        // structure carries the handles it counts.
                        .descriptorCount(1)
                        .descriptorType(org.lwjgl.vulkan.KHRAccelerationStructure
                                .VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            }
            vkUpdateDescriptorSets(device(), writes, null);
        }
        LOGGER.info("Shaders can now trace against the terrain");
    }

    private VkRayTracing rayTracing;

    /** Whether the creatures of this frame really are in a structure. */
    boolean creaturesInStructure() {
        VkRayTracing tracing = rayTracing;
        return tracing != null && tracing.creaturesInStructure();
    }
    /** The tracing build of the terrain pipelines, or zeroes where impossible. */
    private final long[] tracingPipelines = new long[TERRAIN_PIPELINES.length];
    /** Which structure each frame slot's descriptor sets name; 0 means none. */
    private final long[] structureWritten = new long[framesInFlight];

    /** Everything the ultra log wants to know about this renderer. */
    synchronized void appendDiagnostics(StringBuilder sb) {
        sb.append("  terrain: frame ").append(frameCounter)
                .append(", ").append(frameChunks).append(" chunks, ")
                .append(frameVertices).append(" vertices, ")
                .append(frameSkipped).append(" skipped\n");
        sb.append("  targets: ").append(width).append('x').append(height)
                .append(", depth ").append(depthFormat == VK_FORMAT_X8_D24_UNORM_PACK32 ? "D24" : "D32F")
                .append(", depth blit ").append(depthBlit ? "on" : "off")
                .append(", atlas ").append(atlasWidth).append('x').append(atlasHeight)
                .append(" (").append(atlasLevels).append(" mips)\n");
        sb.append("  ").append(VertexLayout.stats()).append('\n');
        sb.append("  frame cost: fence wait ")
                .append(String.format("%.2f", fenceWaitNanos / (double) Math.max(1, timingSamples()) / 1e6))
                .append(" ms, record ")
                .append(String.format("%.2f", recordNanos / (double) Math.max(1, timingSamples()) / 1e6))
                .append(" ms, submit+composite ")
                .append(String.format("%.2f", submitCompositeNanos / (double) Math.max(1, timingSamples()) / 1e6))
                .append(" ms, GPU ").append(gpuTimeText()).append('\n');
        // The three numbers above are the processor's view plus the Vulkan
        // queue's own. This is the other half of the frame: what the card
        // spends inside OpenGL doing our work, which nothing measured before.
        sb.append("  gl cost: composite ").append(glTimeText(compositeTimer))
                .append(" of work plus ").append(glTimeText(compositeWaitTimer))
                .append(" waiting for Vulkan")
                .append(" [").append(compositeTimer.health()).append(']')
                .append(", depth back to Vulkan ")
                .append(depthBlit ? glTimeText(depthBlitTimer) + " by hardware copy"
                        : glTimeText(depthImportTimer))
                .append('\n');
        String animations = net.vulkanmod112.client.AnimatedSprites.stats();
        if (animations != null && net.vulkanmod112.client.VulkanConfig.isSmartAnimations()) {
            sb.append(animations).append('\n');
        }
        sb.append("  lightmap: ").append(lightmapUploads).append(" changes over ")
                .append(lightmapFrames).append(" frames")
                .append(lightmapFrames > 0
                        ? String.format(" (1 per %.1f frames)", lightmapFrames / (double) Math.max(1, lightmapUploads))
                        : "")
                .append("; the game recomputes it once a tick, so ~20/s is expected\n");
        if (rayTracing != null) {
            rayTracing.appendDiagnostics(sb);
        } else {
            sb.append("  ray tracing: ").append(ctx.rayTracingStatus()).append('\n');
        }
        // Whether the pass ran, and not only whether it was asked for: it turns
        // itself off when nothing is traced, and "on but doing nothing" and "on
        // and working" look identical in the settings screen.
        appendPacing(sb);
        sb.append("  atlas animation: ").append(atlasTicksQueued).append(" ticks queued, ")
                .append(atlasFramesCarried).append(" frames carried them")
                .append(atlasPendingBytes > 0
                        ? ", " + (atlasPendingBytes / 1024) + " KiB waiting" : "")
                .append(atlasPendingDropped > 0
                        ? ", " + atlasPendingDropped + " backlogs dropped" : "")
                .append(" (no fence wait on the render thread)\n");
        sb.append("  scene tone: ");
        if (toneFailed) {
            sb.append("off for this session (see the main log)");
        } else if (toneStrength <= 0.0f) {
            sb.append("off");
        } else {
            sb.append(String.format("%.0f%% at %.0f%% warmth, %d frames graded, target %dx%d",
                    toneStrength * 100.0f, (toneWarmth + 1.0f) * 50.0f, toneFrames,
                    toneWidth, toneHeight));
            if (toneProbe != null) {
                sb.append("\n    centre pixel: ").append(toneProbe);
            }
            toneFrames = 0;
        }
        sb.append('\n');
        // Named whether or not it is on, and the two copies it lives on named
        // beside it. "Off" and "on but reading a copy the driver refused" are
        // the same picture and opposite bugs, and the file had no way to tell
        // them apart.
        sb.append("  scene occlusion: ");
        if (aoFailed) {
            sb.append("off for this session (see the main log)");
        } else if (!sceneOcclusion || !aoWanted()) {
            sb.append("off");
        } else {
            sb.append(sceneOcclusionFrames).append(" frames shaded, depth copy ")
                    .append(sceneDepthUsable ? "ok" : "REFUSED")
                    .append(", colour copy ").append(sceneColourUsable ? "ok" : "REFUSED")
                    .append(showOcclusion ? ", showing the occlusion term alone" : "");
            if (sceneStateAsFound != null) {
                sb.append("\n    handed: ").append(sceneStateAsFound);
            }
            if (sceneEntryProbe != null) {
                sb.append("\n    centre on arrival: ").append(sceneEntryProbe);
            }
            sceneOcclusionFrames = 0;
        }
        sb.append('\n');
        sb.append("  frame accumulation: ");
        if (accumFailed) {
            sb.append("off for this session (see the main log)");
        } else if (accumStrength <= 0.0f) {
            sb.append("off");
        } else {
            sb.append(String.format("%.0f%% history", accumStrength * 100.0f))
                    .append(accumApplied ? ", running" : ", idle (nothing traced or no motion)")
                    .append(", dither turn ").append(String.format("%.2f", ditherTurn));
        }
        sb.append('\n');
        sb.append("  sprites: ").append(spritePipeline == 0 ? "pipeline missing" : "in Vulkan")
                .append(", last frame ").append(spriteFrameBatches).append(" batches, ")
                .append(spriteFrameVertices).append(" vertices; sheets");
        for (int slot = 1; slot < FIRST_SKIN_SLOT; slot++) {
            sb.append(' ').append(slot).append('=')
                    .append(spriteImages[slot] == 0 ? "-" : "ok");
        }
        int skins = 0;
        for (int slot = FIRST_SKIN_SLOT; slot < SPRITE_SLOTS; slot++) {
            if (spriteImages[slot] != 0) {
                skins++;
            }
        }
        sb.append(", skins ").append(skins).append('/')
                .append(SPRITE_SLOTS - FIRST_SKIN_SLOT);
        if (skinSlotsExhausted > 0) {
            sb.append(" (").append(skinSlotsExhausted)
                    .append(" left to the game for want of a slot)");
        }
        if (spriteDropped > 0) {
            sb.append(", ").append(spriteDropped).append(" batches handed back to OpenGL");
        }
        sb.append('\n');
        sb.append("  index buffer: ").append(quadIndexCapacityQuads).append(" quads")
                .append(", draw batch ").append(indirectDrawCapacity)
                .append(" in ").append(indirectMemoryIsDeviceLocal ? "BAR (device-local)" : "host")
                .append(" memory, frames in flight ").append(framesInFlight).append('\n');
        if (glErrorLogged) {
            sb.append("  WARNING: a GL error was reported during composite (see the main log)\n");
        }
        if (frameSkipped > 0) {
            sb.append("  WARNING: ").append(frameSkipped)
                    .append(" chunks were skipped last frame — no mirror, or past the draw cap\n");
        }
    }

    /** Frames accumulated into the current timing window. */
    private int timingSamples() {
        return (int) (frameCounter % TIMING_WINDOW == 0 ? TIMING_WINDOW : frameCounter % TIMING_WINDOW);
    }

    /** Average GPU time over the window, or "n/a" where the queue has no timestamps. */
    private String gpuTimeText() {
        if (!timestampsSupported) {
            return "n/a";
        }
        if (gpuSamples == 0) {
            return "pending";
        }
        return String.format("%.2f ms", gpuNanos / (double) gpuSamples / 1e6);
    }

    /**
     * The graphics queue writes a timestamp before and after the terrain pass.
     * Reading them costs nothing here because the slot's fence has already been
     * waited on, so the results are guaranteed to be available.
     */
    private void createQueryPool(MemoryStack stack) {
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(ctx.getPhysicalDevice(), props);
        timestampPeriod = props.limits().timestampPeriod();

        IntBuffer familyCount = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(ctx.getPhysicalDevice(), familyCount, null);
        VkQueueFamilyProperties.Buffer families =
                VkQueueFamilyProperties.malloc(familyCount.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(ctx.getPhysicalDevice(), familyCount, families);
        int validBits = families.get(ctx.getGraphicsQueueFamily()).timestampValidBits();

        timestampsSupported = timestampPeriod > 0.0f && validBits > 0;
        if (!timestampsSupported) {
            LOGGER.info("Graphics queue has no timestamp support; GPU timings unavailable");
            return;
        }
        VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                .queryType(VK_QUERY_TYPE_TIMESTAMP)
                // Four a slot, not two: the opaque pass takes the first pair
                // and the translucent pass the second. Timing only the first
                // was worse than timing nothing, because every expensive shader
                // this renderer has — reflection, refraction, absorption — is
                // in the second, so the number said "the card is idle" exactly
                // when the card was busiest.
                .queryCount(framesInFlight * 4);
        LongBuffer pPool = stack.mallocLong(1);
        check(vkCreateQueryPool(device(), info, null, pPool), "vkCreateQueryPool(terrain)");
        queryPool = pPool.get(0);
    }

    /**
     * The one line that says where the frame actually went.
     *
     * Three numbers were already being measured and never stood next to each
     * other: how long the whole frame took, how much of it this renderer spent
     * on the processor, and how long the card was busy with the work this
     * renderer gave it. Each on its own answers nothing. Together they separate
     * the only three answers there are, and the third one — that the frame is
     * neither, and the time is going into waiting for the two halves to agree —
     * is the one nobody ever guesses and the one this renderer is most able to
     * cause, owning both sides of the boundary as it does.
     *
     * Said in words rather than left as three figures to compare, because the
     * comparison is the whole content and the person reading the log is looking
     * for what to do next, not for arithmetic.
     */
    private void appendVerdict(StringBuilder sb) {
        if (!timestampsSupported || gpuSamples == 0) {
            sb.append("    where the frame went: unknown, this driver has no usable timestamps\n");
            return;
        }
        double frame = worstGapNanos / 1e6;
        double cpu = (worstGapFence + worstGapWait + worstGapRecord + worstGapSubmit) / 1e6;
        double opaque = gpuNanos / (double) gpuSamples / 1e6;
        double water = gpuTranslucentSamples == 0
                ? 0.0 : gpuTranslucentNanos / (double) gpuTranslucentSamples / 1e6;
        double gpu = opaque + water;
        // The fence wait is time already spent waiting for the card, so it is
        // named apart from the rest: a frame that is mostly this is not a frame
        // the processor was busy in, whatever the total says.
        double waiting = (worstGapFence + worstGapWait) / 1e6;
        String verdict;
        // What to do next, printed only where it is the next thing to do.
        //
        // Three of these four verdicts name something in this renderer and the
        // reader can go and look at it. The fourth names the game, and there is
        // nothing here to look at — but the game keeps its own profiler, and
        // this report already prints its tree in full. It only fills in while
        // the game holds that profiler on, which it does only while the chart
        // is open, so the one flight made to answer this question came back
        // without the one measurement that answers it. Saying so beside the
        // verdict is the difference between one flight and two.
        String next = "";
        if (gpu >= frame * 0.7) {
            verdict = "the card — it is busy for most of the frame, so shading and fill are the limit";
        } else if (cpu - waiting >= frame * 0.5) {
            verdict = "this renderer, on the processor — the card finishes early and waits";
        } else if (waiting >= frame * 0.4 && gpu < frame * 0.5) {
            verdict = "waiting, not working — neither side is busy, so the time is in the handshake,"
                    + " the present, or a frame cap";
        } else {
            verdict = "somewhere else — not this renderer's processor time and not its card time,"
                    + " so look at the game, the driver or the collector";
            next = "      to find out which: press Shift+F3 and fly again, and the game's own"
                    + " profiler tree will appear further down this report\n";
        }
        sb.append(String.format(
                "    where the frame went: %s\n"
                        + "      worst frame %.1f ms | this renderer on the CPU %.1f ms"
                        + " (of which %.1f ms was waiting) | card %.1f ms"
                        + " (opaque %.1f + translucent %.1f over %d and %d samples)\n",
                verdict, frame, cpu, waiting, gpu, opaque, water, gpuSamples, gpuTranslucentSamples));
        sb.append(next);
    }

    private void readGpuTimestamps(MemoryStack stack, int slot) {
        if (!timestampsSupported || frameCounter < framesInFlight) {
            return; // this slot has not run yet
        }
        LongBuffer results = stack.mallocLong(2);
        int result = vkGetQueryPoolResults(device(), queryPool, slot * 4, 2, results, 8,
                VK_QUERY_RESULT_64_BIT);
        if (result != VK_SUCCESS) {
            return; // VK_NOT_READY: skip this sample rather than stall the frame
        }
        long delta = results.get(1) - results.get(0);
        if (delta > 0) {
            gpuNanos += (long) (delta * timestampPeriod);
            gpuSamples++;
        }
        // Asked separately, and allowed to fail on its own. A frame with no
        // water in front of the camera records no translucent pass at all, and
        // its pair of queries is never written — reading all four at once would
        // turn every such frame into VK_NOT_READY and throw the opaque half
        // away with it.
        int second = vkGetQueryPoolResults(device(), queryPool, slot * 4 + 2, 2, results, 8,
                VK_QUERY_RESULT_64_BIT);
        if (second != VK_SUCCESS) {
            return;
        }
        long translucent = results.get(1) - results.get(0);
        if (translucent > 0) {
            gpuTranslucentNanos += (long) (translucent * timestampPeriod);
            gpuTranslucentSamples++;
        }
    }

    // ------------------------------------------------------------------
    // Frame assembly
    // ------------------------------------------------------------------

    private void beginFrame(float[] mvp, VkChunkMirror mirror) {
        try (MemoryStack stack = stackPush()) {
            // Queue uploads before this frame. The graphics queue preserves
            // submission order, so the vertex fetches below see device-local
            // copies without a CPU-side wait.
            mirror.flushUploads();
            int slot = (int) (frameCounter % framesInFlight);
            activeFrameSlot = slot;
            commandBuffer = commandBuffers[slot];
            fence = fences[slot];
            long t0 = System.nanoTime();
            check(vkWaitForFences(device(), fence, true, 1_000_000_000L), "vkWaitForFences");
            fenceWaitNanos += System.nanoTime() - t0;
            vkResetFences(device(), fence);
            readGpuTimestamps(stack, slot);
            // This slot's fence covers frame N-2; everything up to it is done
            mirror.setFrameStamp(frameCounter);
            mirror.flushRetired(frameCounter - framesInFlight);
            ensureQuadIndexCapacity(mirror.maxEntrySize() / VertexLayout.stride() / 4);
            firstFrameStage("recording draw commands");
            frameChunks = 0;
            frameVertices = 0;
            frameSkipped = 0;
            // Anything left over belonged to a frame that never reached its
            // translucent pass — a world that unloaded, a layer refused. It is
            // stale by definition and must not be drawn a frame late.
            clearSprites();
            spriteFrameVertices = 0;
            spriteFrameBatches = 0;

            // The lightmap changes when the light level does — dawn, dusk,
            // walking into a cave — and is identical on the great majority of
            // frames. Hashing 256 ints is far cheaper than writing 1 KiB of
            // staging and running two layout barriers plus a copy for data
            // the image already holds.
            lightmapDirty = true;
            lightmapFrames++;
            if (lightmapData != null) {
                int hash = hashLightmap(lightmapData);
                if (lightmapImageInitialized && hash == lightmapHash) {
                    lightmapDirty = false;
                } else {
                    lightmapHash = hash;
                    lightmapUploads++;
                    writeLightmapStaging(lightmapData);
                }
            } else {
                readLightmapFromGL(); // fallback: stalls the GL pipeline
            }

            vkResetCommandBuffer(commandBuffer, 0);
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer");

            // Taken back from OpenGL before anything is recorded against them.
            // Skipped on the very first frame, where there is nothing to take
            // back: nobody has been given them yet.
            if (!firstFrame) {
                transferSharedImages(stack, commandBuffer, false);
            }

            if (timestampsSupported) {
                // Must be outside a render pass, so it goes first.
                // All four reset here, where the frame starts, so the
                // translucent pair is clean even on a frame that never records
                // a translucent pass.
                vkCmdResetQueryPool(commandBuffer, queryPool, slot * 4, 4);
                vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        queryPool, slot * 4);
            }

            recordAtlasUpload(stack);
            if (lightmapDirty) {
                recordLightmapUpload(stack);
            }

            VkClearValue.Buffer clears = VkClearValue.calloc(2, stack);
            clears.get(0).color()
                    .float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 0.0f);
            clears.get(1).depthStencil().depth(1.0f).stencil(0);

            VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(framebuffer)
                    .renderArea(VkRect2D.calloc(stack)
                            .extent(VkExtent2D.calloc(stack).width(width).height(height)))
                    .pClearValues(clears);
            vkCmdBeginRenderPass(commandBuffer, rpBegin, VK_SUBPASS_CONTENTS_INLINE);

            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout,
                    0, stack.longs(descriptorSet), null);
            vkCmdBindIndexBuffer(commandBuffer, quadIndexBuffer, 0, VK_INDEX_TYPE_UINT32);

            // The matrix and the fog are the same for every chunk and every
            // layer, so they live in this frame's uniform buffer rather than
            // being pushed again for each of them.
            refreshShaderSettings();
            // Asked here rather than remembered from last frame: the buffer
            // appears the first time a chunk carries materials, and a shader
            // told about it a frame late would read the one frame where the
            // second binding is still the geometry buffer.
            materialsBound = mirror != null && mirror.materialBuffer() != 0;
            writeFrameUniforms(mvp, fogState);

            // Standard (y-down) viewport: the GL-sourced matrices produce a
            // vertically flipped image in Vulkan's convention, which is
            // exactly GL's texture orientation — the composite pass and the
            // depth readback then sample it without any flip.
            org.lwjgl.vulkan.VkViewport.Buffer viewport = org.lwjgl.vulkan.VkViewport.calloc(1, stack);
            viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(commandBuffer, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).extent(VkExtent2D.calloc(stack).width(width).height(height));
            vkCmdSetScissor(commandBuffer, 0, scissor);

            frameOpen = true;
        }
    }

    private void drawChunks(int layerOrdinal, int[] chunks, int chunkCount, float[] mvp,
                            double viewX, double viewY, double viewZ, VkChunkMirror mirror) {
        int variant = pipelineForLayer(layerOrdinal);
        if (variant < 0) {
            return;
        }
        float cutoff = LAYER_CUTOFF[layerOrdinal];
        // The tracing build only when there is something to trace against and
        // a reason to: no structure, no sun, or the setting at zero, and the
        // ordinary pipeline draws exactly what it always did.
        boolean traced = tracingWanted() && tracingPipelines[variant] != 0
                && structureWritten[activeFrameSlot] == rayTracing.topLevel(activeFrameSlot);
        try (MemoryStack stack = stackPush()) {
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    traced ? tracingPipelines[variant] : pipelines[variant]);
            // The only thing left that changes between draws. Per-chunk origins
            // are fetched by the vertex shader from the storage buffer.
            ByteBuffer drawPush = stack.calloc(16);
            drawPush.putFloat(0, cutoff);
            vkCmdPushConstants(commandBuffer, pipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, drawPush);
            long geometry = mirror.geometryBuffer();
            long materials = mirror.materialBuffer();
            // All chunks are suballocations of this one device-local buffer.
            //
            // The second binding is the materials, and when there are none the
            // geometry buffer is bound in its place. Something has to be bound
            // — the pipeline declares the binding — and this is in bounds by
            // construction: read at stride one, the furthest vertex of the
            // furthest chunk lands at a twenty-eighth of the buffer it is
            // reading from. What comes back is meaningless, and the shader is
            // told so and never looks.
            vkCmdBindVertexBuffers(commandBuffer, 0,
                    stack.longs(geometry, materials == 0 ? geometry : materials),
                    stack.longs(0L, 0L));
            boolean logInputs = STARTUP_READBACK && layerOrdinal == 0
                    && (frameCounter == 0 || frameCounter == 119);

            int batchIndex = activeFrameSlot * BATCHES_PER_FRAME + layerOrdinal;
            long mapped = drawBatchMapped[batchIndex];
            int drawCount = 0;
            if (lookupScratch.length < chunkCount) {
                lookupScratch = new VkChunkMirror.Entry[Integer.highestOneBit(chunkCount) * 2];
            }
            // One monitor acquisition for the whole layer, not one per chunk.
            // Timed on its own, because the frame that cost forty-three
            // milliseconds spent thirty-eight of them somewhere inside this
            // method and there are only two candidates. This one takes the
            // mirror's monitor for the whole layer, and a chunk-building thread
            // can be holding it while it uploads. The other candidate — growing
            // the index buffer or the draw batch, both of which stop the device
            // — happens before the clock that measured those thirty-eight
            // milliseconds even starts, so it is already ruled out.
            //
            // One of these two numbers will grow. Putting the counter in before
            // the fix rather than after is the rule this project keeps
            // relearning.
            long lookupStart = System.nanoTime();
            mirror.findAll(chunks, chunkCount, lookupScratch);
            long lookupNanos = System.nanoTime() - lookupStart;
            mirrorLookupNanos += lookupNanos;
            mirrorLookups++;
            if (lookupNanos > worstMirrorLookupNanos) {
                worstMirrorLookupNanos = lookupNanos;
            }
            for (int c = 0; c < chunkCount; c++) {
                VkChunkMirror.Entry entry = lookupScratch[c];
                if (entry == null || entry.size < VertexLayout.stride()
                        || entry.size % VertexLayout.stride() != 0) {
                    frameSkipped++;
                    continue;
                }
                int vertexCount = entry.size / VertexLayout.stride();
                if (vertexCount / 4 > quadIndexCapacityQuads) {
                    frameSkipped++;
                    continue; // grew mid-frame; drawable next frame
                }
                if (drawCount >= indirectDrawCapacity) {
                    frameSkipped++;
                    continue;
                }
                frameChunks++;
                frameVertices += vertexCount;
                long origin = mapped + (long) drawCount * DRAW_ORIGIN_BYTES;
                MemoryUtil.memPutFloat(origin, (float) (chunks[c * 4 + 1] - viewX));
                MemoryUtil.memPutFloat(origin + 4, (float) (chunks[c * 4 + 2] - viewY));
                MemoryUtil.memPutFloat(origin + 8, (float) (chunks[c * 4 + 3] - viewZ));
                // Where this chunk's first vertex sits within a quad.
                //
                // gl_VertexIndex carries the draw's vertexOffset added in, and
                // the vertex shader needs the corner number inside the quad to
                // tell the top of a plant from its bottom. Every suballocation
                // begins on a vertex boundary but not necessarily on a quad
                // one, so the offset is not always a multiple of four and the
                // low two bits cannot simply be masked off. Handing them over
                // costs a float that was being written as zero anyway.
                int baseVertex = (int) (entry.offset / VertexLayout.stride());
                MemoryUtil.memPutFloat(origin + 12, baseVertex & 3);
                if (logInputs) {
                    logInputs = false;
                    ByteBuffer push = stack.malloc(12);
                    push.putFloat(0, MemoryUtil.memGetFloat(origin));
                    push.putFloat(4, MemoryUtil.memGetFloat(origin + 4));
                    push.putFloat(8, MemoryUtil.memGetFloat(origin + 8));
                    logDrawInputs(mvp, push, entry);
                }
                long command = mapped + drawCommandOffset + (long) drawCount * DRAW_COMMAND_BYTES;
                MemoryUtil.memPutInt(command, vertexCount / 4 * 6);
                MemoryUtil.memPutInt(command + 4, 1);
                MemoryUtil.memPutInt(command + 8, 0);
                MemoryUtil.memPutInt(command + 12, (int) (entry.offset / VertexLayout.stride()));
                MemoryUtil.memPutInt(command + 16, drawCount++);
            }
            if (drawCount != 0) {
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout,
                        0, stack.longs(drawDescriptorSets[batchIndex]), null);
                if (ctx.canMultiDrawIndirect()) {
                    vkCmdDrawIndexedIndirect(commandBuffer, drawBatchBuffers[batchIndex],
                            drawCommandOffset, drawCount, DRAW_COMMAND_BYTES);
                } else {
                    // One command per call where the driver will not take a
                    // batch. Slower, and the alternative is undefined
                    // behaviour, which is not an alternative.
                    for (int i = 0; i < drawCount; i++) {
                        vkCmdDrawIndexedIndirect(commandBuffer, drawBatchBuffers[batchIndex],
                                drawCommandOffset + (long) i * DRAW_COMMAND_BYTES, 1,
                                DRAW_COMMAND_BYTES);
                    }
                }
            }
        }
    }

    /** One-shot dump of everything the vertex stage consumes, for offline math checks. */
    private void logDrawInputs(float[] mvp, ByteBuffer push, VkChunkMirror.Entry entry) {
        StringBuilder sb = new StringBuilder("Draw inputs (frame ").append(frameCounter + 1).append("): mvp=[");
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("%.4f", mvp[i])).append(i == 15 ? "]" : " ");
        }
        sb.append(" offset=[").append(push.getFloat(0)).append(' ').append(push.getFloat(4))
                .append(' ').append(push.getFloat(8)).append(']');
        // The first vertices used to be dumped here from the chunk's own
        // staging copy. Uploads now pass through a shared ring that is
        // overwritten within a few hundred chunks, so there is no copy left to
        // read — and the geometry buffer is device-local.
        sb.append(" verts=").append(entry.size / VertexLayout.stride());
        LOGGER.info(sb.toString());
    }

    /**
     * Whether a frame has been signalled to OpenGL and not yet waited for.
     *
     * There is one interop semaphore pair for the whole renderer, not one per
     * frame in flight, and that is safe only because {@link #submitFrame()} and
     * {@link #composite()} are called as a pair, on one thread, once per real
     * frame. Signalling a binary semaphore twice without a wait between is
     * undefined behaviour, and the symptom would be a device loss some frames
     * later with nothing pointing at the cause — which this project has already
     * paid for three times.
     *
     * Today no path breaks the pairing. Nothing enforced it either: a future
     * early return or a thrown exception between the two would break it in
     * silence. So the invariant is now stated rather than assumed, and says so
     * once if it is ever untrue.
     */
    private boolean frameSignalled;
    private boolean pairingWarned;

    private void submitFrame() {
        if (frameSignalled && !pairingWarned) {
            pairingWarned = true;
            LOGGER.error("A frame was signalled to OpenGL twice without a wait between. "
                    + "The interop semaphores are one pair for the whole renderer and rely on "
                    + "submitFrame and composite being called together; something now calls them "
                    + "apart. Expect a device loss with no obvious cause.");
        }
        frameSignalled = true;
        try (MemoryStack stack = stackPush()) {
            vkCmdEndRenderPass(commandBuffer);
            // Handed to OpenGL as the last thing this frame records, so the
            // composite that follows reads images Vulkan no longer owns.
            transferSharedImages(stack, commandBuffer, true);
            if (STARTUP_READBACK && (frameCounter == 0 || frameCounter == 119)) {
                recordColorReadback(stack);
            }
            if (timestampsSupported) {
                vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                        queryPool, activeFrameSlot * 4 + 1);
            }
            check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(commandBuffer));
            if (SHARED_SEMAPHORES) {
                submit.pSignalSemaphores(stack.longs(vkSignalSemaphore));
                // Vulkan tracks a D3D12-fence-backed binary semaphore's value
                // itself; this mirror is only so the GL side of the pair knows
                // what to wait for, see setFenceValue.
                signalFenceValue++;
            }
            if (!firstFrame && SHARED_SEMAPHORES) {
                submit.waitSemaphoreCount(1)
                        .pWaitSemaphores(stack.longs(vkWaitSemaphore))
                        .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));
            }
            firstFrame = false;
            firstFrameStage("submitting the opaque frame");
            check(vkQueueSubmit(ctx.getGraphicsQueue(), submit, fence), "vkQueueSubmit(terrain)");
            if (!SHARED_SEMAPHORES) {
                // Nothing will tell OpenGL when these images are finished, so
                // finishing them here is the only ordering left.
                vkQueueWaitIdle(ctx.getGraphicsQueue());
            }
            firstFrameStage("submitted");
            if (tracingFirstFrame) {
                // The one question three attempted fixes never asked: does the
                // card finish our work at all? Everything after this waits on
                // that being true, and if it is not, nothing on the OpenGL side
                // was ever the defect. Bounded, so the answer arrives even when
                // it is "no" — an unbounded wait here would look exactly like
                // the hang it is meant to explain.
                int done = vkWaitForFences(device(), fence, true, 3_000_000_000L);
                LOGGER.info("First terrain frame: the card {} our work ({})",
                        done == VK_SUCCESS ? "finished" : "did NOT finish", done);
            }
            frameOpen = false;
        }
    }

    /**
     * Draws the translucent layer, after the game has drawn everything that
     * belongs behind it.
     *
     * The order matters more than the drawing does. Vanilla asks for this layer
     * once entities, particles and weather are already in its framebuffer, and
     * draws it depth-tested against them with depth writes off. This renderer
     * composited its opaque terrain long before that, so the depth image here
     * still holds terrain alone — which is why the first thing that happens is
     * copying the game's depth back into it. Skip that and water is drawn over
     * anything swimming behind it.
     *
     * @return true when the layer was taken and OpenGL should not draw it
     */
    /**
     * The same condition {@link #renderTranslucent} opens with, asked ahead of
     * time. Kept next to it so the two cannot drift: whoever decides to stop
     * filling the game's own chunk buffers is betting the world's water on this
     * answer.
     */
    synchronized boolean drawsTranslucent() {
        return translucentFramebuffer != 0 && canReturnDepth();
    }

    /**
     * Whether particles and weather can go through Vulkan on this machine.
     *
     * Tied to the translucent pass and not a condition of its own, because it
     * <em>is</em> that pass: sprites are drawn into the same target, in the
     * same submission, against the same borrowed depth. A machine where the
     * translucent layer stays in OpenGL has nowhere to put them that would not
     * cost a second import of the game's depth and a second composite — about
     * a third of a millisecond, to save drawing a few thousand quads.
     */
    synchronized boolean drawsSprites() {
        return spritePipeline != 0 && drawsTranslucent();
    }

    /**
     * Copies one of the game's sprite sheets into Vulkan.
     *
     * Slot 0 is the block atlas and is never uploaded here: it is already in
     * Vulkan for the terrain, and its descriptor simply points at the same
     * image. A second copy of an atlas that a resource pack can make sixteen
     * megabytes large, to draw the handful of block-shaped particles a broken
     * block throws off, would be a poor trade.
     */
    /**
     * The slot holding this OpenGL texture, copying it in the first time.
     *
     * Returns zero when there is no room, which the caller reads as "let the
     * game draw this one" — a creature missing from our pass is a creature
     * drawn the old way, not a creature missing from the screen.
     */
    synchronized int spriteSlotForTexture(int glTextureId) {
        if (glTextureId <= 0) {
            return 0;
        }
        for (int slot = FIRST_SKIN_SLOT; slot < SPRITE_SLOTS; slot++) {
            if (spriteGlIds[slot] == glTextureId && spriteImages[slot] != 0) {
                return slot;
            }
        }
        for (int slot = FIRST_SKIN_SLOT; slot < SPRITE_SLOTS; slot++) {
            if (spriteImages[slot] == 0) {
                updateSpriteTexture(slot, glTextureId);
                return spriteImages[slot] == 0 ? 0 : slot;
            }
        }
        skinSlotsExhausted++;
        return 0;
    }

    private long skinSlotsExhausted;

    synchronized void updateSpriteTexture(int slot, int glTextureId) {
        if (slot <= 0 || slot >= SPRITE_SLOTS || glTextureId <= 0) {
            return;
        }
        if (spriteGlIds[slot] == glTextureId && spriteImages[slot] != 0) {
            return;
        }
        ctx.ensureGlCapabilities();
        ensureBaseResources();
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glTextureId);
        int w = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
        int h = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0) {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
            LOGGER.warn("Sprite sheet in GL texture {} has no level 0; slot {} left empty", glTextureId, slot);
            return;
        }
        // Level 0 only. These sheets are drawn at close range on quads facing
        // the camera, so a mip chain would almost never be sampled from, and
        // the game does not build one for them either.
        ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
        GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
        try (MemoryStack stack = stackPush()) {
            destroySpriteImage(slot);
            long[] out = new long[3];
            createSampledImage(stack, w, h, new ByteBuffer[]{pixels}, out);
            spriteImages[slot] = out[0];
            spriteMemories[slot] = out[1];
            spriteViews[slot] = out[2];
            spriteGlIds[slot] = glTextureId;
        } finally {
            MemoryUtil.memFree(pixels);
        }
        writeSpriteSet(slot, spriteViews[slot], spriteSampler);
        LOGGER.info("Sprite sheet copied to Vulkan: slot {}, {}x{}", slot, w, h);
    }

    /**
     * Takes one batch of the game's own sprite vertices.
     *
     * The vertices are built by the game exactly as they always were — this
     * renderer does not know what a particle is, only what a quad is — and what
     * changes is where they go: into a buffer the card owns, instead of through
     * a client-side vertex array, which is the slowest way OpenGL has of being
     * handed geometry and the way this game has always drawn every particle in
     * the world.
     */
    /**
     * @return false when this batch was not taken, so the caller draws it the
     *         way the game would have. This used to return nothing: a batch
     *         that would not fit was counted, logged as "left to OpenGL", and
     *         then left to nobody — the caller had already been told the
     *         geometry was handled. One rain field is one batch and does not
     *         fit, so weather through this path was invisible.
     */
    synchronized boolean submitSprites(ByteBuffer vertices, int vertexCount, int slot, float cutoff) {
        return submitSprites(vertices, vertexCount, slot, cutoff, 0, false);
    }

    synchronized boolean submitSprites(ByteBuffer vertices, int vertexCount, int slot, float cutoff,
                                       int overlay, boolean glint) {
        if (vertices == null || slot < 0 || slot >= SPRITE_SLOTS) {
            return false;
        }
        vertexCount -= vertexCount % 4;
        if (vertexCount < 4) {
            // Nothing to draw at all; nobody needs to draw it instead.
            return true;
        }
        int bytes = vertexCount * SPRITE_VERTEX_STRIDE;
        if (vertices.remaining() < bytes) {
            return false;
        }
        if (spriteScratchVertices + vertexCount > MAX_SPRITE_VERTICES
                || spriteBatchCount >= spriteCutoffs.length && !growSpriteBatches()) {
            spriteDropped++;
            if (!spriteOverflowLogged) {
                spriteOverflowLogged = true;
                LOGGER.warn("More sprite geometry in one frame than this renderer will hold "
                        + "({} vertices); the surplus is left to OpenGL", MAX_SPRITE_VERTICES);
            }
            return false;
        }
        int used = spriteScratchVertices * SPRITE_VERTEX_STRIDE;
        if (spriteScratch == null || spriteScratch.capacity() - used < bytes) {
            int want = Integer.highestOneBit(Math.max(used + bytes, 1 << 16)) * 2;
            ByteBuffer grown = MemoryUtil.memAlloc(want);
            if (spriteScratch != null) {
                MemoryUtil.memCopy(MemoryUtil.memAddress0(spriteScratch),
                        MemoryUtil.memAddress0(grown), used);
                MemoryUtil.memFree(spriteScratch);
            }
            spriteScratch = grown;
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(vertices),
                MemoryUtil.memAddress0(spriteScratch) + used, bytes);
        int b = spriteBatchCount++;
        spriteBatches[b * 3] = spriteScratchVertices;
        spriteBatches[b * 3 + 1] = vertexCount;
        spriteBatches[b * 3 + 2] = slot;
        spriteCutoffs[b] = cutoff;
        spriteOverlays[b] = overlay;
        spriteGlints[b] = glint;
        spriteScratchVertices += vertexCount;
        return true;
    }

    private boolean growSpriteBatches() {
        int want = spriteCutoffs.length * 2;
        if (want > 4096) {
            return false;
        }
        int[] batches = new int[want * 3];
        float[] cutoffs = new float[want];
        int[] overlays = new int[want];
        boolean[] glints = new boolean[want];
        System.arraycopy(spriteBatches, 0, batches, 0, spriteBatchCount * 3);
        System.arraycopy(spriteCutoffs, 0, cutoffs, 0, spriteBatchCount);
        System.arraycopy(spriteOverlays, 0, overlays, 0, spriteBatchCount);
        System.arraycopy(spriteGlints, 0, glints, 0, spriteBatchCount);
        spriteOverlays = overlays;
        spriteGlints = glints;
        spriteBatches = batches;
        spriteCutoffs = cutoffs;
        return true;
    }

    private void clearSprites() {
        spriteBatchCount = 0;
        spriteScratchVertices = 0;
    }

    /**
     * Moves this frame's sprite vertices onto the card and makes sure there are
     * enough quad indices for them.
     *
     * Called after the translucent fence has been waited for and before any
     * command is recorded: both things it touches — the per-slot vertex buffer
     * and the shared index buffer — may be destroyed and rebuilt here, and
     * neither may be in flight when that happens.
     */
    private boolean prepareSprites() {
        if (spriteBatchCount == 0 || spritePipeline == 0) {
            return false;
        }
        int slot = activeFrameSlot;
        long bytes = (long) spriteScratchVertices * SPRITE_VERTEX_STRIDE;
        if (!ensureSpriteVertexCapacity(slot, bytes)) {
            clearSprites();
            return false;
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress0(spriteScratch), spriteVertexMapped[slot], bytes);
        noteCreatureSpan(slot);
        int quads = spriteScratchVertices / 4;
        if (quads > quadIndexCapacityQuads) {
            // Growing it destroys the buffer, and the opaque pass of the frame
            // before this one may still be reading from it — its fence is a
            // different one from the fence waited on above. Rare enough to
            // afford the bluntest possible answer.
            vkDeviceWaitIdle(device());
            ensureQuadIndexCapacity(quads);
        }
        return true;
    }

    /**
     * Where this frame's creature vertices sit in the sprite buffer.
     *
     * A structure can be built straight out of that buffer — it is the same
     * twenty-eight bytes a vertex with the position first that the terrain uses
     * — but only over one continuous run, and the buffer holds particles and
     * weather as well. Creatures are submitted at the end of the entity pass
     * and everything else afterwards, so in practice they are a run at the
     * front; this measures rather than trusts that, and says nothing at all
     * when they turn out to be scattered.
     */
    private int[] creatureFirstVertex;
    private int[] creatureVertexCount;
    private boolean creatureSpanWarned;

    private void noteCreatureSpan(int slot) {
        if (creatureFirstVertex == null || creatureFirstVertex.length != framesInFlight) {
            creatureFirstVertex = new int[framesInFlight];
            creatureVertexCount = new int[framesInFlight];
        }
        int first = Integer.MAX_VALUE;
        int end = 0;
        int total = 0;
        for (int b = 0; b < spriteBatchCount; b++) {
            if (spriteBatches[b * 3 + 2] < FIRST_SKIN_SLOT) {
                continue;
            }
            int start = spriteBatches[b * 3];
            int count = spriteBatches[b * 3 + 1];
            first = Math.min(first, start);
            end = Math.max(end, start + count);
            total += count;
        }
        if (total == 0 || end - first != total) {
            // Either there are none, or they are not one run — a structure over
            // the gap would contain particles, and a particle is a quad turned
            // to face the camera. Its shadow would be a rectangle that turns
            // with the player.
            creatureVertexCount[slot] = 0;
            if (total != 0 && !creatureSpanWarned) {
                creatureSpanWarned = true;
                LOGGER.info("Creature geometry arrived in more than one run this frame; "
                        + "their shadows are skipped rather than guessed at");
            }
            return;
        }
        creatureFirstVertex[slot] = first;
        creatureVertexCount[slot] = total;
    }

    /** Records this frame's sprite batches into the translucent pass. */
    /**
     * @param creatures true to draw only the creature skins, false for
     *                  everything else — the two live in different subpasses
     *                  and cannot be recorded together
     */
    private void drawSprites(MemoryStack stack, VkCommandBuffer cmd, boolean creatures) {
        int slot = activeFrameSlot;
        long boundPipeline = 0;
        vkCmdBindVertexBuffers(cmd, 0, stack.longs(spriteVertexBuffers[slot]), stack.longs(0L));
        // The translucent set of this frame, for the light map and the frame
        // constants. Set 1 is the one that changes between batches.
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, spritePipelineLayout, 0,
                stack.longs(drawDescriptorSets[slot * BATCHES_PER_FRAME + LAYER_TRANSLUCENT]), null);
        ByteBuffer push = stack.calloc(48);
        // Everything in here except the alpha cutoff is the same for every
        // batch of the call, so it is written once rather than per batch.
        push.putFloat(4, creatures ? 1.0f : 0.0f);
        push.putFloat(8, showCreatureLight ? 1.0f : 0.0f);
        push.putFloat(16, sunDirection[0]);
        push.putFloat(20, sunDirection[1]);
        push.putFloat(24, sunDirection[2]);
        // Faded out with the sun itself. Below the horizon there is no sky
        // light left to shade, and holding the term on through dusk makes a
        // creature's shading outlive the reason for it.
        push.putFloat(28, creatureLight * Math.max(0.0f, Math.min(1.0f, sunDirection[1] * 5.0f)));
        // One allocation, reused: the stack frame is not popped until the whole
        // pass has been recorded, and a batch list can be thousands long.
        LongBuffer setHandle = stack.mallocLong(1);
        long boundTexture = 0;
        for (int b = 0; b < spriteBatchCount; b++) {
            int first = spriteBatches[b * 3];
            int count = spriteBatches[b * 3 + 1];
            int sheet = spriteBatches[b * 3 + 2];
            // Which half of the pass this batch belongs to. A creature skin is
            // any slot past the game's own sheets, and those are drawn in the
            // subpass that owns the depth attachment.
            if ((sheet >= FIRST_SKIN_SLOT) != creatures) {
                continue;
            }
            long set = spriteSets[sheet];
            // Slot 0 borrows the terrain's atlas and has no image of its own;
            // the rest must have one. A set whose image was freed by a resource
            // reload still looks like a valid handle and would take the device
            // down, which is the sort of thing that is invisible until it is
            // fatal — so the check is on the image, not on the set.
            if (set == 0 || (sheet != 0 && spriteImages[sheet] == 0)) {
                continue; // sheet never arrived, or went away; dropped, not drawn wrong
            }
            // Which state this batch wants is decided by which slot it is in:
            // the game's own sheets are particles and weather, everything past
            // them is a creature skin.
            long wanted;
            if (creatures && spriteGlints[b] && creatureGlintPipeline != 0) {
                wanted = creatureGlintPipeline;
            } else if (creatures && creaturePipeline != 0) {
                wanted = creaturePipeline;
            } else if (sheet >= FIRST_SKIN_SLOT && spriteOpaquePipeline != 0) {
                wanted = spriteOpaquePipeline;
            } else {
                wanted = spritePipeline;
            }
            if (wanted != boundPipeline) {
                boundPipeline = wanted;
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, wanted);
            }
            push.putFloat(0, spriteCutoffs[b]);
            push.putFloat(12, spriteGlints[b] ? 1.0f : 0.0f);
            int overlay = spriteOverlays[b];
            push.putFloat(32, ((overlay >>> 16) & 0xFF) / 255.0f);
            push.putFloat(36, ((overlay >>> 8) & 0xFF) / 255.0f);
            push.putFloat(40, (overlay & 0xFF) / 255.0f);
            push.putFloat(44, ((overlay >>> 24) & 0xFF) / 255.0f);
            vkCmdPushConstants(cmd, spritePipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
            if (set != boundTexture) {
                boundTexture = set;
                setHandle.put(0, set);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, spritePipelineLayout,
                        1, setHandle, null);
            }
            // firstIndex stays at zero and the offset goes on the vertices:
            // every batch is whole quads, so the same run of indices serves all
            // of them and only where they read from moves.
            vkCmdDrawIndexed(cmd, count / 4 * 6, 1, 0, first, 0);
            spriteFrameBatches++;
            spriteFrameVertices += count;
        }
    }

    private boolean ensureSpriteVertexCapacity(int slot, long bytes) {
        if (spriteVertexBuffers == null) {
            spriteVertexBuffers = new long[framesInFlight];
            spriteVertexMemories = new long[framesInFlight];
            spriteVertexMapped = new long[framesInFlight];
            spriteVertexCapacity = new long[framesInFlight];
        }
        if (spriteVertexCapacity[slot] >= bytes && spriteVertexBuffers[slot] != 0) {
            return true;
        }
        long want = Math.max(bytes, 1L << 18);
        want = Long.highestOneBit(want) * 2;
        destroySpriteVertexBuffer(slot);
        try (MemoryStack stack = stackPush()) {
            // Ray tracing reads creature geometry straight out of this buffer
            // to build a structure over it, so it needs an address and the
            // right to be a build input — asked for only where tracing is
            // actually on, because both are features a device has to have.
            int usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
            boolean traced = ctx.isRayTracingEnabled() && ctx.isRayQuerySupported();
            if (traced) {
                usage |= org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                        | org.lwjgl.vulkan.KHRAccelerationStructure
                                .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
            }
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(want)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(device(), info, null, pBuffer) != VK_SUCCESS) {
                return false;
            }
            long buffer = pBuffer.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), buffer, req);
            int type = findMemoryTypeOrNone(stack, req.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            if (type < 0) {
                vkDestroyBuffer(device(), buffer, null);
                return false;
            }
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(type);
            if (traced) {
                // Asking for the address on the buffer is not enough: the
                // memory under it has to be allocated knowing that an address
                // will be taken, or the call to take one is invalid.
                alloc.pNext(org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                        .flags(org.lwjgl.vulkan.VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT)
                        .address());
            }
            LongBuffer pMemory = stack.mallocLong(1);
            if (vkAllocateMemory(device(), alloc, null, pMemory) != VK_SUCCESS) {
                vkDestroyBuffer(device(), buffer, null);
                return false;
            }
            long memory = pMemory.get(0);
            check(vkBindBufferMemory(device(), buffer, memory, 0), "vkBindBufferMemory(sprites)");
            PointerBuffer ppData = stack.mallocPointer(1);
            check(vkMapMemory(device(), memory, 0, want, 0, ppData), "vkMapMemory(sprites)");
            spriteVertexBuffers[slot] = buffer;
            spriteVertexMemories[slot] = memory;
            spriteVertexMapped[slot] = ppData.get(0);
            spriteVertexCapacity[slot] = want;
        }
        LOGGER.info("Sprite vertex buffer {} sized for {} KiB", slot, want / 1024);
        return true;
    }

    private void destroySpriteVertexBuffer(int slot) {
        if (spriteVertexBuffers == null || spriteVertexBuffers[slot] == 0) {
            return;
        }
        vkUnmapMemory(device(), spriteVertexMemories[slot]);
        vkDestroyBuffer(device(), spriteVertexBuffers[slot], null);
        vkFreeMemory(device(), spriteVertexMemories[slot], null);
        spriteVertexBuffers[slot] = 0;
        spriteVertexMemories[slot] = 0;
        spriteVertexMapped[slot] = 0;
        spriteVertexCapacity[slot] = 0;
    }

    private void destroySpriteImage(int slot) {
        if (spriteImages[slot] == 0) {
            return;
        }
        vkDeviceWaitIdle(device());
        vkDestroyImageView(device(), spriteViews[slot], null);
        vkDestroyImage(device(), spriteImages[slot], null);
        vkFreeMemory(device(), spriteMemories[slot], null);
        spriteImages[slot] = 0;
        spriteViews[slot] = 0;
        spriteMemories[slot] = 0;
        spriteGlIds[slot] = 0;
    }

    /** Forgets every uploaded sheet, because a resource reload renumbers them. */
    private void forgetSpriteSheets() {
        for (int slot = 1; slot < SPRITE_SLOTS; slot++) {
            destroySpriteImage(slot);
        }
    }

    private void writeSpriteSet(int slot, long view, long sampler) {
        if (spriteSets[slot] == 0 || view == 0 || sampler == 0) {
            return;
        }
        vkDeviceWaitIdle(device());
        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).sampler(sampler).imageView(view)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(spriteSets[slot]).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            vkUpdateDescriptorSets(device(), write, null);
        }
    }

    /**
     * Whether the depth the game owns can be put back into the shared image at
     * all — by the hardware copy, or failing that by the shader that replaced
     * it. Without one of the two the translucent layer has nothing to test
     * itself against and must stay where it is.
     */
    private boolean canReturnDepth() {
        return depthBlit || (glDepthWriteFbo != -1 && gameDepthTexture != 0);
    }

    private boolean renderTranslucent(int[] chunks, int chunkCount, float[] mvp,
                                      double viewX, double viewY, double viewZ,
                                      VkChunkMirror mirror) {
        // Sprites are drawn in this pass, so a frame with particles and no
        // water still needs it. Without that second term, standing in a desert
        // and breaking a block put the particles nowhere at all.
        if (translucentFramebuffer == 0 || !canReturnDepth()
                || (chunkCount == 0 && spriteBatchCount == 0)) {
            // With no way to get the game's depth back, drawing the layer
            // would be worse than leaving it where it is.
            return false;
        }
        int slot = activeFrameSlot;
        boolean sprites;
        try (MemoryStack stack = stackPush()) {
            // Timed apart from everything after it, and that separation is the
            // whole point of this counter.
            //
            // This wait sat inside the number the log prints as "our command
            // recording". A blocking wait for the card is not recording — it is
            // the opposite, the processor doing nothing at all — and one frame
            // that reported thirty-eight milliseconds of recording out of
            // forty-three was almost certainly this line. A number that answers
            // a different question than its name says is worse than no number:
            // it sent the search into the recording code, where there was
            // nothing to find, and the alternative was written off in the notes
            // as excluded without ever being measured.
            long waitStart = System.nanoTime();
            check(vkWaitForFences(device(), translucentFences[slot], true, Long.MAX_VALUE),
                    "vkWaitForFences(translucent)");
            translucentWaitNanos += System.nanoTime() - waitStart;
            check(vkResetFences(device(), translucentFences[slot]), "vkResetFences(translucent)");

            // Both the buffer this writes and the index buffer it may resize
            // are read by the commands recorded below, so it goes before the
            // first of them and after the fence that says the last frame to
            // use them has finished.
            sprites = prepareSprites();

            importGlDepth();

            VkCommandBuffer cmd = translucentCommandBuffers[slot];
            check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer(translucent)");
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer(translucent)");
            if (timestampsSupported) {
                // Reset with the other pair at the top of the opaque buffer,
                // which is submitted to the same queue before this one, so the
                // reset has run by the time this is written.
                vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        queryPool, slot * 4 + 2);
            }

            // One clear value only: the depth attachment is loaded, not cleared.
            VkClearValue.Buffer clears = VkClearValue.calloc(1, stack);
            clears.get(0).color().float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 0.0f);
            VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(translucentRenderPass)
                    .framebuffer(translucentFramebuffer)
                    .renderArea(VkRect2D.calloc(stack)
                            .extent(VkExtent2D.calloc(stack).width(width).height(height)))
                    .pClearValues(clears);
            vkCmdBeginRenderPass(cmd, rpBegin, VK_SUBPASS_CONTENTS_INLINE);

            vkCmdBindIndexBuffer(cmd, quadIndexBuffer, 0, VK_INDEX_TYPE_UINT32);
            org.lwjgl.vulkan.VkViewport.Buffer viewport = org.lwjgl.vulkan.VkViewport.calloc(1, stack);
            viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).extent(VkExtent2D.calloc(stack).width(width).height(height));
            vkCmdSetScissor(cmd, 0, scissor);

            // Creatures first, in the subpass that owns the depth attachment,
            // so that everything after them is tested against where they are.
            if (sprites) {
                drawSprites(stack, cmd, true);
            }
            // Once, always, whether or not anything was drawn above: the pass
            // has two subpasses and a command buffer that ends inside the first
            // of them is not a valid recording.
            vkCmdNextSubpass(cmd, VK_SUBPASS_CONTENTS_INLINE);

            // Particles and weather next, water last, which is the order
            // vanilla draws them in: a bubble behind a water surface has to end
            // up under the water's colour rather than over it. Depth does not
            // settle it between these two — the attachment is read-only from
            // here on, so nothing in this subpass occludes anything else in it
            // — which leaves the order of the draws as the whole of the answer.
            if (sprites) {
                drawSprites(stack, cmd, false);
            }
            clearSprites();

            VkCommandBuffer previous = commandBuffer;
            commandBuffer = cmd;
            try {
                drawChunks(LAYER_TRANSLUCENT, chunks, chunkCount, mvp, viewX, viewY, viewZ, mirror);
            } finally {
                commandBuffer = previous;
            }

            vkCmdEndRenderPass(cmd);
            if (timestampsSupported) {
                vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                        queryPool, slot * 4 + 3);
            }
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer(translucent)");

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd))
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(vkTranslucentWaitSemaphore))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT))
                    .pSignalSemaphores(stack.longs(vkTranslucentSignalSemaphore));
            translucentSignalFenceValue++;
            check(vkQueueSubmit(ctx.getGraphicsQueue(), submit, translucentFences[slot]),
                    "vkQueueSubmit(translucent)");
        }
        compositeTranslucent();
        return true;
    }

    /**
     * Copies the depth the game now owns into the shared image, then tells
     * Vulkan it may read it.
     *
     * This is the mirror of {@link #blitDepth}, which sends depth the other
     * way after the opaque pass. Between the two, OpenGL has drawn entities,
     * and their depth is exactly what the translucent layer has to be tested
     * against.
     */
    private void importGlDepth() {
        GL11C.glGetError();
        if (depthBlit) {
            int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, glDepthBlitFbo);
            GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
        } else {
            importGlDepthByShader();
        }
        int error = GL11C.glGetError();
        if (error != 0 && !glErrorLogged) {
            glErrorLogged = true;
            LOGGER.error("Handing the game's depth back to Vulkan failed with 0x{}",
                    Integer.toHexString(error));
        }
        try (MemoryStack stack = stackPush()) {
            IntBuffer noBuffers = stack.mallocInt(0);
            IntBuffer textures = stack.ints(glDepthTexture);
            IntBuffer layouts = stack.ints(glDepthHandoffLayout());
            translucentWaitFenceValue++;
            setFenceValue(glTranslucentSignalSemaphore, translucentWaitFenceValue);
            EXTSemaphore.glSignalSemaphoreEXT(glTranslucentSignalSemaphore, noBuffers, textures, layouts);
        }
        // Without this the signal can sit in the GL command stream while the
        // Vulkan queue is already waiting on it, and neither side moves.
        GL11C.glFlush();
    }

    /**
     * The same hand-off as the blit above, for cards whose shared depth image
     * is not in the game's format.
     *
     * Two steps, because a shader can only read a texture and the game's depth
     * is not one: it is copied into a texture of the game's own format first,
     * then written into the shared image a fragment at a time. Both steps stay
     * on the card — nothing travels back to the processor.
     */
    private void importGlDepthByShader() {
        depthImportTimer.begin();
        int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int prevActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);

        // Straight out of whatever the game is drawing into, in its format.
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, gameDepthTexture);
        GL11C.glCopyTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);

        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, glDepthWriteFbo);
        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT
                | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
                | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                | org.lwjgl.opengl.GL11.GL_VIEWPORT_BIT
                | org.lwjgl.opengl.GL11.GL_POLYGON_BIT);
        // Everything between here and the pop is inside a try, and the reason
        // is what this particular pass borrows. It is the only place in this
        // renderer that switches colour writes off — it wants the depth of a
        // full-screen quad and none of its colour — and a colour mask left
        // shut is not a wrong picture, it is no picture: every draw after it
        // computes correctly and writes nothing. On top of that this runs only
        // where the shared depth is not in the game's format, which is to say
        // on one make of card and not the other, so anything thrown here would
        // have blackened a world that the machine it was written on could
        // never reproduce.
        try {
            GL11C.glViewport(0, 0, width, height);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
            GL11C.glColorMask(false, false, false, false);
            // Every fragment replaces what is there: this is a copy wearing the
            // clothes of a draw, so the test that would normally reject the far
            // half of it has to be told to accept everything.
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_ALWAYS);
            GL11C.glDepthMask(true);

            GL20C.glUseProgram(depthImportProgram);
            GL20C.glUniform2f(depthImportInvSizeUniform, 1.0f / width, 1.0f / height);
            org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
            org.lwjgl.opengl.GL11.glVertex2f(-1.0f, -1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(1.0f, -1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(1.0f, 1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(-1.0f, 1.0f);
            org.lwjgl.opengl.GL11.glEnd();
        } finally {
            org.lwjgl.opengl.GL11.glPopAttrib();
        }
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
        GL20C.glUseProgram(prevProgram);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
        GL13C.glActiveTexture(prevActive);
        depthImportTimer.end();
    }

    /** Blends the translucent target over the game's frame. */
    private void compositeTranslucent() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer noBuffers = stack.mallocInt(0);
            IntBuffer textures = stack.ints(glTranslucentTexture);
            IntBuffer layouts = stack.ints(EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT);
            setFenceValue(glTranslucentWaitSemaphore, translucentSignalFenceValue);
            EXTSemaphore.glWaitSemaphoreEXT(glTranslucentWaitSemaphore, noBuffers, textures, layouts);

            int prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            int prevActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT
                    | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
                    | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                    | org.lwjgl.opengl.GL11.GL_TEXTURE_BIT
                    | org.lwjgl.opengl.GL11.GL_CURRENT_BIT
                    | org.lwjgl.opengl.GL11.GL_POLYGON_BIT);

            GL11C.glDisable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            // Occlusion was settled by the depth test in the Vulkan pass, so
            // this only has to put the colour down in the right proportion.
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(false);
            GL11C.glEnable(GL11C.GL_BLEND);
            // The target holds premultiplied colour, so the source is added as
            // it is rather than being scaled by its alpha a second time.
            GL11C.glBlendFunc(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);

            GL20C.glUseProgram(translucentCompositeProgram);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glTranslucentTexture);

            org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
            org.lwjgl.opengl.GL11.glVertex2f(-1.0f, -1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(1.0f, -1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(1.0f, 1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(-1.0f, 1.0f);
            org.lwjgl.opengl.GL11.glEnd();

            org.lwjgl.opengl.GL11.glPopAttrib();
            GL20C.glUseProgram(prevProgram);
            GL13C.glActiveTexture(prevActive);
            GL11C.glFlush();
        }
    }

    /** GL side: wait for Vulkan, draw the shared frame into the game's framebuffer, signal back. */
    private void composite() {
        frameSignalled = false;
        // The wait has a timer of its own inside, and elapsed-time queries do
        // not nest, so this one starts after it. What it measures is the work.
        //
        // Ended only if it was started: the wait comes first and can throw, and
        // closing a query that was never opened is a GL error and a ring left
        // one slot out of step for the rest of the session.
        compositeTimerRunning = false;
        try {
            compositeInner();
        } finally {
            if (compositeTimerRunning) {
                compositeTimerRunning = false;
                compositeTimer.end();
            }
        }
    }

    private void compositeInner() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer noBuffers = stack.mallocInt(0);
            IntBuffer textures = stack.ints(glColorTexture, glDepthTexture);
            IntBuffer layouts = stack.ints(EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT,
                    EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT);
            // The imported semaphore is the first thing on this path that the
            // graphics driver has to honour across two APIs, and a driver that
            // never signals it stalls here until the operating system decides
            // the card is gone. That looks exactly like the log simply ending.
            if (SHARED_SEMAPHORES) {
                setFenceValue(glWaitSemaphore, signalFenceValue);
                firstFrameStage("waiting on the Vulkan semaphore from OpenGL");
                compositeWaitTimer.begin();
                try {
                    EXTSemaphore.glWaitSemaphoreEXT(glWaitSemaphore, noBuffers, textures,
                            layouts);
                } finally {
                    compositeWaitTimer.end();
                }
                firstFrameStage("semaphore taken, compositing");
            } else {
                firstFrameStage("compositing (semaphores off, both sides go idle)");
            }

            int prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            int prevActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT
                    | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
                    | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                    | org.lwjgl.opengl.GL11.GL_TEXTURE_BIT
                    | org.lwjgl.opengl.GL11.GL_CURRENT_BIT
                    | org.lwjgl.opengl.GL11.GL_POLYGON_BIT);

            GL11C.glDisable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDepthMask(true);

            if (depthBlit) {
                depthBlitTimer.begin();
                try {
                    blitDepth();
                } finally {
                    depthBlitTimer.end();
                }
            }

            // The composite's own clock starts here, and where it starts is
            // the whole of what it means.
            //
            // An elapsed-time query cannot contain another, so this one has to
            // begin after both of the passes that are timed separately: the
            // wait for Vulkan, which is idling rather than work, and the depth
            // blit, which the hardware does. The three numbers on the line add
            // up to the cost of handing a frame back.
            //
            // It began in the translucent composite instead, which is a
            // different method that starts with the same twelve lines, and
            // nothing ever closed it: the query stayed open, no slot was ever
            // marked as owing an answer, and every later attempt to open one
            // was rejected by the driver for being inside the first. The line
            // printed 0.00 ms with a note that no result had been collected —
            // and this is the number a whole plan was waiting on.
            compositeTimer.begin();
            compositeTimerRunning = true;

            // Before the colour goes into the frame: what the frame receives is
            // the terrain already darkened where it cannot see the sky.
            //
            // Fenced off from the rest of the composite on purpose. Corners in
            // the world are decoration; the world itself is not. Whatever goes
            // wrong in here costs this one effect for the session and the frame
            // carries on undarkened, rather than taking the terrain renderer
            // down with it and dropping the player back to vanilla GL.
            boolean motion = false;
            if (!motionFailed && motionWanted()) {
                int frameFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
                try {
                    motion = motionPass();
                } catch (Throwable t) {
                    LOGGER.error("Motion vectors failed; off for this session", t);
                    motionFailed = true;
                    GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, frameFbo);
                }
            }

            // Between the two: it needs what the motion pass produced, and the
            // occlusion is computed from depth alone and has no grain to lose.
            accumApplied = false;
            if (!accumFailed) {
                int frameFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
                try {
                    accumApplied = accumPass(motion);
                } catch (Throwable t) {
                    LOGGER.error("Frame accumulation failed; off for this session", t);
                    accumFailed = true;
                    GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, frameFbo);
                }
            }

            boolean ao = false;
            // Not when the whole scene is being darkened later instead: the
            // same corners would be shaded twice, once here from a depth image
            // holding only blocks and once there from one holding everything.
            if (aoWanted() && !sceneOcclusion && !aoFailed) {
                int frameFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
                try {
                    ao = aoPass(glDepthTexture);
                } catch (Throwable t) {
                    LOGGER.error("Ambient occlusion failed; off for this session", t);
                    aoFailed = true;
                    GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, frameFbo);
                }
            }

            GL20C.glUseProgram(compositePrograms[depthBlit ? 1 : 0]);
            GL20C.glUniform1f(compositeAoUniforms[depthBlit ? 1 : 0], ao ? 1.0f : 0.0f);
            GL20C.glUniform1f(compositeAoOnlyUniforms[depthBlit ? 1 : 0],
                    ao && showOcclusion ? 1.0f : 0.0f);
            GL20C.glUniform1f(compositeMotionUniforms[depthBlit ? 1 : 0],
                    motion && showMotion ? 1.0f : 0.0f);
            GL20C.glUniform1f(compositeMotionGhostUniforms[depthBlit ? 1 : 0],
                    motionOverWorld ? 1.0f : 0.0f);
            GL20C.glUniform1f(compositeAccumUniforms[depthBlit ? 1 : 0],
                    accumApplied ? 1.0f : 0.0f);
            if (accumApplied) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE4);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, accumTexture[accumIndex]);
            }
            if (motion) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, motionTexture);
            }
            if (ao) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, aoTexture);
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glColorTexture);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glDepthTexture);

            if (depthBlit) {
                // Depth already carries the terrain; the quad only paints colour.
                GL11C.glDisable(GL11C.GL_DEPTH_TEST);
                GL11C.glDepthMask(false);
            } else {
                GL11C.glEnable(GL11C.GL_DEPTH_TEST);
                GL11C.glDepthFunc(GL11C.GL_LEQUAL);
                // Re-opened here and not once at the top of the composite: the
                // ambient occlusion and motion passes above both close the mask
                // for their own fullscreen quads and leave it closed. On the
                // blit path that is harmless, because the depth was already
                // copied in before they ran; here the quad below is the only
                // thing that ever writes depth, and a closed mask throws it
                // away silently. The frame still looks right — the colour lands
                // either way — and everything the game draws afterwards stops
                // being occluded by the world.
                GL11C.glDepthMask(true);
            }

            org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
            org.lwjgl.opengl.GL11.glVertex2f(-1.0f, -1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(1.0f, -1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(1.0f, 1.0f);
            org.lwjgl.opengl.GL11.glVertex2f(-1.0f, 1.0f);
            org.lwjgl.opengl.GL11.glEnd();

            // The sky, while it is still the only thing behind the terrain.
            //
            // Here and nowhere later on purpose: the game draws the sky before
            // the world and the clouds after it, so at this one moment every
            // pixel the terrain did not cover is sky and nothing else. A pass
            // after the whole frame would have to tell sky from a distant hill,
            // and the only thing that could answer is a depth buffer the game
            // keeps as a renderbuffer and will not let anyone read.
            if (skyGradient > 0.0f) {
                paintSkyGradient();
            }

            // After the terrain is in the frame and before the game draws
            // anything else into it.
            if (bloomStrength > 0.0f && !bloomFailed) {
                bloomPrepare();
            }

            org.lwjgl.opengl.GL11.glPopAttrib();
            GL20C.glUseProgram(prevProgram);
            GL13C.glActiveTexture(prevActive);

            firstFrameStage("terrain drawn into the game's frame");
            if (SHARED_SEMAPHORES) {
                waitFenceValue++;
                setFenceValue(glSignalSemaphore, waitFenceValue);
                EXTSemaphore.glSignalSemaphoreEXT(glSignalSemaphore, noBuffers, textures, layouts);
                GL11C.glFlush();
            } else {
                // The next Vulkan frame writes these images with nothing told
                // to wait for this read, so the read has to be over first.
                GL11C.glFinish();
            }
            if (tracingFirstFrame) {
                tracingFirstFrame = false;
                LOGGER.info("First terrain frame: complete");
            }

            if (!glErrorLogged) {
                int error = GL11C.glGetError();
                if (error != 0) {
                    glErrorLogged = true;
                    LOGGER.error("GL error 0x{} during terrain composite (frame {})",
                            Integer.toHexString(error), frameCounter);
                }
            }
        }
    }

    /**
     * Pull the glow out of the terrain, blur it, add it back.
     *
     * Three fullscreen quads, all of them on targets the composite already had
     * to make. What is glowing does not have to be guessed at from brightness:
     * the terrain shader writes it into the alpha of every opaque pixel, which
     * was carrying the constant 1.0 and nothing else. Guessing would have meant
     * bloom on snow and on sand in sunlight, which are as bright on screen as
     * lava and are not lights.
     *
     * The glow is added with a quad of its own rather than folded into the
     * composite, and that is not tidiness: the composite discards where there
     * is no terrain, so that the sky shows through, and a glow that stopped at
     * the silhouette of a lava lake would be a lake with a hard edge. Added
     * separately and blended, it reaches over the sky the way light does.
     */
    /**
     * Keeps what the last passes will need after the Vulkan target is gone.
     *
     * By the time the glow is drawn, the colour target has been handed back
     * through a semaphore and reading it would race the next frame. What the
     * glow needs from it is two things, and both are copied here into a texture
     * of this renderer's own: which pixels are lights, and what the terrain
     * looked like before the game drew anything over it. The second is what
     * makes a creature block the light behind it instead of being lit through.
     *
     * Half resolution. Both uses are comparisons rather than colour, and a
     * boundary two pixels soft is better than a hard one for either of them.
     */
    /**
     * How much of its surroundings each terrain pixel can see, into a texture.
     *
     * Everything it needs is in the depth buffer. A view-space position comes
     * back from a depth and the two numbers the projection is made of; the
     * surface's direction comes from how that position changes across the
     * screen, which is exact here because every face of a block is flat. Then
     * eight neighbours are asked whether they stand in front of the surface,
     * and how much they do is the answer.
     *
     * Run inside the composite, while the depth image is still this renderer's
     * to read, and before the colour is drawn into the frame, because what the
     * frame receives is the colour already darkened.
     */
    private boolean aoPass(int depthTexture) {
        if (!ensureAoTargets()) {
            return false;
        }
        // The projection the world is being drawn with, read rather than
        // carried: this runs inside the game's own world pass, so it is still
        // set, and the four numbers wanted from it are all that a depth needs
        // to become a position again.
        //
        // Through GL11C, not GL11, and this is not a style choice. The two
        // libraries name this call differently — LWJGL 2 has
        // glGetFloat(int, FloatBuffer), LWJGL 3 has glGetFloatv — and this
        // side of the mod compiles with both on the path but runs only with
        // LWJGL 3. The LWJGL 2 spelling compiles here and then fails to link
        // in the game. GL11C has no counterpart in LWJGL 2 at all, so naming
        // it is the compiler checking that this is the right library.
        projectionMatrix.clear();
        GL11C.glGetFloatv(org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX, projectionMatrix);
        float m0 = projectionMatrix.get(0);
        float m5 = projectionMatrix.get(5);
        float m10 = projectionMatrix.get(10);
        float m14 = projectionMatrix.get(14);
        if (m0 == 0.0f || m5 == 0.0f || m10 == 1.0f || m10 == -1.0f) {
            return false; // not a perspective projection; nothing to reconstruct
        }
        float near = m14 / (m10 - 1.0f);
        float far = m14 / (m10 + 1.0f);
        if (!(near > 0.0f) || !(far > near)) {
            return false;
        }

        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_VIEWPORT_BIT);
        // Balanced whatever happens: an attribute pushed and never popped is a
        // leak the driver keeps for the rest of the session, and the caller is
        // still holding a push of its own around this one.
        try {
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(false);
            GL11C.glDisable(GL11C.GL_BLEND);

            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, aoFbo);
            GL11C.glViewport(0, 0, aoWidth, aoHeight);
            GL20C.glUseProgram(aoProgram);
            GL20C.glUniform2f(aoInvSize, 1.0f / aoWidth, 1.0f / aoHeight);
            GL20C.glUniform4f(aoProjUniform, 1.0f / m0, 1.0f / m5, near, far);
            GL20C.glUniform1f(aoRadiusUniform, aoRadius);
            GL20C.glUniform1f(aoStrengthUniform, aoStrength);
            if (aoCamWrapUniform >= 0) {
                GL20C.glUniform3f(aoCamWrapUniform, wrapForJitter(viewWorldX),
                        wrapForJitter(viewWorldY), wrapForJitter(viewWorldZ));
            }
            writeContactSun();
            writeCloudShadow();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTexture);
            fullscreenQuad();

            // Smoothed, because sixteen samples of a neighbourhood is a noisy
            // answer to a question whose answer is smooth.
            //
            // The same blur bloom uses, walking further between its taps. A
            // blur wide enough for a glow is not wide enough for this: what a
            // glow needs hidden is the grain of one bright pixel, and what a
            // corner needs hidden is the grain of sixteen directions, which is
            // coarser and lives on a larger scale. Widening costs nothing —
            // the taps stay five and only the distance between them changes.
            GL20C.glUseProgram(bloomBlurProgram);
            GL20C.glUniform2f(bloomBlurInvSize, 1.0f / aoWidth, 1.0f / aoHeight);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, aoBlurFbo);
            GL20C.glUniform2f(bloomBlurStep, AO_BLUR_SPREAD, 0.0f);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, aoTexture);
            fullscreenQuad();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, aoFbo);
            GL20C.glUniform2f(bloomBlurStep, 0.0f, AO_BLUR_SPREAD);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, aoBlurTexture);
            fullscreenQuad();
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
            org.lwjgl.opengl.GL11.glPopAttrib();
        }
        return true;
    }

    /**
     * The sun in the space the occlusion pass works in, and how dark it may go.
     *
     * Zero length means "do not march": below the horizon there is no direction
     * to march along, and the shader is given one thing to test rather than a
     * direction and a flag that can disagree with it.
     *
     * The direction is turned by the rotation of the modelview and nothing
     * else. A direction has no position, so the translation in the fourth
     * column is not wanted — carrying it would make the sun swing round the
     * world as the player walks, which is the classic way this goes wrong and
     * looks like the shadows lagging rather than like the wrong maths.
     */
    /**
     * How wide the lattice is that the camera's world position is folded over
     * before a hash sees it.
     *
     * Wide enough that no two places a player can see at once land on the same
     * fold, and narrow enough that the number handed to the shader keeps a
     * fraction of a block: a float has about seven digits, and a coordinate in
     * the millions spends all of them on the whole part. This is the same
     * reason the waves and the sway fold their lattice, written out here
     * because the value differs.
     */
    private static final double JITTER_LATTICE = 4096.0;

    private static float wrapForJitter(double world) {
        return (float) (world - Math.floor(world / JITTER_LATTICE) * JITTER_LATTICE);
    }

    /**
     * How much of a sun-driven shadow to believe at this hour, 0 to 1.
     *
     * One ramp for all of them, and that is the whole point of it. There are
     * three things here that darken the world because of where the sun is —
     * the traced shadow, the contact march and the cloud sheet — and each had
     * arrived at its own hour: the traced one eased in between two and thirty
     * hundredths of sun height, the contact march climbed straight from zero
     * to a fifth, and the cloud shadow appeared at five hundredths already at
     * full strength. Three arrivals of one sunrise, and the third of them a
     * step. A fourth system laid over that would be laid over a disagreement,
     * which is why the shadow map waits on this.
     *
     * The curve is the traced one's, because it was the one chosen against a
     * sunrise rather than for convenience: a shadow that begins the instant
     * the sun clears the horizon appears over the whole world in one frame,
     * and at that moment it is also at its longest and sweeping fastest.
     */
    private float sunRamp() {
        float t = (sunDirection[1] - 0.02f) / (0.30f - 0.02f);
        t = Math.max(0.0f, Math.min(1.0f, t));
        return t * t * (3.0f - 2.0f * t);
    }

    private void writeContactSun() {
        if (aoContactUniform < 0 || aoSunUniform < 0) {
            return;
        }
        if (contactShadows <= 0.0f || sunRamp() <= 0.0f) {
            GL20C.glUniform1f(aoContactUniform, 0.0f);
            GL20C.glUniform3f(aoSunUniform, 0.0f, 0.0f, 0.0f);
            return;
        }
        modelViewMatrix.clear();
        GL11C.glGetFloatv(org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX, modelViewMatrix);
        float x = sunDirection[0];
        float y = sunDirection[1];
        float z = sunDirection[2];
        float vx = modelViewMatrix.get(0) * x + modelViewMatrix.get(4) * y
                + modelViewMatrix.get(8) * z;
        float vy = modelViewMatrix.get(1) * x + modelViewMatrix.get(5) * y
                + modelViewMatrix.get(9) * z;
        float vz = modelViewMatrix.get(2) * x + modelViewMatrix.get(6) * y
                + modelViewMatrix.get(10) * z;
        float len = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (!(len > 1.0e-4f)) {
            // A modelview with no rotation left in it is not this pass's to
            // interpret; the pass simply does nothing this frame.
            GL20C.glUniform1f(aoContactUniform, 0.0f);
            GL20C.glUniform3f(aoSunUniform, 0.0f, 0.0f, 0.0f);
            return;
        }
        GL20C.glUniform3f(aoSunUniform, vx / len, vy / len, vz / len);
        // Faded out as the sun reaches the horizon, where a shadow marched
        // along the ground stretches past anything on the screen and every
        // sample lands on the same wall. The curve is shared with the other
        // two sun-driven shadows; see sunRamp.
        GL20C.glUniform1f(aoContactUniform, contactShadows * sunRamp());
    }

    /**
     * Where the game's clouds sit on their own sheet, from the camera's point.
     *
     * The wrapping is done here, in double precision, and only the wrapped
     * remainder is handed over. A world coordinate can be in the millions, and
     * a million divided by three thousand and handed to a float leaves nothing
     * of the fraction — the shadow would move in visible steps as the player
     * walks, which is the classic way this effect goes wrong far from spawn.
     * Everything the shader adds to it is measured from the camera and small.
     */
    private void writeCloudShadow() {
        if (aoCloudShadowUniform < 0) {
            return;
        }
        int sheet = cloudTexture;
        if (cloudShadows <= 0.0f || sheet == 0 || sunRamp() <= 0.0f) {
            GL20C.glUniform1f(aoCloudShadowUniform, 0.0f);
            return;
        }
        // The game's own arithmetic: a cloud cell is twelve blocks and the
        // sheet is two hundred and fifty six cells across, drifting along x.
        final double cell = 12.0;
        final double sheetCells = 256.0;
        double perBlock = 1.0 / (cell * sheetCells);
        double baseU = ((viewWorldX + cloudDrift) / cell) / sheetCells;
        double baseV = ((viewWorldZ / cell) + 0.33) / sheetCells;
        baseU -= Math.floor(baseU);
        baseV -= Math.floor(baseV);
        GL20C.glUniform4f(aoCloudUvUniform, (float) baseU, (float) baseV,
                (float) perBlock, (float) (cloudHeight - viewWorldY));
        GL20C.glUniform3f(aoSunWorldUniform,
                sunDirection[0], sunDirection[1], sunDirection[2]);
        modelViewMatrix.clear();
        GL11C.glGetFloatv(org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX, modelViewMatrix);
        GL20C.glUniform3f(aoCol0Uniform, modelViewMatrix.get(0),
                modelViewMatrix.get(1), modelViewMatrix.get(2));
        GL20C.glUniform3f(aoCol1Uniform, modelViewMatrix.get(4),
                modelViewMatrix.get(5), modelViewMatrix.get(6));
        GL20C.glUniform3f(aoCol2Uniform, modelViewMatrix.get(8),
                modelViewMatrix.get(9), modelViewMatrix.get(10));
        GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sheet);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL20C.glUniform1f(aoCloudShadowUniform, cloudShadows * sunRamp());
    }

    private boolean ensureAoTargets() {
        int wantWidth = Math.max(1, width / 2);
        int wantHeight = Math.max(1, height / 2);
        if (aoFbo != 0 && wantWidth == aoWidth && wantHeight == aoHeight) {
            return true;
        }
        destroyAoTargets();
        aoWidth = wantWidth;
        aoHeight = wantHeight;
        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        int prevTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        aoTexture = GL11C.glGenTextures();
        allocateBloomTexture(aoTexture, aoWidth, aoHeight);
        aoFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, aoFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, aoTexture, 0);
        boolean ok = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)
                == GL30C.GL_FRAMEBUFFER_COMPLETE;
        aoBlurTexture = GL11C.glGenTextures();
        allocateBloomTexture(aoBlurTexture, aoWidth, aoHeight);
        aoBlurFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, aoBlurFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, aoBlurTexture, 0);
        ok &= GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)
                == GL30C.GL_FRAMEBUFFER_COMPLETE;
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
        if (!ok) {
            LOGGER.error("Ambient occlusion targets incomplete; the effect is off for this session");
            destroyAoTargets();
            aoFailed = true;
            return false;
        }
        try {
            buildAoProgram();
            buildBlurProgram();
        } catch (RuntimeException e) {
            LOGGER.error("Ambient occlusion program failed to build; off for this session", e);
            destroyAoTargets();
            aoFailed = true;
            return false;
        }
        return true;
    }

    private void buildAoProgram() {
        if (aoProgram != 0) {
            return;
        }
        aoProgram = buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform vec2 uInvSize;\n"
                        // x, y: how wide the view is at unit distance; z, w: the
                        // near and far planes. Everything else follows.
                        + "uniform vec4 uProj;\n"
                        + "uniform float uRadius;\n"
                        + "uniform float uStrength;\n"
                        // The sun, in the space this pass works in, and zero
                        // when it is below the horizon. Length rather than a
                        // separate flag, so the shader has one thing to ask.
                        + "uniform vec3 uSun;\n"
                        + "uniform float uContact;\n"
                        + "uniform sampler2D uClouds;\n"
                        + "uniform float uCloudShadow;\n"
                        // xy: where the camera sits on the cloud sheet,
                        // already wrapped; z: how much of the sheet one block
                        // covers; w: how far the clouds are above the eye.
                        + "uniform vec4 uCloudUv;\n"
                        + "uniform vec3 uSunWorld;\n"
                        // The three columns of the view rotation, which turn
                        // a view-space direction back into world axes.
                        + "uniform vec3 uCol0;\n"
                        + "uniform vec3 uCol1;\n"
                        + "uniform vec3 uCol2;\n"
                        // Where the camera is in the world, reduced modulo a
                        // lattice so that single precision still tells one
                        // block from the next. Only the contact march reads it,
                        // and only to seed a hash.
                        + "uniform vec3 uCamWrap;\n"
                        // How far a contact shadow reaches, in blocks, split
                        // over eight steps — and how thick a thing has to be
                        // before it is treated as standing in the way rather
                        // than as the far side of the world seen past it.
                        + "const float CONTACT_STEP = 0.16;\n"
                        + "const float CONTACT_THICK = 0.55;\n"
                        + "float linearZ(float d) {\n"
                        + "    return 2.0 * uProj.z * uProj.w\n"
                        + "         / (uProj.w + uProj.z - (2.0 * d - 1.0) * (uProj.w - uProj.z));\n"
                        + "}\n"
                        // The depth is passed in rather than fetched, so a
                        // neighbour costs one read of the texture instead of
                        // two. That is what pays for sixteen of them.
                        + "vec3 viewPos(vec2 uv, float d) {\n"
                        + "    float z = linearZ(d);\n"
                        + "    vec2 ndc = uv * 2.0 - 1.0;\n"
                        + "    return vec3(ndc.x * uProj.x * z, ndc.y * uProj.y * z, -z);\n"
                        + "}\n"
                        // One texel of the cloud sheet is twelve blocks wide and the
                        // sheet is filtered by nearest texel, so the edge of a cloud
                        // is a cliff twelve blocks across. The point this shadow is
                        // read at travels hundreds of blocks sideways on its way up to
                        // the cloud layer, so the smallest turn of the head walks it
                        // over that cliff and the shade snaps on and off. Four taps a
                        // texel apart average that cliff into a slope — which is also
                        // what a real cloud edge looks like.
                        + "const float CLOUD_TEXEL = 0.00390625;\n"
                        + "float cloudAt(vec2 uv2) {\n"
                        + "    float h = CLOUD_TEXEL * 0.5;\n"
                        + "    return 0.25 * (texture2D(uClouds, fract(uv2 + vec2(h, h))).a\n"
                        + "                 + texture2D(uClouds, fract(uv2 + vec2(-h, h))).a\n"
                        + "                 + texture2D(uClouds, fract(uv2 + vec2(h, -h))).a\n"
                        + "                 + texture2D(uClouds, fract(uv2 + vec2(-h, -h))).a);\n"
                        + "}\n"
                        + "void main() {\n"
                        + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                        + "    float d = texture2D(uSource, uv).r;\n"
                        // Nothing was drawn here, so there is nothing to shade.
                        // Sky: nothing to occlude and no sun to lose. Red is
                        // full light, green is no shadow — the two channels
                        // mean opposite things, and writing ones into both is
                        // how the sky came out black the first time this pass
                        // stopped carrying one number in all four.
                        + "    if (d >= 0.9999) { gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);\n"
                        + "        return; }\n"
                        + "    vec3 p = viewPos(uv, d);\n"
                        // Two neighbours an axis, and the nearer of each pair
                        // wins. The hardware's own derivative would be cheaper
                        // and is what this used to do, but it is taken across a
                        // block of four pixels, and a block lying across the
                        // seam where a wall meets a ceiling takes its difference
                        // over both surfaces at once. What comes out is not a
                        // rough normal, it is a direction belonging to neither
                        // face — and with the wrong direction nearly every
                        // neighbour counts as standing in front of the surface
                        // instead of half of them, so the seam went black. One
                        // pixel wide, along every seam in the world, and no
                        // slider touched it because it was never a matter of
                        // how much.
                        + "    vec2 ex = vec2(uInvSize.x, 0.0);\n"
                        + "    vec2 ey = vec2(0.0, uInvSize.y);\n"
                        + "    float dl1 = texture2D(uSource, uv - ex).r;\n"
                        + "    float dl2 = texture2D(uSource, uv - 2.0 * ex).r;\n"
                        + "    float dr1 = texture2D(uSource, uv + ex).r;\n"
                        + "    float dr2 = texture2D(uSource, uv + 2.0 * ex).r;\n"
                        + "    float dd1 = texture2D(uSource, uv - ey).r;\n"
                        + "    float dd2 = texture2D(uSource, uv - 2.0 * ey).r;\n"
                        + "    float du1 = texture2D(uSource, uv + ey).r;\n"
                        + "    float du2 = texture2D(uSource, uv + 2.0 * ey).r;\n"
                        // The side that bends least wins, and it takes two steps
                        // to know which that is. One step only finds the nearer
                        // neighbour, which answers a different question: it tells
                        // a silhouette from flat ground, where the depth jumps.
                        // Where a wall meets a ceiling nothing jumps — the two
                        // surfaces touch, and only their slope changes — so the
                        // nearer neighbour was as likely to be the wrong face as
                        // the right one. A face carries straight on, so its two
                        // steps predict where the third would fall; a side that
                        // crosses the seam does not, and the difference between
                        // those two predictions is what picks the face.
                        //
                        // Written so both branches step the same way round the
                        // surface, or the cross product would face backwards
                        // wherever the straighter side happened to be behind.
                        + "    vec3 gx = abs(2.0 * dr1 - dr2 - d) < abs(2.0 * dl1 - dl2 - d)\n"
                        + "            ? viewPos(uv + ex, dr1) - p : p - viewPos(uv - ex, dl1);\n"
                        + "    vec3 gy = abs(2.0 * du1 - du2 - d) < abs(2.0 * dd1 - dd2 - d)\n"
                        + "            ? viewPos(uv + ey, du1) - p : p - viewPos(uv - ey, dd1);\n"
                        + "    vec3 n = normalize(cross(gx, gy));\n"
                        + "    if (dot(n, p) > 0.0) n = -n;\n"
                        // A radius in blocks becomes a radius on screen by
                        // dividing by distance, which is the whole of
                        // perspective.
                        + "    float scale = uRadius / (uProj.x * 2.0 * max(-p.z, 0.1));\n"
                        // Capped, because standing with your nose against a wall
                        // projects a radius of several blocks across more than
                        // the whole screen, and a neighbourhood spread that wide
                        // is not a neighbourhood — it is the rest of the picture.
                        + "    scale = min(scale, 0.15);\n"
                        // Where this point is in the world, in world axes and
                        // reduced against a lattice the camera is already
                        // reduced against. Both marches below hash it, and it is
                        // three dot products for the pair.
                        + "    vec3 rel = vec3(dot(uCol0, p), dot(uCol1, p), dot(uCol2, p));\n"
                        // Turned by a different angle at every point, so what
                        // sixteen samples cannot cover comes out as noise the
                        // blur removes rather than as rings nothing removes.
                        //
                        // At every *point*, and not at every pixel, which is
                        // what it used to say and do. A pattern keyed to
                        // gl_FragCoord is fixed to the screen: turn your head a
                        // fraction of a degree and every surface point lands on
                        // a different pixel, is handed an angle unrelated to the
                        // one it had, and comes back with a different amount of
                        // shade. The picture then crawls whenever the camera
                        // turns and is perfectly still when it does not — which
                        // is exactly what was reported, and reported as the
                        // shading being computed from where you look. The blur
                        // underneath cannot answer it: the blur averages across
                        // the screen, and this falls apart across frames.
                        //
                        // The contact shadow fifty lines below was cured of the
                        // same thing and left this one alone. Keyed to the world
                        // instead, neighbouring pixels still get unrelated
                        // angles — p differs between them — while one point in
                        // the world keeps its angle whatever the camera does.
                        //
                        // The usual fract(sin(dot(...))) was here before either
                        // and is exactly wrong at this size: its argument grows
                        // with the coordinate, and past six figures a 32-bit
                        // float no longer holds a sine's argument finely enough
                        // to answer differently for neighbours. The angles stop
                        // being unrelated and lay themselves out in faint bands
                        // wider than the grain the blur was written to remove.
                        // This one never takes a sine and never lets a number
                        // grow: fractions multiplied by fractions.
                        + "    vec3 h3 = fract((uCamWrap + rel) * vec3(0.1031, 0.1030, 0.0973));\n"
                        + "    h3 += dot(h3, h3.yzx + 33.33);\n"
                        + "    float a = fract((h3.x + h3.y) * h3.z) * 6.2831853;\n"
                        + "    float occlusion = 0.0;\n"
                        + "    for (int i = 0; i < 16; i++) {\n"
                        // The golden angle, so consecutive samples never line up
                        // however many of them there are, and a square root on
                        // the distance, so the sixteen cover the disc evenly
                        // instead of crowding its middle.
                        + "        float t = a + float(i) * 2.3999632;\n"
                        + "        float reach = sqrt((float(i) + 0.5) * 0.0625);\n"
                        // The share of the answer this sample speaks for, known
                        // before anything is read and the same every frame. It
                        // is what makes the total a fraction rather than a sum:
                        // these add up to exactly 8 for sixteen samples laid out
                        // this way, so dividing by 8 gives the part of the
                        // neighbourhood that is in the way — a number between
                        // zero and one whatever the geometry does, and one that
                        // does not change if the sample count ever does.
                        + "        float share = 1.0 - reach * reach;\n"
                        + "        vec2 suv = uv + vec2(cos(t), sin(t)) * scale * reach;\n"
                        + "        float sd = texture2D(uSource, suv).r;\n"
                        + "        if (sd >= 0.9999) continue;\n"
                        + "        vec3 diff = viewPos(suv, sd) - p;\n"
                        + "        float len = length(diff);\n"
                        + "        if (len < 0.0001) continue;\n"
                        // In front of the surface and near enough to matter. The
                        // bias keeps a flat wall from shading itself, which is
                        // what the depth buffer's own steps would otherwise do.
                        // The bias grows with distance, and that is what the
                        // banding on floors and ceilings was. Depth is stored in
                        // twenty-four bits spread unevenly over the view, so a
                        // reconstructed position carries a step, and the step is
                        // wider the further away it is. A surface seen edge-on —
                        // the floor you are standing on, the ceiling over your
                        // head — spans that whole range across a few pixels of
                        // screen, so the step lands as stripes running along it,
                        // while a wall you are facing has every pixel at one
                        // distance and shows nothing. A fixed bias cannot answer
                        // both: set for the near end it leaves the far end
                        // striped, set for the far end it erases the near.
                        + "        float bias = 0.02 + 0.0004 * (-p.z);\n"
                        + "        float front = max(0.0, dot(n, diff / len) - bias);\n"
                        // Fading to nothing at the edge of the radius rather
                        // than being cut off there. A sample that counts in full
                        // right up to the edge and then stops is a step in the
                        // shading, and a handful of such steps is what a corner
                        // shaded in bands rather than softly is made of. This
                        // rejects a distant occluder; it is not the sample's
                        // share, which is fixed and settled above — the two were
                        // one term before, and where a seam brought a neighbour
                        // closer than its place on the disc implied, the share
                        // it was allowed grew with it.
                        + "        float range = clamp(1.0 - (len * len) / (uRadius * uRadius), 0.0, 1.0);\n"
                        + "        occlusion += front * share * range;\n"
                        + "    }\n"
                        // Divided by the shares, so what multiplies the strength
                        // is how much of the neighbourhood is in the way.
                        //
                        // Half of what it was, because this is not the only
                        // occlusion in the picture. The game bakes its own into
                        // the corners of every block while the chunk is built,
                        // and this lands on top of that rather than instead of
                        // it — so a seam was being darkened twice and came out
                        // blacker than anything in the room. The full length of
                        // the slider is now the useful range, which is the point
                        // of a slider; the setting that looked right at half of
                        // the old scale is the whole of this one.
                        + "    float ao = 1.0 - uStrength * (occlusion * 0.125) * 0.6;\n"
                        // A short shadow along the ground towards the sun, from
                        // the same depth image and the same reconstructed
                        // position. What this adds over the sixteen samples
                        // above is a direction: occlusion asks how enclosed a
                        // point is and answers the same whatever the hour,
                        // while this asks whether one particular thing stands
                        // between the point and the sun, so it moves as the sun
                        // does. It is the cheap half of a shadow — it can only
                        // find an occluder that is itself on the screen, and
                        // only within a step or so of the surface — but that is
                        // exactly the half that is missing here, because a
                        // chest, a mob and another mod's machine are all in
                        // this depth image and in none of the traced ones.
                        // Started off the surface along its own normal, or the
                        // first step lands back on the surface it came from and
                        // every lit face shadows itself. Shared by both marches
                        // below, which start from the same place for the same
                        // reason.
                        + "    vec3 rayStart = p + n * (0.05 + 0.002 * (-p.z));\n"
                        + "    float contact = 0.0;\n"
                        + "    if (uContact > 0.0 && dot(uSun, uSun) > 0.25 && dot(n, uSun) > 0.0) {\n"
                        + "        vec3 rp = rayStart;\n"
                        // Anchored to the world, and not to the pixel like the
                        // occlusion's rotation above it.
                        //
                        // They look like the same kind of noise and they are
                        // not, because of what is done with the answer. The
                        // occlusion averages sixteen samples and is blurred
                        // afterwards, so a pattern fixed to the screen washes
                        // out and staying still is what makes it wash out
                        // evenly. This march takes a maximum over a hard
                        // threshold — a step either crosses the surface or it
                        // does not — and nothing downstream averages that. With
                        // the jitter fixed to the pixel, turning the head a
                        // fraction of a degree moves every surface point onto a
                        // different pixel, hands it a different offset, and
                        // moves where the threshold falls: reported twice as
                        // shadows flickering indoors on the smallest movement,
                        // walking or looking around.
                        //
                        // So the offset is read from where the point is in the
                        // world instead. The camera's own position arrives
                        // already reduced modulo a lattice, the same trick the
                        // waves and the sway use and for the same reason: a
                        // hash needs a number it can tell apart from its
                        // neighbour, and a single-precision world coordinate at
                        // Minecraft's range cannot give it one.
                        + "        vec3 wh = fract((uCamWrap + rel) * vec3(0.4127, 0.4013, 0.3971));\n"
                        + "        wh += dot(wh, wh.yzx + 33.33);\n"
                        + "        float jitter = fract((wh.x + wh.y) * wh.z);\n"
                        + "        for (int i = 1; i <= 8; i++) {\n"
                        + "            vec3 s = rp + uSun * (CONTACT_STEP * (float(i) + jitter));\n"
                        // Behind the eye, where there is no pixel to ask.
                        + "            if (s.z > -uProj.z) break;\n"
                        + "            vec2 sn = vec2(s.x / (uProj.x * -s.z), s.y / (uProj.y * -s.z));\n"
                        + "            vec2 suv = sn * 0.5 + 0.5;\n"
                        // Off the edge of the screen, which is the honest limit
                        // of the technique rather than a shadow ending.
                        + "            if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) break;\n"
                        + "            float sd = texture2D(uSource, suv).r;\n"
                        + "            if (sd >= 0.9999) continue;\n"
                        + "            float depthHere = linearZ(sd);\n"
                        + "            float rayHere = -s.z;\n"
                        + "            float gap = rayHere - depthHere;\n"
                        // In front of the ray, and thin enough to be a thing
                        // standing there rather than the far side of the world.
                        // Without the thickness test every pixel with any
                        // geometry nearer to the camera anywhere along the line
                        // counts, and a hillside puts the whole valley in shade.
                        + "            if (gap > 0.03 && gap < CONTACT_THICK) {\n"
                        // Strongest against the thing casting it and gone by
                        // the end of the reach, which is what makes it read as
                        // contact rather than as a second helping of occlusion.
                        + "                contact = max(contact, 1.0 - float(i) / 8.0);\n"
                        + "            }\n"
                        + "        }\n"
                        + "    }\n"
                        // The shadow of the game's own clouds, cast onto whatever
                        // is in the picture. Nothing about it is invented: the
                        // sheet is the one the game draws its clouds from, the
                        // height is the one the world reports, and the drift is
                        // the game's own counter — so the dark patch lands under
                        // the cloud that cast it. This is also the cheapest way
                        // there is to make a sky read as having depth, and it
                        // costs one texture read on a pass that already exists.
                        + "    float cloud = 0.0;\n"
                        + "    if (uCloudShadow > 0.0 && uSunWorld.y > 0.05) {\n"
                        // The shared rel above: view space back to the axes the
                        // world is measured in. A direction only, so the three
                        // dot products are the whole of it and the camera's own
                        // position never enters the arithmetic — which is what
                        // keeps this exact a million blocks from the origin.
                        + "        float climb = uCloudUv.w - rel.y;\n"
                        // Only what is under the clouds is in their shade. Above
                        // them the sun is unobstructed, and marching backwards
                        // would put a shadow on the top of a mountain that rises
                        // through the cloud layer.
                        + "        if (climb > 0.0) {\n"
                        + "            vec2 hit = rel.xz + uSunWorld.xz * (climb / uSunWorld.y);\n"
                        + "            cloud = cloudAt(uCloudUv.xy + hit * uCloudUv.z);\n"
                        // Nothing under a roof is in a cloud's shade, and the
                        // sheet alone cannot know that: it is read at a point
                        // hundreds of blocks away in the sky and says nothing
                        // about what stands between. Without this the shade of
                        // passing clouds swept across the floor of a closed
                        // house. Six steps of two blocks towards the sun is
                        // enough to find a ceiling and cheap enough to spend:
                        // it is the same march the contact shadows do, walked
                        // further and asked a coarser question.
                        + "            vec3 up = rayStart;\n"
                        // Twelve steps of a block, not six of two.
                        //
                        // This walk asks whether there is a roof between the
                        // point and the sun, and a step of two blocks steps
                        // clean over a ceiling one block thick: the sample
                        // before it is under the roof and the sample after is
                        // above it, and nothing in between is ever asked. So
                        // the shade of a passing cloud swept across the floor
                        // of a closed room — reported three rounds running,
                        // each time as "the shadows flicker indoors", and each
                        // time looking like noise rather than like a step that
                        // is simply too long. The reach is the same twelve
                        // blocks; only the spacing changes, and it changes to
                        // the thickness of the thinnest thing worth finding.
                        + "            for (int k = 1; k <= 12; k++) {\n"
                        + "                vec3 sk = up + uSun * float(k);\n"
                        + "                if (sk.z > -uProj.z) break;\n"
                        + "                vec2 kn = vec2(sk.x / (uProj.x * -sk.z), sk.y / (uProj.y * -sk.z));\n"
                        + "                vec2 kuv = kn * 0.5 + 0.5;\n"
                        + "                if (kuv.x < 0.0 || kuv.x > 1.0 || kuv.y < 0.0 || kuv.y > 1.0) break;\n"
                        + "                float kd = texture2D(uSource, kuv).r;\n"
                        + "                if (kd >= 0.9999) continue;\n"
                        + "                float gap2 = -sk.z - linearZ(kd);\n"
                        + "                if (gap2 > 0.05 && gap2 < 6.0) { cloud = 0.0; break; }\n"
                        + "            }\n"
                        // Faded out with the sun near the horizon, where the
                        // journey to the cloud layer is long enough that the
                        // shadow lands a hundred blocks from anything overhead
                        // and reads as a stain rather than as weather.
                        + "            cloud *= clamp(uSunWorld.y * 3.0, 0.0, 1.0);\n"
                        + "        }\n"
                        + "    }\n"
                        // Faded towards the edge of the screen rather than stopped
                        // at it. A ray from a pixel near the border leaves the
                        // picture within a step or two and finds nothing, so the
                        // shadow simply ended along a straight line down the side
                        // of the view — and a straight line is the one thing the
                        // eye never misses. The technique still cannot see past
                        // the border; this only stops it announcing where the
                        // border is. Wider fields of view show more of it, which
                        // is why it was worse the wider the view got.
                        + "    vec2 toEdge = min(uv, vec2(1.0) - uv);\n"
                        + "    float edge = clamp(min(toEdge.x, toEdge.y) / 0.10, 0.0, 1.0);\n"
                        // The two sun-driven shadows are one shadow, so they
                        // are taken together rather than one after the other.
                        //
                        // Multiplying them compounded: a contact shadow lying
                        // under a cloud came out darker than either could make
                        // it, and darker again wherever a traced shadow had
                        // already lowered the same surface. They are not two
                        // occluders in front of two lights — they are two ways
                        // of finding out about the one sun, so the answer is
                        // whichever of them found more of it, not the product.
                        //
                        // The cloud is worth half of what the slider says,
                        // because the sheet is a mask of ones and zeros: the
                        // value read is the share of the sky covered, not the
                        // share of light removed, and a cloud does not take
                        // all of the light under it.
                        + "    float sunBlocked = max(uContact * contact * 0.75 * edge,\n"
                        + "                           uCloudShadow * cloud * 0.5);\n"
                        // Kept apart rather than multiplied together here.
                        //
                        // They are two different questions with two different
                        // answers downstream: how enclosed a point is holds
                        // whatever the light is doing, while how much of the
                        // sun is blocked has to be weighed by how much sun the
                        // surface was getting in the first place — and that is
                        // known only where the terrain was shaded. Red carries
                        // the occlusion, green the sun.
                        + "    gl_FragColor = vec4(clamp(ao, 0.0, 1.0),\n"
                        + "                        clamp(sunBlocked, 0.0, 1.0), 0.0, 1.0);\n"
                        + "}\n");
        aoInvSize = GL20C.glGetUniformLocation(aoProgram, "uInvSize");
        aoProjUniform = GL20C.glGetUniformLocation(aoProgram, "uProj");
        aoRadiusUniform = GL20C.glGetUniformLocation(aoProgram, "uRadius");
        aoStrengthUniform = GL20C.glGetUniformLocation(aoProgram, "uStrength");
        aoSunUniform = GL20C.glGetUniformLocation(aoProgram, "uSun");
        aoContactUniform = GL20C.glGetUniformLocation(aoProgram, "uContact");
        aoCloudShadowUniform = GL20C.glGetUniformLocation(aoProgram, "uCloudShadow");
        aoCloudUvUniform = GL20C.glGetUniformLocation(aoProgram, "uCloudUv");
        aoSunWorldUniform = GL20C.glGetUniformLocation(aoProgram, "uSunWorld");
        aoCamWrapUniform = GL20C.glGetUniformLocation(aoProgram, "uCamWrap");
        aoCol0Uniform = GL20C.glGetUniformLocation(aoProgram, "uCol0");
        aoCol1Uniform = GL20C.glGetUniformLocation(aoProgram, "uCol1");
        aoCol2Uniform = GL20C.glGetUniformLocation(aoProgram, "uCol2");
        int prevAo = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        GL20C.glUseProgram(aoProgram);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(aoProgram, "uClouds"), 2);
        GL20C.glUseProgram(prevAo);
    }

    private void destroyAoTargets() {
        if (aoFbo != 0) {
            GL30C.glDeleteFramebuffers(aoFbo);
            aoFbo = 0;
        }
        if (aoBlurFbo != 0) {
            GL30C.glDeleteFramebuffers(aoBlurFbo);
            aoBlurFbo = 0;
        }
        if (aoTexture != 0) {
            GL11C.glDeleteTextures(aoTexture);
            aoTexture = 0;
        }
        if (aoBlurTexture != 0) {
            GL11C.glDeleteTextures(aoBlurTexture);
            aoBlurTexture = 0;
        }
        aoWidth = 0;
        aoHeight = 0;
    }

    private void destroyMotionProgram() {
        if (motionProgram != 0) {
            GL20C.glDeleteProgram(motionProgram);
            motionProgram = 0;
        }
    }

    /**
     * Where each pixel of this frame was standing in the last one, written into
     * a texture as a step across the screen.
     *
     * The whole of it is one matrix. A pixel's clip position is known — its
     * place on screen and its depth — and last frame's matrix says where that
     * point would have landed then, once the camera's own step between the two
     * frames has been added back to it, because everything here is measured
     * from a camera that has itself moved. Multiplying the three together on
     * this side leaves the shader with a single transform and no inverse to
     * take per pixel.
     *
     * The first frame after the renderer starts, and the first after a resize,
     * have nothing behind them and are written as standing still. That is the
     * right answer rather than a placeholder: nothing may be carried over from
     * a frame that does not exist.
     */
    private boolean motionPass() {
        if (!ensureMotionTargets()) {
            return false;
        }
        if (!hasPreviousFrame) {
            identity(reprojectMatrix);
        } else {
            // The camera's step, taken in double where it is exact and handed
            // over small. World coordinates in this game reach tens of millions
            // and a float cannot separate one block from the next up there, but
            // the distance a camera covers in a frame is a fraction of a block.
            float dx = (float) (viewWorldX - previousViewX);
            float dy = (float) (viewWorldY - previousViewY);
            float dz = (float) (viewWorldZ - previousViewZ);
            float[] inverse = new float[16];
            if (!invert(currentMvp, inverse)) {
                identity(reprojectMatrix);
            } else {
                // The camera's step, folded straight into the inverse rather
                // than applied as a matrix of its own. Translating a
                // homogeneous point moves it by the step times its own w, which
                // is the same point translated and left homogeneous — so it is
                // three rows gaining a multiple of the fourth, and no divide is
                // forced into the middle of the product.
                float[] shifted = new float[16];
                for (int col = 0; col < 4; col++) {
                    float w = inverse[col * 4 + 3];
                    shifted[col * 4] = inverse[col * 4] + dx * w;
                    shifted[col * 4 + 1] = inverse[col * 4 + 1] + dy * w;
                    shifted[col * 4 + 2] = inverse[col * 4 + 2] + dz * w;
                    shifted[col * 4 + 3] = w;
                }
                multiply(previousMvp, shifted, reprojectMatrix);
            }
        }

        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_VIEWPORT_BIT);
        try {
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(false);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, motionFbo);
            GL11C.glViewport(0, 0, motionWidth, motionHeight);
            GL20C.glUseProgram(motionProgram);
            GL20C.glUniform2f(motionInvSizeUniform, 1.0f / motionWidth, 1.0f / motionHeight);
            reprojectBuffer.clear();
            reprojectBuffer.put(reprojectMatrix).flip();
            GL20C.glUniformMatrix4fv(motionReprojectUniform, false, reprojectBuffer);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glDepthTexture);
            fullscreenQuad();
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
            org.lwjgl.opengl.GL11.glPopAttrib();
        }
        return true;
    }

    /**
     * Mixes this frame's terrain colour into the ones before it.
     *
     * Runs after the motion pass, whose answer it needs, and before the
     * composite, which is what draws the result. Full resolution deliberately:
     * the grain being removed is one pixel wide, and a half-size pass would take
     * the detail with it.
     *
     * @return false when the frame is to be composited as it came out of Vulkan
     */
    /**
     * Whether anything is going to read the motion this frame.
     *
     * It has exactly two readers: the pass that averages successive frames,
     * which is switched on only while rays are being traced, and the
     * diagnostic that paints the motion instead of the world. Neither was
     * asked before the motion was worked out — the only condition was that the
     * pass had not already failed — so on every ordinary session, which is
     * every session with tracing off, a half-resolution pass over the whole
     * screen and a matrix inverse were run each frame for nobody.
     *
     * It never showed up as a cost worth chasing because it was measured
     * inside one figure covering the whole composite, and at sixty frames a
     * second the whole composite is two per cent of a frame. At six hundred it
     * is a fifth of one.
     */
    private boolean motionWanted() {
        return (accumStrength > 0.0f && tracingWanted()) || showMotion || motionOverWorld;
    }

    private boolean accumPass(boolean motionReady) {
        if (!motionReady || accumStrength <= 0.0f || accumFailed || !tracingWanted()) {
            // Nothing is noisy, or nothing can be reprojected. Either way the
            // history stops being about this world, so it is not carried over.
            accumHasHistory = false;
            return false;
        }
        if (!ensureAccumTargets()) {
            return false;
        }
        int target = accumIndex ^ 1;
        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_VIEWPORT_BIT);
        try {
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(false);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, accumFbo[target]);
            GL11C.glViewport(0, 0, accumWidth, accumHeight);
            GL20C.glUseProgram(accumProgram);
            GL20C.glUniform2f(accumInvSizeUniform, 1.0f / accumWidth, 1.0f / accumHeight);
            // The first frame after this target was made, after a resize, or
            // after the effect was switched on has nothing behind it, and the
            // texture it would read holds whatever was last drawn there.
            GL20C.glUniform1f(accumBlendUniform, accumHasHistory ? accumStrength : 0.0f);
            GL20C.glUniform1f(accumShowUniform, showAccumulation ? 1.0f : 0.0f);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glColorTexture);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, motionTexture);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, accumTexture[accumIndex]);
            fullscreenQuad();
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
            org.lwjgl.opengl.GL11.glPopAttrib();
        }
        accumIndex = target;
        accumHasHistory = true;
        return true;
    }

    private boolean ensureAccumTargets() {
        if (accumFbo[0] != 0 && accumWidth == width && accumHeight == height) {
            return true;
        }
        destroyAccumTargets();
        accumWidth = width;
        accumHeight = height;
        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        int prevTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        boolean ok = true;
        for (int i = 0; i < 2; i++) {
            accumTexture[i] = GL11C.glGenTextures();
            allocateBloomTexture(accumTexture[i], accumWidth, accumHeight);
            accumFbo[i] = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, accumFbo[i]);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, accumTexture[i], 0);
            ok &= GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)
                    == GL30C.GL_FRAMEBUFFER_COMPLETE;
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
        if (!ok) {
            LOGGER.error("Frame accumulation targets incomplete; off for this session");
            destroyAccumTargets();
            accumFailed = true;
            return false;
        }
        try {
            buildAccumProgram();
        } catch (RuntimeException e) {
            LOGGER.error("Frame accumulation program failed to build; off for this session", e);
            destroyAccumTargets();
            accumFailed = true;
            return false;
        }
        accumHasHistory = false;
        return true;
    }

    private void buildAccumProgram() {
        if (accumProgram != 0) {
            return;
        }
        accumProgram = buildQuadProgram(
                "uniform sampler2D uColor;\n"
                        + "uniform sampler2D uMotion;\n"
                        + "uniform sampler2D uHistory;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "uniform float uBlend;\n"
                        + "uniform float uShow;\n"
                        + "void main() {\n"
                        + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                        + "    vec4 here = texture2D(uColor, uv);\n"
                        // The nine pixels around this one, as a range. What a
                        // pixel is allowed to remember is bounded by what its
                        // own surroundings look like now — so a wall that has
                        // just moved in front of something, a light that has
                        // just gone out and a block that has just been broken
                        // all correct themselves in a single frame, without any
                        // of them having to be detected.
                        + "    vec3 lo = here.rgb;\n"
                        + "    vec3 hi = here.rgb;\n"
                        + "    for (int y = -1; y <= 1; y++) {\n"
                        + "        for (int x = -1; x <= 1; x++) {\n"
                        + "            vec3 n = texture2D(uColor,\n"
                        + "                     uv + vec2(float(x), float(y)) * uInvSize).rgb;\n"
                        + "            lo = min(lo, n);\n"
                        + "            hi = max(hi, n);\n"
                        + "        }\n"
                        + "    }\n"
                        + "    vec4 m = texture2D(uMotion, uv);\n"
                        + "    vec2 prevUv = uv + m.rg;\n"
                        + "    float weight = uBlend;\n"
                        // Sky, or a pixel the reprojection could not answer for.
                        + "    if (m.a < 0.5) { weight = 0.0; }\n"
                        // Off the edge of last frame's picture. There is no
                        // history there to take, and clamping to the border
                        // would smear the edge of the screen inwards.
                        + "    if (prevUv.x < 0.0 || prevUv.x > 1.0\n"
                        + "        || prevUv.y < 0.0 || prevUv.y > 1.0) { weight = 0.0; }\n"
                        // Falling away with speed across the screen. Resampling
                        // history through a bilinear filter every frame is
                        // history slowly turning into blur, and the grain this
                        // removes is least visible exactly when the view is
                        // moving fastest.
                        + "    weight *= 1.0 - clamp(length(m.rg) * 60.0, 0.0, 1.0);\n"
                        + "    vec3 history = clamp(texture2D(uHistory, prevUv).rgb, lo, hi);\n"
                        + "    vec3 mixed = mix(here.rgb, history, weight);\n"
                        // The alpha is not ours to average: the terrain shader
                        // writes which pixels are lights into it, and the bloom
                        // that reads it wants this frame's answer.
                        + "    gl_FragColor = vec4(mix(mixed, vec3(weight), uShow), here.a);\n"
                        + "}\n");
        accumInvSizeUniform = GL20C.glGetUniformLocation(accumProgram, "uInvSize");
        accumBlendUniform = GL20C.glGetUniformLocation(accumProgram, "uBlend");
        accumShowUniform = GL20C.glGetUniformLocation(accumProgram, "uShow");
        int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        GL20C.glUseProgram(accumProgram);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(accumProgram, "uColor"), 0);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(accumProgram, "uMotion"), 1);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(accumProgram, "uHistory"), 2);
        GL20C.glUseProgram(prev);
    }

    private void destroyAccumTargets() {
        for (int i = 0; i < 2; i++) {
            if (accumFbo[i] != 0) {
                GL30C.glDeleteFramebuffers(accumFbo[i]);
                accumFbo[i] = 0;
            }
            if (accumTexture[i] != 0) {
                GL11C.glDeleteTextures(accumTexture[i]);
                accumTexture[i] = 0;
            }
        }
        accumWidth = 0;
        accumHeight = 0;
        accumIndex = 0;
        accumHasHistory = false;
    }

    /** Keeps this frame's matrix and camera for the next one to ask about. */
    private void rememberFrame() {
        System.arraycopy(currentMvp, 0, previousMvp, 0, 16);
        previousViewX = viewWorldX;
        previousViewY = viewWorldY;
        previousViewZ = viewWorldZ;
        hasPreviousFrame = true;
    }

    private boolean ensureMotionTargets() {
        int wantWidth = Math.max(1, width / 2);
        int wantHeight = Math.max(1, height / 2);
        if (motionFbo != 0 && motionWidth == wantWidth && motionHeight == wantHeight) {
            return true;
        }
        destroyMotionTargets();
        motionWidth = wantWidth;
        motionHeight = wantHeight;
        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        int prevTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        motionTexture = GL11C.glGenTextures();
        allocateBloomTexture(motionTexture, motionWidth, motionHeight);
        motionFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, motionFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, motionTexture, 0);
        boolean ok = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)
                == GL30C.GL_FRAMEBUFFER_COMPLETE;
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
        if (!ok) {
            LOGGER.error("Motion vector target incomplete; off for this session");
            destroyMotionTargets();
            motionFailed = true;
            return false;
        }
        try {
            buildMotionProgram();
        } catch (RuntimeException e) {
            LOGGER.error("Motion vector program failed to build; off for this session", e);
            destroyMotionTargets();
            motionFailed = true;
            return false;
        }
        // The frame this target was made for has nothing before it.
        hasPreviousFrame = false;
        return true;
    }

    private void buildMotionProgram() {
        if (motionProgram != 0) {
            return;
        }
        motionProgram = buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "uniform mat4 uReproject;\n"
                        + "void main() {\n"
                        + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                        + "    float d = texture2D(uSource, uv).r;\n"
                        // Sky. Nothing was drawn, so there is nothing that was
                        // anywhere last frame either.
                        + "    if (d >= 0.9999) { gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0); return; }\n"
                        // The depth written here is already the [0,1] the
                        // matrix was built to produce, so it goes in as it is
                        // rather than being stretched to [-1,1] first.
                        + "    vec4 clipNow = vec4(uv * 2.0 - 1.0, d, 1.0);\n"
                        + "    vec4 before = uReproject * clipNow;\n"
                        + "    if (abs(before.w) < 1e-6) { gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0); return; }\n"
                        + "    vec2 prevUv = (before.xy / before.w) * 0.5 + 0.5;\n"
                        + "    gl_FragColor = vec4(prevUv - uv, 0.0, 1.0);\n"
                        + "}\n");
        motionInvSizeUniform = GL20C.glGetUniformLocation(motionProgram, "uInvSize");
        motionReprojectUniform = GL20C.glGetUniformLocation(motionProgram, "uReproject");
    }

    private void destroyMotionTargets() {
        if (motionFbo != 0) {
            GL30C.glDeleteFramebuffers(motionFbo);
            motionFbo = 0;
        }
        if (motionTexture != 0) {
            GL11C.glDeleteTextures(motionTexture);
            motionTexture = 0;
        }
        motionWidth = 0;
        motionHeight = 0;
        hasPreviousFrame = false;
    }

    /** Column-major, as everything that reaches OpenGL or Vulkan is. */
    private static void identity(float[] out) {
        for (int i = 0; i < 16; i++) {
            out[i] = (i % 5 == 0) ? 1.0f : 0.0f;
        }
    }

    /** out = a * b, both column-major. */
    private static void multiply(float[] a, float[] b, float[] out) {
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
     * The general inverse, by cofactors. A projection times a view is not a
     * rotation and a translation any more — the perspective divide is in there
     * — so none of the shortcuts for rigid transforms apply.
     */
    private static boolean invert(float[] m, float[] out) {
        float[] inv = new float[16];
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15]
                + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15]
                - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15]
                + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14]
                - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15]
                - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15]
                + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15]
                - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14]
                + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15]
                + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15]
                - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15]
                + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14]
                - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11]
                - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11]
                + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11]
                - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10]
                + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
        float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        if (det == 0.0f || Float.isNaN(det) || Float.isInfinite(det)) {
            return false;
        }
        float scale = 1.0f / det;
        for (int i = 0; i < 16; i++) {
            out[i] = inv[i] * scale;
        }
        return true;
    }

    private void bloomPrepare() {
        if (!ensureBloomTargets()) {
            return;
        }
        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_VIEWPORT_BIT);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glDisable(GL11C.GL_BLEND);

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomMaskFbo);
        GL11C.glViewport(0, 0, Math.max(1, width / 2), Math.max(1, height / 2));
        GL20C.glUseProgram(bloomMaskProgram);
        GL20C.glUniform2f(bloomMaskInvSize, 2.0f / width, 2.0f / height);
        // The reference has to be what the frame actually received, occlusion
        // and all, or the comparison that decides whether a light is covered
        // would fail everywhere and the glow would vanish.
        GL20C.glUniform1f(bloomMaskAoUniform, aoStrength > 0.0f && !aoFailed ? 1.0f : 0.0f);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, aoTexture);
        // The same picture the composite drew, or the comparison that decides
        // whether a light is covered fails everywhere and the glow disappears.
        // That is the occlusion above and the frame averaging here, and it is
        // the second time this exact trap has been walked into.
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,
                accumApplied ? accumTexture[accumIndex] : glColorTexture);
        fullscreenQuad();

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
        org.lwjgl.opengl.GL11.glPopAttrib();
        bloomReady = true;
    }

    /**
     * Adds the glow to the frame, once the game has drawn everything into it.
     *
     * Called from the world render, after terrain, entities, particles, weather
     * and water and before the hand — which is the one moment the frame holds
     * the whole scene. Doing it here rather than in the composite is what puts
     * a mob standing in front of lava *in* the glow instead of on top of it,
     * and what lets a torch throw light onto the sky behind it, which it could
     * not before: the sky is not drawn until long after the composite.
     *
     * Nothing owned by Vulkan is read here. The blurred glow and the mask are
     * both ordinary OpenGL textures of ours, filled while the colour target was
     * still ours to read; reading that target at this point would race the next
     * frame, because the semaphore handing it back has already been signalled.
     */
    /**
     * The tone of the whole finished picture, applied where the whole picture
     * exists.
     *
     * <h2>Why here and nowhere else</h2>
     *
     * What separates a shader pack's frame from vanilla's, before any single
     * effect is named, is the tone: contrast lifted, lights warmed, shadows
     * pressed down. A curve like that has to reach everything or it reads as a
     * fault — and this renderer's own composite is stitched into the frame
     * before the game draws its creatures, particles and weather, so anything
     * applied there would have coloured the blocks and left the cows alone.
     *
     * This hook is the other one. It fires on the profiler's "hand" marker,
     * after the world is finished in full and before the hand and the interface
     * — so it sees terrain, creatures, particles, weather and water together,
     * and never touches the inventory or the menus.
     *
     * <h2>What it cannot do</h2>
     *
     * The game's frame is eight bits a channel, so there is no headroom above
     * white: this is colour grading, not the film curve with burning highlights
     * a pack gets from a floating-point buffer. Said plainly rather than
     * pretended away — the curve below deliberately never pushes anything up
     * into clipping, because the only thing it could spend there is detail that
     * is already in the frame.
     */
    /**
     * Corners of the whole picture, not only of the blocks.
     *
     * Everything this renderer draws is in a depth image of its own, and that
     * image holds terrain and nothing else — so the occlusion built from it
     * stops at the edge of what this mod owns. A chest casts nothing into the
     * floor it stands on; nor does a mob, nor a modded block drawn by its own
     * renderer. Standing next to a chest in the sun is where anybody notices.
     *
     * By this point the game has finished the world and its depth buffer holds
     * all of them. Nothing here takes drawing away from anyone: the picture is
     * already made, and this reads its depth and darkens where the light could
     * not have reached. A mod cannot break on it, because a mod is not asked to
     * do anything.
     *
     * The occlusion itself is the same pass and the same shader the terrain
     * already used, handed a different depth texture. That is the whole of the
     * change — the arithmetic that turns a depth into a position and a position
     * into a corner does not care whose geometry made it.
     */
    /**
     * The pass over the finished picture — occlusion, and light shafts.
     *
     * Two effects rather than one because they want the same two things and
     * neither is cheap to get: the depth of the whole scene, which has to be
     * blitted out of a renderbuffer to be sampled at all, and a copy of the
     * colour, because a texture cannot be read and written at once. Splitting
     * them into two hooks would pay for both twice for nothing.
     */
    void applySceneOcclusion(int sceneTexture) {
        boolean wantOcclusion = sceneOcclusion && !aoFailed && aoWanted();
        boolean wantRays = godRaysWanted();
        if ((!wantOcclusion && !wantRays) || sceneTexture == 0
                || width <= 0 || height <= 0) {
            return;
        }
        int prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        int prevTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int prevActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        // What this pass is handed, recorded once and before anything below
        // overrides it. There are several ways a fragment can be computed
        // correctly and never reach the frame — the colour write mask shut,
        // the stencil test failing, the scissor holding an empty rectangle —
        // and not one of them raises a GL error or looks any different from
        // arithmetic that came out zero. A session was spent proving this
        // pass could not produce black, which was true and did not matter,
        // because the question was never what it computed.
        if (sceneStateAsFound == null) {
            try (MemoryStack stack = stackPush()) {
                java.nio.ByteBuffer mask = stack.malloc(4);
                GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, mask);
                sceneStateAsFound = "colour write "
                        + (mask.get(0) != 0 ? 'r' : '-') + (mask.get(1) != 0 ? 'g' : '-')
                        + (mask.get(2) != 0 ? 'b' : '-') + (mask.get(3) != 0 ? 'a' : '-')
                        + ", stencil test " + (GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST) ? "ON" : "off")
                        + ", scissor " + (GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST) ? "ON" : "off")
                        + ", depth test " + (GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST) ? "on" : "off")
                        + ", frame " + prevFbo;
            }
        }
        // The same pixel the grading pass reads, one pass earlier. This is the
        // first thing in the chain to touch the finished frame, so a centre
        // that is already nothing here says the world never arrived, and a
        // centre that holds a colour here and nothing later says one of the
        // passes between the two threw it away. Nothing else separates those,
        // and they are repaired in different files.
        if (sceneEntryProbe == null && ++sceneProbeFrames > 120) {
            sceneEntryProbe = probeCentre(prevFbo);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
            LOGGER.info("Frame handed to the scene passes, centre: {}", sceneEntryProbe);
        }
        try {
            if (!ensureSceneOcclusionTargets()) {
                return;
            }
            org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT
                    | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
                    | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                    | org.lwjgl.opengl.GL11.GL_TEXTURE_BIT
                    | org.lwjgl.opengl.GL11.GL_CURRENT_BIT
                    | org.lwjgl.opengl.GL11.GL_POLYGON_BIT
                    | org.lwjgl.opengl.GL11.GL_VIEWPORT_BIT);
            try {
                GL11C.glDisable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
                GL11C.glDisable(GL11C.GL_CULL_FACE);
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                GL11C.glDisable(GL11C.GL_DEPTH_TEST);
                GL11C.glDisable(GL11C.GL_BLEND);
                GL11C.glDepthMask(false);
                // The two that were left to whoever drew last. Both are saved
                // by the push above, so this costs a restore that was already
                // being paid, and both can swallow a full-screen quad whole
                // while every other thing this pass checks says it is fine.
                GL11C.glDisable(GL11C.GL_STENCIL_TEST);
                GL11C.glColorMask(true, true, true, true);

                // The game's depth into a texture, because a renderbuffer
                // cannot be sampled and the game's is one. A blit does not care
                // which of the two kinds either side is.
                GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevFbo);
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, sceneDepthFbo);
                GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                        GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
                // Asked, not assumed. Every effect below reads this depth, and
                // a refused blit leaves it at zero rather than leaving it alone
                // — so the failure is not "no shading" but "everything in
                // shadow", which is the one wrong answer that looks like the
                // renderer itself is broken. Once is enough: the formats do not
                // change during a session, and a message per frame is not a
                // message.
                if (!sceneDepthBlitChecked) {
                    sceneDepthBlitChecked = true;
                    int error = GL11C.glGetError();
                    if (error != GL11C.GL_NO_ERROR) {
                        sceneDepthUsable = false;
                        LOGGER.warn("The frame's depth would not copy into a texture (GL error"
                                + " 0x{}), so scene occlusion, contact shadows, cloud shadows and"
                                + " light shafts are off for this session — they all read it,"
                                + " and reading it empty puts the whole world in shadow",
                                Integer.toHexString(error));
                    }
                }
                if (!sceneDepthUsable) {
                    GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
                    return;
                }
                // And the colour, because the pass below reads what it writes.
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, sceneCopyFbo);
                GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                        GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
                // Asked as well, and for the harder reason: a refused colour
                // copy leaves this texture black, and the grading pass writes
                // what it read back over the whole frame. That is a world that
                // has gone black with the hand still on top of it — the hand
                // being drawn after all of this — which reads as the renderer
                // having failed rather than as one copy having been refused.
                if (!sceneColourBlitChecked) {
                    sceneColourBlitChecked = true;
                    int error = GL11C.glGetError();
                    if (error != GL11C.GL_NO_ERROR) {
                        sceneColourUsable = false;
                        LOGGER.warn("The frame's colour would not copy into a texture (GL error"
                                + " 0x{}), so grading and light shafts are off for this session:"
                                + " both read that copy, and reading it empty paints the world"
                                + " black", Integer.toHexString(error));
                    }
                }
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
                if (!sceneColourUsable) {
                    return;
                }

                if (wantOcclusion && aoPass(sceneDepthTexture)) {
                    GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
                    GL11C.glViewport(0, 0, width, height);
                    GL20C.glUseProgram(sceneOcclusionProgram);
                    GL20C.glUniform2f(sceneOcclusionInvSize, 1.0f / width, 1.0f / height);
                    GL20C.glUniform1f(sceneOcclusionAoOnly, showOcclusion ? 1.0f : 0.0f);
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, aoTexture);
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glColorTexture);
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sceneCopyTexture);
                    fullscreenQuad();
                    sceneOcclusionFrames++;
                    // The copy again, because the shafts below read the colour
                    // and the darkening has just changed it. Shafts gathered
                    // from the undarkened copy would carry light the picture no
                    // longer has.
                    if (wantRays) {
                        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevFbo);
                        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, sceneCopyFbo);
                        GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                                GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
                        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
                    }
                }
                if (wantRays) {
                    rayPass(prevFbo);
                }
            } finally {
                org.lwjgl.opengl.GL11.glPopAttrib();
            }
        } catch (Throwable t) {
            // The same fence every other full-screen effect stands behind: a
            // corner is decoration and the world is not.
            LOGGER.error("Whole-scene occlusion failed; off for this session", t);
            aoFailed = true;
        } finally {
            GL20C.glUseProgram(prevProgram);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
            // The unit first and the binding second, because the binding that
            // was read belongs to that unit. Binding before selecting puts it
            // on whichever unit this pass happened to leave selected, which is
            // zero — the one the game keeps its atlas on.
            GL13C.glActiveTexture(prevActive);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
        }
    }

    /** Asked once, because the formats cannot change while a session runs. */
    private boolean sceneDepthBlitChecked;
    private boolean sceneColourBlitChecked;
    private boolean toneBlitChecked;
    private boolean sceneColourUsable = true;
    private boolean sceneDepthUsable = true;
    /**
     * How many frames this pass actually darkened since the last snapshot.
     *
     * Counted at the draw rather than at the entry, because every interesting
     * way this pass can fail leaves the entry reached and the draw not: a
     * refused copy, a target that would not build, an occlusion term that had
     * nothing to work from. Three sessions were spent asking whether this pass
     * was painting a world black while the file it wrote said nothing about it
     * at all — every other effect in that snapshot names itself, and the one
     * under suspicion was the one that did not.
     */
    private int sceneOcclusionFrames;
    /** The pipeline state this pass was handed the first time it ran. */
    private String sceneStateAsFound;
    /** The centre pixel as this pass found it, before anything here touched it. */
    private String sceneEntryProbe;
    private int sceneProbeFrames;
    private int sceneDepthTexture;
    private int sceneDepthFbo;
    private int sceneCopyTexture;
    private int sceneCopyFbo;
    private int sceneOcclusionProgram;
    private int sceneOcclusionInvSize;
    private int sceneOcclusionAoOnly;
    private int sceneTargetsWidth;
    private int sceneTargetsHeight;
    /** Whether the corners of the whole picture are darkened instead of the blocks'. */
    private boolean sceneOcclusion;

    private int rayTexture;
    private int rayFbo;
    /** The other half of the ping-pong the shafts are smoothed through. */
    private int rayBlurTexture;
    private int rayBlurFbo;
    private int rayWidth;
    private int rayHeight;
    private int rayProgram;
    private int rayInvSize = -1;
    private int raySunUniform = -1;
    private int rayAddProgram;
    private int rayAddInvSize = -1;
    private int rayAddStrengthUniform = -1;
    private boolean raysFailed;
    /** How bright the shafts of light from the sun may be, 0 for off. */
    private float godRays;
    /**
     * Whether the game's frame really is floating this session.
     *
     * Read from what happened rather than from what was asked for. The
     * switch is a wish; the mixin that changes the format is the only thing
     * that knows whether the driver took it, and it says so by publishing
     * this. Grading a frame as though it had headroom it does not have
     * would darken the whole picture for nothing.
     */
    private boolean hdrFrame;
    /** Light let in before the film curve closes the range back down. */
    private float exposure = 0.5f;

    private boolean hdrFrameActive() {
        return hdrFrame;
    }

    /**
     * The exposure slider in stops, with the middle of it meaning no change.
     *
     * Stops rather than a straight multiplier because that is how light
     * behaves and how the slider will feel: every step the same size, and
     * the same distance either side of neutral.
     */
    private float exposureStops() {
        return (exposure - 0.5f) * 3.0f;
    }
    /** Where the sun is on the screen, and how much of it counts, this frame. */
    private final float[] sunScreen = new float[3];

    /**
     * Whether shafts are worth gathering this frame.
     *
     * The sun has to be above the horizon and in front of the eye, and its
     * place on the screen decides the rest: this technique gathers along the
     * line from a pixel towards the sun, so with the sun behind you every line
     * runs the wrong way and what comes out is not a dim effect but a wrong
     * one.
     */
    private boolean godRaysWanted() {
        if (godRays <= 0.0f || raysFailed || sunDirection[1] <= 0.0f) {
            return false;
        }
        computeSunScreen();
        return sunScreen[2] > 0.0f;
    }

    /**
     * The sun's place on the screen, and how much of it to believe.
     *
     * Weight rather than a yes or no, because the sun crossing the edge of the
     * screen must not switch the shafts off between one frame and the next —
     * that reads as a flicker, and a flicker is the one artefact nobody
     * forgives. It falls to nothing over half a screen outside the frame,
     * which is roughly where a shaft stops reaching into the picture anyway.
     */
    private void computeSunScreen() {
        sunScreen[2] = 0.0f;
        projectionMatrix.clear();
        GL11C.glGetFloatv(org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX, projectionMatrix);
        modelViewMatrix.clear();
        GL11C.glGetFloatv(org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX, modelViewMatrix);
        float x = sunDirection[0];
        float y = sunDirection[1];
        float z = sunDirection[2];
        float vx = modelViewMatrix.get(0) * x + modelViewMatrix.get(4) * y
                + modelViewMatrix.get(8) * z;
        float vy = modelViewMatrix.get(1) * x + modelViewMatrix.get(5) * y
                + modelViewMatrix.get(9) * z;
        float vz = modelViewMatrix.get(2) * x + modelViewMatrix.get(6) * y
                + modelViewMatrix.get(10) * z;
        // Behind the eye. A direction has no position, so this is the whole of
        // the test — there is no near plane to fall foul of.
        if (vz >= -1.0e-4f) {
            return;
        }
        float cx = projectionMatrix.get(0) * vx + projectionMatrix.get(4) * vy
                + projectionMatrix.get(8) * vz;
        float cy = projectionMatrix.get(1) * vx + projectionMatrix.get(5) * vy
                + projectionMatrix.get(9) * vz;
        float cw = projectionMatrix.get(3) * vx + projectionMatrix.get(7) * vy
                + projectionMatrix.get(11) * vz;
        if (!(cw > 1.0e-6f)) {
            return;
        }
        float u = (cx / cw) * 0.5f + 0.5f;
        float v = (cy / cw) * 0.5f + 0.5f;
        float outside = Math.max(
                Math.max(-u, u - 1.0f),
                Math.max(-v, v - 1.0f));
        float edge = 1.0f - Math.max(0.0f, outside) / 0.5f;
        if (!(edge > 0.0f)) {
            return;
        }
        sunScreen[0] = u;
        sunScreen[1] = v;
        // Faded out again as the sun nears the horizon, where the shafts lie
        // along the ground rather than across the picture and the same
        // strength reads as a wash of colour over everything.
        sunScreen[2] = edge * Math.min(1.0f, sunDirection[1] * 4.0f);
    }

    /**
     * Gathers the shafts at half resolution and adds them to the frame.
     *
     * The gathering is the oldest trick there is for this and still the right
     * one here: walk from the pixel towards the sun and add up what the sky
     * shows through, so terrain in the way leaves a dark lane and a gap in a
     * canopy leaves a bright one. It needs no geometry, no second view of the
     * world and no rays — which is what makes it the one shafts effect that
     * cannot break another mod, because everything it reads is a picture the
     * game has already finished drawing.
     */
    private void rayPass(int prevFbo) {
        try {
            if (!ensureRayTargets()) {
                return;
            }
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, rayFbo);
            GL11C.glViewport(0, 0, rayWidth, rayHeight);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL20C.glUseProgram(rayProgram);
            GL20C.glUniform2f(rayInvSize, 1.0f / rayWidth, 1.0f / rayHeight);
            GL20C.glUniform2f(raySunUniform, sunScreen[0], sunScreen[1]);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sceneDepthTexture);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sceneCopyTexture);
            fullscreenQuad();

            // Smoothed before it is added, the same way and with the same
            // program the occlusion is. Twenty-four yes-or-no answers a pixel
            // is twenty-five possible values, and the edge of a shaft walks
            // down them a step at a time; the offset that starts each walk
            // scatters those steps without removing them. Two passes of five
            // taps, across and then down, at half resolution — so the whole of
            // it costs a fraction of the gather it is smoothing.
            GL20C.glUseProgram(bloomBlurProgram);
            GL20C.glUniform2f(bloomBlurInvSize, 1.0f / rayWidth, 1.0f / rayHeight);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, rayBlurFbo);
            GL20C.glUniform2f(bloomBlurStep, RAY_BLUR_SPREAD, 0.0f);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, rayTexture);
            fullscreenQuad();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, rayFbo);
            GL20C.glUniform2f(bloomBlurStep, 0.0f, RAY_BLUR_SPREAD);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, rayBlurTexture);
            fullscreenQuad();

            // Added rather than mixed: light arriving along the line of sight
            // is light on top of what is already there, and a shaft crossing a
            // dark hillside has to brighten it rather than replace it.
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
            GL11C.glViewport(0, 0, width, height);
            GL11C.glEnable(GL11C.GL_BLEND);
            GL11C.glBlendFunc(GL11C.GL_ONE, GL11C.GL_ONE);
            GL20C.glUseProgram(rayAddProgram);
            GL20C.glUniform2f(rayAddInvSize, 1.0f / width, 1.0f / height);
            GL20C.glUniform1f(rayAddStrengthUniform, godRays * sunScreen[2]);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, rayTexture);
            fullscreenQuad();
            GL11C.glDisable(GL11C.GL_BLEND);
        } catch (Throwable t) {
            // Its own fence rather than the occlusion's: a fault in the shafts
            // is no reason to stop darkening the corners of the world.
            LOGGER.error("Light shafts failed; off for this session", t);
            raysFailed = true;
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
        }
    }

    private boolean ensureRayTargets() {
        int wantWidth = Math.max(1, width / 2);
        int wantHeight = Math.max(1, height / 2);
        if (rayFbo != 0 && wantWidth == rayWidth && wantHeight == rayHeight
                && rayProgram != 0 && rayAddProgram != 0) {
            return true;
        }
        destroyRayTargets();
        rayWidth = wantWidth;
        rayHeight = wantHeight;
        rayTexture = GL11C.glGenTextures();
        allocateBloomTexture(rayTexture, rayWidth, rayHeight);
        rayBlurTexture = GL11C.glGenTextures();
        allocateBloomTexture(rayBlurTexture, rayWidth, rayHeight);
        rayBlurFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, rayBlurFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, rayBlurTexture, 0);
        rayFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, rayFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, rayTexture, 0);
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.error("Light shaft framebuffer incomplete; shafts are off for this session");
            raysFailed = true;
            destroyRayTargets();
            return false;
        }
        buildRayPrograms();
        buildBlurProgram();
        return true;
    }

    private void buildRayPrograms() {
        if (rayProgram == 0) {
            rayProgram = buildQuadProgram(
                    "uniform sampler2D uSource;\n"
                            + "uniform sampler2D uDepth;\n"
                            + "uniform vec2 uInvSize;\n"
                            + "uniform vec2 uSun;\n"
                            // How much of the way to the sun the walk covers,
                            // and how fast a step stops counting. Short of the
                            // whole distance on purpose: the far end of that
                            // line is where the sun is, and a pixel there
                            // gathers the sun itself over and over.
                            + "const float RAY_SPAN = 0.85;\n"
                            + "const float RAY_DECAY = 0.94;\n"
                            + "void main() {\n"
                            + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                            + "    vec2 stride = (uv - uSun) * (RAY_SPAN / 24.0);\n"
                            // Started off the pixel's own place by a fraction
                            // of a step, different at every pixel, so the
                            // twenty-four samples do not land on the same rings
                            // across the whole screen. Rings are what this
                            // technique looks like when it is done wrong.
                            + "    vec3 h3 = fract(vec3(gl_FragCoord.xyx) * vec3(0.1031, 0.1030, 0.0973));\n"
                            + "    h3 += dot(h3, h3.yzx + 33.33);\n"
                            + "    vec2 p = uv - stride * fract((h3.x + h3.y) * h3.z);\n"
                            + "    vec3 total = vec3(0.0);\n"
                            + "    float weight = 1.0;\n"
                            + "    float share = 0.0;\n"
                            + "    for (int i = 0; i < 24; i++) {\n"
                            + "        p -= stride;\n"
                            // Only what the sky shows through counts. Terrain,
                            // a creature or another mod's machine standing in
                            // the line contributes nothing, and that absence is
                            // the shaft: the dark lanes are the shadows and the
                            // bright ones are the gaps.
                            + "        float d = texture2D(uDepth, p).r;\n"
                            + "        if (d >= 0.9999) {\n"
                            + "            total += texture2D(uSource, p).rgb * weight;\n"
                            + "        }\n"
                            + "        share += weight;\n"
                            + "        weight *= RAY_DECAY;\n"
                            + "    }\n"
                            + "    gl_FragColor = vec4(total / max(share, 0.0001), 1.0);\n"
                            + "}\n");
            rayInvSize = GL20C.glGetUniformLocation(rayProgram, "uInvSize");
            raySunUniform = GL20C.glGetUniformLocation(rayProgram, "uSun");
            int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            GL20C.glUseProgram(rayProgram);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(rayProgram, "uDepth"), 1);
            GL20C.glUseProgram(prev);
        }
        if (rayAddProgram == 0) {
            rayAddProgram = buildQuadProgram(
                    "uniform sampler2D uSource;\n"
                            + "uniform vec2 uInvSize;\n"
                            + "uniform float uStrength;\n"
                            // Warmed, because sunlight through air is warm and
                            // the sky colour gathered above is not — taking the
                            // sky's own colour straight gives blue shafts,
                            // which read as fog rather than as light.
                            + "const vec3 RAY_TINT = vec3(1.0, 0.92, 0.78);\n"
                            + "void main() {\n"
                            + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                            + "    gl_FragColor = vec4(texture2D(uSource, uv).rgb\n"
                            + "            * RAY_TINT * uStrength * 0.6, 1.0);\n"
                            + "}\n");
            rayAddInvSize = GL20C.glGetUniformLocation(rayAddProgram, "uInvSize");
            rayAddStrengthUniform = GL20C.glGetUniformLocation(rayAddProgram, "uStrength");
        }
    }

    private void destroyRayTargets() {
        if (rayBlurFbo != 0) {
            GL30C.glDeleteFramebuffers(rayBlurFbo);
            rayBlurFbo = 0;
        }
        if (rayBlurTexture != 0) {
            GL11C.glDeleteTextures(rayBlurTexture);
            rayBlurTexture = 0;
        }
        if (rayFbo != 0) {
            GL30C.glDeleteFramebuffers(rayFbo);
            rayFbo = 0;
        }
        if (rayTexture != 0) {
            GL11C.glDeleteTextures(rayTexture);
            rayTexture = 0;
        }
        rayWidth = 0;
        rayHeight = 0;
    }

    private boolean ensureSceneOcclusionTargets() {
        if (sceneDepthTexture != 0 && sceneTargetsWidth == width
                && sceneTargetsHeight == height && sceneOcclusionProgram != 0) {
            return true;
        }
        destroySceneOcclusionTargets();
        sceneDepthTexture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sceneDepthTexture);
        // The same twenty-four bits the game's own depth has, unless this card
        // has not got them.
        //
        // A depth blit is the one copy OpenGL will not convert for: source and
        // destination formats have to match exactly, and this is a second place
        // that rule reaches after the first one cost a release. Where the driver
        // gives no sampleable twenty-four-bit depth — every AMD card — this
        // renderer's targets are thirty-two-bit float, the frame's depth
        // arrives in that, and a blit into a twenty-four-bit texture is refused
        // silently. What is then read is a texture full of zeroes, which
        // reconstructs as every pixel sitting on the near plane, which makes
        // the occlusion pass shade everything against everything. The symptom
        // is not a wrong shade: it is a black world with the hand still visible,
        // because the hand is drawn after this.
        // Asked of the framebuffer this will be copied from, not worked out
        // from what Vulkan chose.
        //
        // Deriving it from the renderer's own format was the obvious guess and
        // it was backwards: where the driver has no sampleable twenty-four-bit
        // depth the Vulkan targets are float, but the game's framebuffer is
        // untouched and still twenty-four — so matching Vulkan created the
        // mismatch instead of curing it, and the copy started failing with
        // GL_INVALID_OPERATION where it had worked. The number of bits in the
        // attachment about to be read is the only thing that decides this, and
        // it can simply be asked for.
        int depthBits = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_FRAMEBUFFER,
                GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
        boolean depth32f = depthBits > 24;
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0,
                depth32f ? GL30C.GL_DEPTH_COMPONENT32F : GL30C.GL_DEPTH_COMPONENT24,
                width, height, 0, GL11C.GL_DEPTH_COMPONENT,
                depth32f ? GL11C.GL_FLOAT : GL11C.GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        LOGGER.info("Scene depth texture made {} bits to match the frame's own {}",
                depth32f ? "32 float" : "24", depthBits);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        sceneDepthFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, sceneDepthFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11C.GL_TEXTURE_2D, sceneDepthTexture, 0);
        // Depth only, and said out loud. A framebuffer object starts life
        // drawing to colour attachment zero whether or not it has one, and by
        // the rules a target named as a draw buffer and not attached makes the
        // whole thing incomplete — so this said nothing and was incomplete for
        // its entire existence, which makes the blit below undefined rather
        // than merely empty. The depth target on the other side of this file
        // already carries this line and the comment explaining it; this one is
        // the copy that never got it.
        //
        // The order matters more than it looks: asking whether the target is
        // complete before saying this would have been answered "no" on every
        // driver alive, and standing the effect down everywhere would have
        // looked exactly like curing it on the one card where it misbehaves.
        GL20C.glDrawBuffers(GL11C.GL_NONE);
        GL11C.glReadBuffer(GL11C.GL_NONE);
        boolean sceneTargetsComplete = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)
                == GL30C.GL_FRAMEBUFFER_COMPLETE;

        sceneCopyTexture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sceneCopyTexture);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, width, height,
                0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        sceneCopyFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, sceneCopyFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, sceneCopyTexture, 0);
        sceneTargetsComplete &= GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)
                == GL30C.GL_FRAMEBUFFER_COMPLETE;
        if (!sceneTargetsComplete) {
            LOGGER.error("Scene occlusion targets incomplete; the effect is off for this session");
            destroySceneOcclusionTargets();
            aoFailed = true;
            return false;
        }

        if (sceneOcclusionProgram == 0) {
            sceneOcclusionProgram = buildQuadProgram(
                    "uniform sampler2D uColor;\n"
                            + "uniform sampler2D uAo;\n"
                            // The terrain as Vulkan drew it, for its alpha
                            // alone: the share of the sky this surface gets.
                            // Nothing else here knows it, and without it the
                            // sun's shadow darkens a wall lit by a torch.
                            + "uniform sampler2D uTerrain;\n"
                            + "uniform vec2 uInvSize;\n"
                            // Flat grey instead of the darkened world, for the
                            // same diagnostic the simpler composite path has
                            // through uAo_only — this path had no equivalent,
                            // so the switch changed nothing here and a black
                            // screen with it on proved only that the picture
                            // was already black, not that this pass made it so.
                            + "uniform float uAoOnly;\n"
                            + "void main() {\n"
                            + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                            + "    vec3 c = texture2D(uColor, uv).rgb;\n"
                            // This pass runs over the finished frame, so a
                            // pixel here may be a creature or another mod's
                            // machine as easily as a block. Where the terrain
                            // wrote something, its alpha says how much sky
                            // that surface receives and the sun's share of the
                            // shadow is weighed by it — which is what the
                            // traced shadow does and what makes the two agree.
                            // Where it wrote nothing there is nothing to ask,
                            // and the old behaviour stands.
                            + "    vec2 shade = texture2D(uAo, uv).rg;\n"
                            + "    float ta = texture2D(uTerrain, uv).a;\n"
                            + "    float sky = ta < 0.004 || ta > 0.5\n"
                            + "            ? 1.0 : clamp(ta / 0.49, 0.0, 1.0);\n"
                            + "    vec3 ao = vec3(shade.r * (1.0 - shade.g * sky));\n"
                            // The diagnostic shows occlusion alone, not
                            // occlusion times shadow: it answers one question.
                            + "    gl_FragColor = vec4(mix(c * ao, vec3(shade.r), uAoOnly),\n"
                            + "                        1.0);\n"
                            + "}\n");
            int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            GL20C.glUseProgram(sceneOcclusionProgram);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(sceneOcclusionProgram, "uColor"), 0);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(sceneOcclusionProgram, "uAo"), 1);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(sceneOcclusionProgram, "uTerrain"), 2);
            sceneOcclusionInvSize =
                    GL20C.glGetUniformLocation(sceneOcclusionProgram, "uInvSize");
            sceneOcclusionAoOnly =
                    GL20C.glGetUniformLocation(sceneOcclusionProgram, "uAoOnly");
            GL20C.glUseProgram(prev);
        }
        sceneTargetsWidth = width;
        sceneTargetsHeight = height;
        return true;
    }

    private void destroySceneOcclusionTargets() {
        if (sceneDepthFbo != 0) {
            GL30C.glDeleteFramebuffers(sceneDepthFbo);
            sceneDepthFbo = 0;
        }
        if (sceneDepthTexture != 0) {
            GL11C.glDeleteTextures(sceneDepthTexture);
            sceneDepthTexture = 0;
        }
        if (sceneCopyFbo != 0) {
            GL30C.glDeleteFramebuffers(sceneCopyFbo);
            sceneCopyFbo = 0;
        }
        if (sceneCopyTexture != 0) {
            GL11C.glDeleteTextures(sceneCopyTexture);
            sceneCopyTexture = 0;
        }
        sceneTargetsWidth = 0;
        sceneTargetsHeight = 0;
    }

    void applySceneTone(int sceneTexture) {
        // A floating frame has to be brought back into range here whether
        // anything is being graded or not: this is the last place that
        // sees it before the hand and the interface are drawn over it, and
        // the screen shows eight bits whatever the buffer holds.
        if (toneFailed || (toneStrength <= 0.0f && !hdrFrameActive()) || sceneTexture == 0
                || width <= 0 || height <= 0) {
            return;
        }
        int prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        int prevTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        // The unit as well as the binding. glPushAttrib does not carry the
        // active unit back, this pass sets it to zero, and the game's next
        // draw would find its light map unit no longer selected. Every other
        // full-screen pass in this file saves it; this one did not.
        int prevActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        if (!ensureToneTargets(prevFbo, prevTexture)) {
            return;
        }
        // The last pass over the frame, and the one whose borrowed state costs
        // most if it is not given back: it writes over the whole picture, so
        // whoever draws next inherits its viewport, its blending and its
        // texture unit for everything, not for a corner of the screen. The
        // early return on a refused copy already had to hand all four back by
        // hand, which is the sign that the giving back belongs in one place.
        //
        // The push stays out here, above the try. Inside it, a throw before the
        // push had ever run would take the pop below with it and unbalance a
        // stack that is shared with the game.
        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT
                | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
                | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                | org.lwjgl.opengl.GL11.GL_TEXTURE_BIT
                | org.lwjgl.opengl.GL11.GL_CURRENT_BIT
                | org.lwjgl.opengl.GL11.GL_POLYGON_BIT
                | org.lwjgl.opengl.GL11.GL_VIEWPORT_BIT);
        try {
            toneInner(sceneTexture, prevFbo);
        } finally {
            org.lwjgl.opengl.GL11.glPopAttrib();
            GL20C.glUseProgram(prevProgram);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
            // The unit first and the binding second — see the same two lines at
            // the end of the whole-scene occlusion pass.
            GL13C.glActiveTexture(prevActive);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
        }
    }

    private void toneInner(int sceneTexture, int prevFbo) {
        GL11C.glDisable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDepthMask(false);

        // One frame, a few seconds in, this pass says what it was given and
        // what it left. Three numbers from the middle of the screen: the frame
        // as it arrived, the copy this pass will read, and the frame after the
        // grading has been written over it.
        //
        // It is here because this is where the argument keeps stopping. The
        // occlusion pass upstream reports that it shaded two and a half
        // thousand frames with the state it needs, this one reports that it
        // graded the same number, both copies were accepted, and the world is
        // black anyway — so every remaining explanation is about the values
        // rather than about whether the work happened, and no amount of asking
        // the driver for error codes will produce one. A refused copy has been
        // asked about for a session and a half; a copy that is accepted and
        // black has never been asked about at all.
        boolean probing = toneProbe == null && ++toneProbeFrames > 120;
        String probeBefore = probing ? probeCentre(prevFbo) : null;

        // A copy first, because a texture cannot be read and written at once,
        // and the thing being graded is the very image being drawn into. The
        // hardware blit is cheaper than a pass that only moves pixels.
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevFbo);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, toneFbo);
        GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
        // The copy this pass then reads and writes over the frame. If it was
        // refused the copy is black, and what gets written over the whole world
        // is black — with the hand still on top, because the hand comes after
        // this. There is no shade of wrong here that looks like a bug in the
        // grading: it is all or nothing, so it is worth one question per
        // session. Two things can refuse it, and both are invisible from the
        // Java side: a format the driver will not convert between, and a
        // multisampled frame, which cannot be blitted to a single-sampled one
        // at a different size or format at all.
        if (!toneBlitChecked) {
            toneBlitChecked = true;
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) {
                toneFailed = true;
                LOGGER.warn("The frame would not copy for grading (GL error 0x{}), so the scene"
                        + " tone pass is off for this session — it writes back what it read, and"
                        + " reading an empty copy paints the world black",
                        Integer.toHexString(error));
                // Nothing given back by hand here any more: the caller's
                // finally does all five, on this path and on every other.
                return;
            }
        }

        String probeCopy = probing ? probeCentre(toneFbo) : null;

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
        GL11C.glViewport(0, 0, width, height);
        GL20C.glUseProgram(toneProgram);
        GL20C.glUniform2f(toneInvSize, 1.0f / width, 1.0f / height);
        GL20C.glUniform1f(toneStrengthUniform, toneStrength);
        GL20C.glUniform1f(toneWarmthUniform, toneWarmth);
        GL20C.glUniform1f(toneHdrUniform, hdrFrameActive() ? 1.0f : 0.0f);
        GL20C.glUniform1f(toneExposureUniform, exposureStops());
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, toneTexture);
        fullscreenQuad();
        toneFrames++;
        if (probing) {
            toneProbe = probeBefore + " -> copy " + probeCopy + " -> after " + probeCentre(prevFbo);
            LOGGER.info("Scene tone, centre of the frame: {}", toneProbe);
        }
    }

    /**
     * One pixel from the middle of a framebuffer, as floats.
     *
     * Floats rather than bytes because the frame may be a floating one and the
     * whole question is whether the numbers in it are sane: read as bytes, a
     * value of eight would come back as one and a value that is not a number
     * would come back as nought, which is the answer being looked for and
     * therefore the last way to ask.
     */
    private String probeCentre(int fbo) {
        try (MemoryStack stack = stackPush()) {
            java.nio.FloatBuffer px = stack.mallocFloat(4);
            // A value the frame cannot hold, so that "the read did not happen"
            // and "the pixel really is black" stop reading the same. The first
            // version of this had zeroes for both and spent an afternoon
            // reporting a black world that was an unwritten array.
            px.put(0, -1.0f).put(1, -1.0f).put(2, -1.0f).put(3, -1.0f);
            // Two things silently swallow this read. A bound pack buffer turns
            // the array into an offset into that buffer, so the array keeps
            // whatever it had; and a multisampled frame cannot be read a pixel
            // at a time at all. Neither says anything unless asked.
            int prevPack = GL11C.glGetInteger(
                    org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
            if (prevPack != 0) {
                org.lwjgl.opengl.GL15C.glBindBuffer(
                        org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER, 0);
            }
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, fbo);
            int samples = GL11C.glGetInteger(GL30C.GL_SAMPLES);
            while (GL11C.glGetError() != GL11C.GL_NO_ERROR) {
                // Whatever came before is not what is being asked about.
            }
            GL11C.glReadPixels(width / 2, height / 2, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, px);
            int readError = GL11C.glGetError();
            if (prevPack != 0) {
                org.lwjgl.opengl.GL15C.glBindBuffer(
                        org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER, prevPack);
            }
            if (readError != GL11C.GL_NO_ERROR || samples > 1 || prevPack != 0) {
                return "UNREADABLE (GL error 0x" + Integer.toHexString(readError)
                        + ", " + samples + " samples, pack buffer " + prevPack + ")";
            }
            float r = px.get(0);
            float g = px.get(1);
            float b = px.get(2);
            String note = (Float.isNaN(r) || Float.isNaN(g) || Float.isNaN(b)) ? " NOT-A-NUMBER"
                    : (Float.isInfinite(r) || Float.isInfinite(g) || Float.isInfinite(b))
                            ? " INFINITE" : "";
            return String.format("%.3f/%.3f/%.3f a%.2f%s", r, g, b, px.get(3), note);
        }
    }

    /** Printed once a session, a couple of seconds after the pass starts. */
    private String toneProbe;
    private int toneProbeFrames;

    boolean isSceneToneAvailable() {
        return !toneFailed;
    }

    private int toneTexture;
    private int toneFbo;
    private int toneProgram;
    private int toneInvSize;
    private int toneStrengthUniform;
    private int toneWarmthUniform;
    private int toneHdrUniform = -1;
    private int toneExposureUniform = -1;
    private int toneWidth;
    private int toneHeight;
    private boolean toneFailed;
    private long toneFrames;
    private float toneStrength;
    private float toneWarmth;

    private boolean ensureToneTargets(int prevFbo, int prevTexture) {
        if (toneTexture != 0 && toneWidth == width && toneHeight == height && toneProgram != 0) {
            return true;
        }
        destroyToneTargets();
        try {
            toneTexture = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, toneTexture);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, width, height,
                    0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            toneFbo = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, toneFbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, toneTexture, 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)
                    != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                LOGGER.error("Tone framebuffer incomplete; scene tone is off for this session");
                toneFailed = true;
                destroyToneTargets();
                return false;
            }
            if (toneProgram == 0) {
                toneProgram = buildToneProgram();
                toneInvSize = GL20C.glGetUniformLocation(toneProgram, "uInvSize");
                toneStrengthUniform = GL20C.glGetUniformLocation(toneProgram, "uStrength");
                toneWarmthUniform = GL20C.glGetUniformLocation(toneProgram, "uWarmth");
                toneHdrUniform = GL20C.glGetUniformLocation(toneProgram, "uHdr");
                toneExposureUniform = GL20C.glGetUniformLocation(toneProgram, "uExposure");
            }
            toneWidth = width;
            toneHeight = height;
            return true;
        } catch (Throwable t) {
            LOGGER.error("Could not prepare the scene tone pass; it is off for this session", t);
            toneFailed = true;
            destroyToneTargets();
            return false;
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
        }
    }

    private void destroyToneTargets() {
        if (toneFbo != 0) {
            GL30C.glDeleteFramebuffers(toneFbo);
            toneFbo = 0;
        }
        if (toneTexture != 0) {
            GL11C.glDeleteTextures(toneTexture);
            toneTexture = 0;
        }
        toneWidth = 0;
        toneHeight = 0;
    }

    private int buildToneProgram() {
        return buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "uniform float uStrength;\n"
                        + "uniform float uWarmth;\n"
                        // Whether the frame being read has room above white in
                        // it, and how much light to let in before it is closed
                        // back down. Both do nothing at all while the frame is
                        // eight bits, which is the default.
                        + "uniform float uHdr;\n"
                        + "uniform float uExposure;\n"
                        // The film curve proper — the one thing an eight bit
                        // frame cannot have. It closes an open-ended range down
                        // into nought to one along a shoulder, so a highlight
                        // brighter than white keeps its shape instead of
                        // arriving already flattened into a white patch. This
                        // is the standard cheap fit to the academy curve: no
                        // table to carry and no branch, five multiplies.
                        + "vec3 filmic(vec3 x) {\n"
                        + "    return clamp((x * (2.51 * x + 0.03))\n"
                        + "               / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);\n"
                        + "}\n"
                        + "void main() {\n"
                        + "    vec3 c = texture2D(uSource, gl_FragCoord.xy * uInvSize).rgb;\n"
                        // Not part of the grading, and deliberately not behind
                        // the strength slider: with a floating frame this is
                        // the only thing that brings the picture back into the
                        // range a screen can show. Left out, everything above
                        // white is cut off at the moment of display and the
                        // headroom bought nothing at all.
                        + "    if (uHdr > 0.5) {\n"
                        + "        c = filmic(c * exp2(uExposure));\n"
                        + "    }\n"
                        // An S curve about the middle grey of the frame. Not a
                        // film curve: there is no headroom above white in an
                        // eight bit buffer, so anything that pushed highlights
                        // up would only be spending detail the frame already
                        // has. This leaves both ends where they are and steepens
                        // the middle, which is where a picture's contrast lives.
                        + "    vec3 s = c * c * (3.0 - 2.0 * c);\n"
                        // Warmth as a rotation of the balance rather than a
                        // tint added on top: red gains what blue gives up, so
                        // the average brightness of the frame does not move and
                        // a warm scene does not read as a brighter one.
                        + "    vec3 w = vec3(s.r * (1.0 + 0.10 * uWarmth),\n"
                        + "                  s.g * (1.0 + 0.02 * uWarmth),\n"
                        + "                  s.b * (1.0 - 0.10 * uWarmth));\n"
                        // Mixed rather than replaced, so the slider is a real
                        // amount and zero is the untouched frame to the bit.
                        + "    gl_FragColor = vec4(clamp(mix(c, w, uStrength), 0.0, 1.0), 1.0);\n"
                        + "}\n");
    }

    void applySceneBloom(int sceneTexture) {
        if (!bloomReady || bloomStrength <= 0.0f || bloomFailed
                || bloomTexture[0] == 0 || sceneTexture == 0) {
            return;
        }
        bloomReady = false;
        int prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int prevActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT
                | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
                | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                | org.lwjgl.opengl.GL11.GL_TEXTURE_BIT
                | org.lwjgl.opengl.GL11.GL_CURRENT_BIT
                | org.lwjgl.opengl.GL11.GL_POLYGON_BIT
                | org.lwjgl.opengl.GL11.GL_VIEWPORT_BIT);
        // Split the way the terrain composite is split, and for the same
        // reason: this borrows the attribute stack, the current program, the
        // texture unit and the framebuffer, and it was the last full-screen
        // pass here still handing all four back on the way out rather than on
        // any way out. Everything else in this chain gives them back from a
        // finally, and a frame drawn by whoever comes next with this pass's
        // viewport and blending is not a wrong glow, it is a wrong everything.
        try {
            bloomInner(sceneTexture, prevFbo);
        } finally {
            org.lwjgl.opengl.GL11.glPopAttrib();
            GL20C.glUseProgram(prevProgram);
            GL13C.glActiveTexture(prevActive);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
        }
    }

    private void bloomInner(int sceneTexture, int prevFbo) {
        GL11C.glDisable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDepthMask(false);

        // What glows, taken from the finished frame rather than from this
        // renderer's own picture of the world.
        //
        // This is the whole of why a creature stops being lit through. The
        // terrain image has no creatures in it, so a glowstone block behind one
        // was still visible in it and its glow was added straight over whatever
        // stood in front — the block appeared to shine through the mob, and
        // through a pane of glass put in front of lava. The finished frame has
        // everything in it, so a covered source simply is not there any more.
        // Whether it is covered is decided by comparing the frame against what
        // the terrain looked like before the game drew into it: equal means
        // nothing was put in the way.
        // Once, at half size, where the grid is fine enough that a source a
        // few pixels wide cannot fall between the samples.
        GL20C.glUseProgram(bloomExtractProgram);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomMaskTexture);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sceneTexture);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomNearFbo[0]);
        GL11C.glViewport(0, 0, bloomNearWidth, bloomNearHeight);
        GL20C.glUniform2f(bloomExtractInvSize, 1.0f / bloomNearWidth, 1.0f / bloomNearHeight);
        GL20C.glUniform2f(bloomExtractTexel, 1.0f / width, 1.0f / height);
        fullscreenQuad();

        // And down to the wide chain by averaging rather than by picking.
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomFbo[0]);
        GL11C.glViewport(0, 0, bloomWidth, bloomHeight);
        GL20C.glUseProgram(bloomDownProgram);
        GL20C.glUniform2f(bloomDownInvSize, 1.0f / bloomWidth, 1.0f / bloomHeight);
        GL20C.glUniform2f(bloomDownTexel, 1.0f / bloomNearWidth, 1.0f / bloomNearHeight);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomNearTexture[0]);
        fullscreenQuad();

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomFarFbo[0]);
        GL11C.glViewport(0, 0, bloomFarWidth, bloomFarHeight);
        GL20C.glUniform2f(bloomDownInvSize, 1.0f / bloomFarWidth, 1.0f / bloomFarHeight);
        GL20C.glUniform2f(bloomDownTexel, 1.0f / bloomWidth, 1.0f / bloomHeight);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomTexture[0]);
        fullscreenQuad();

        GL20C.glUseProgram(bloomBlurProgram);
        GL11C.glViewport(0, 0, bloomFarWidth, bloomFarHeight);
        GL20C.glUniform2f(bloomBlurInvSize, 1.0f / bloomFarWidth, 1.0f / bloomFarHeight);
        for (int round = 0; round < 3; round++) {
            for (int axis = 0; axis < 2; axis++) {
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomFarFbo[1 - axis]);
                GL20C.glUniform2f(bloomBlurStep, axis == 0 ? 1.0f : 0.0f, axis == 0 ? 0.0f : 1.0f);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomFarTexture[axis]);
                fullscreenQuad();
            }
        }
        GL11C.glViewport(0, 0, bloomWidth, bloomHeight);
        GL20C.glUniform2f(bloomBlurInvSize, 1.0f / bloomWidth, 1.0f / bloomHeight);
        for (int round = 0; round < 3; round++) {
            for (int axis = 0; axis < 2; axis++) {
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomFbo[1 - axis]);
                GL20C.glUniform2f(bloomBlurStep, axis == 0 ? 1.0f : 0.0f, axis == 0 ? 0.0f : 1.0f);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomTexture[axis]);
                fullscreenQuad();
            }
        }
        // The tight one, once across and once down. More rounds here would
        // only turn it into the wide one again.
        GL11C.glViewport(0, 0, bloomNearWidth, bloomNearHeight);
        GL20C.glUniform2f(bloomBlurInvSize, 1.0f / bloomNearWidth, 1.0f / bloomNearHeight);
        for (int axis = 0; axis < 2; axis++) {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomNearFbo[1 - axis]);
            GL20C.glUniform2f(bloomBlurStep, axis == 0 ? 1.0f : 0.0f, axis == 0 ? 0.0f : 1.0f);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomNearTexture[axis]);
            fullscreenQuad();
        }

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
        GL11C.glViewport(0, 0, width, height);
        GL11C.glEnable(GL11C.GL_BLEND);
        GL11C.glBlendFunc(GL11C.GL_ONE, GL11C.GL_ONE);
        GL20C.glUseProgram(bloomAddProgram);
        GL20C.glUniform2f(bloomAddInvSize, 1.0f / width, 1.0f / height);
        GL20C.glUniform1f(bloomAddStrength, bloomStrength);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomMaskTexture);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomNearTexture[0]);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomFarTexture[0]);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bloomTexture[0]);
        fullscreenQuad();
    }

    private void fullscreenQuad() {
        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        org.lwjgl.opengl.GL11.glVertex2f(-1.0f, -1.0f);
        org.lwjgl.opengl.GL11.glVertex2f(1.0f, -1.0f);
        org.lwjgl.opengl.GL11.glVertex2f(1.0f, 1.0f);
        org.lwjgl.opengl.GL11.glVertex2f(-1.0f, 1.0f);
        org.lwjgl.opengl.GL11.glEnd();
    }

    /** Half-resolution targets and the three programs; built once per size. */
    private boolean ensureBloomTargets() {
        // An eighth of the screen on each axis, and the number was reached by
        // being told twice that the effect could not be seen.
        //
        // What makes a glow read as a glow is how far it reaches, and a blur of
        // a fixed number of taps reaches twice as far across the frame for
        // every halving of the target it runs on — while costing a quarter as
        // much, so reach here is not a trade against speed but the same lever
        // as speed. At half resolution the halo stopped about six pixels out,
        // close enough to the edge of a block to be taken for the block being
        // brighter, which is the one thing it must not be taken for. At a
        // quarter it was a rim drawn round the block: visible, and still not
        // light falling on anything. What has to happen is that the ground
        // beside a lava lake changes colour, and that is tens of pixels.
        //
        // Small sources do not get lost at this size the way they look as
        // though they should. A torch is a texel here, but a blur moves energy
        // rather than discarding it, so what a torch becomes is a wide faint
        // glow — which is what a torch across a room actually looks like.
        int wantWidth = Math.max(1, width / 8);
        int wantHeight = Math.max(1, height / 8);
        if (bloomFbo[0] != 0 && wantWidth == bloomWidth && wantHeight == bloomHeight) {
            return true;
        }
        destroyBloomTargets();
        bloomWidth = wantWidth;
        bloomHeight = wantHeight;
        if (!buildBloomTargets()) {
            if (!bloomFloat) {
                return false;
            }
            // A driver without float render targets: the reach will suffer,
            // which is better than the effect not existing.
            LOGGER.warn("Bloom targets refused a float format; falling back to eight bits");
            bloomFloat = false;
            destroyBloomTargets();
            bloomWidth = wantWidth;
            bloomHeight = wantHeight;
            if (!buildBloomTargets()) {
                bloomFailed = true;
                return false;
            }
        }
        try {
            buildBloomPrograms();
        } catch (RuntimeException e) {
            LOGGER.error("Bloom programs failed to build; the effect is off for this session", e);
            destroyBloomTargets();
            bloomFailed = true;
            return false;
        }
        return true;
    }

    private boolean buildBloomTargets() {
        GL11C.glGetError();
        int prevFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        int prevTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        for (int i = 0; i < 2; i++) {
            bloomTexture[i] = GL11C.glGenTextures();
            allocateBloomTexture(bloomTexture[i], bloomWidth, bloomHeight);
            bloomFbo[i] = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomFbo[i]);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, bloomTexture[i], 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                LOGGER.error("Bloom framebuffer incomplete; the effect is off for this session");
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
                destroyBloomTargets();
                bloomFailed = true;
                return false;
            }
        }
        int maskWidth = Math.max(1, width / 2);
        int maskHeight = Math.max(1, height / 2);
        bloomNearWidth = maskWidth;
        bloomNearHeight = maskHeight;
        for (int i = 0; i < 2; i++) {
            bloomNearTexture[i] = GL11C.glGenTextures();
            allocateBloomTexture(bloomNearTexture[i], bloomNearWidth, bloomNearHeight);
            bloomNearFbo[i] = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomNearFbo[i]);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, bloomNearTexture[i], 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                LOGGER.error("Bloom framebuffer incomplete; the effect is off for this session");
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
                destroyBloomTargets();
                bloomFailed = true;
                return false;
            }
        }
        bloomFarWidth = Math.max(1, width / 32);
        bloomFarHeight = Math.max(1, height / 32);
        for (int i = 0; i < 2; i++) {
            bloomFarTexture[i] = GL11C.glGenTextures();
            allocateBloomTexture(bloomFarTexture[i], bloomFarWidth, bloomFarHeight);
            bloomFarFbo[i] = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomFarFbo[i]);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, bloomFarTexture[i], 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
                return false;
            }
        }
        bloomMaskTexture = GL11C.glGenTextures();
        allocateBloomTexture(bloomMaskTexture, maskWidth, maskHeight);
        bloomMaskFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bloomMaskFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, bloomMaskTexture, 0);
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.error("Bloom mask framebuffer incomplete; the effect is off for this session");
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
            destroyBloomTargets();
            bloomFailed = true;
            return false;
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
        return true;
    }

    /** One blur target; float where the driver allows it. See {@link #bloomFloat}. */
    private void allocateBloomTexture(int texture, int w, int h) {
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        if (bloomFloat) {
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA16F, w, h,
                    0, GL11C.GL_RGBA, GL11C.GL_FLOAT, (java.nio.ByteBuffer) null);
        } else {
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, w, h,
                    0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        }
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
    }

    private void destroyBloomTargets() {
        for (int i = 0; i < 2; i++) {
            if (bloomFbo[i] != 0) {
                GL30C.glDeleteFramebuffers(bloomFbo[i]);
                bloomFbo[i] = 0;
            }
            if (bloomTexture[i] != 0) {
                GL11C.glDeleteTextures(bloomTexture[i]);
                bloomTexture[i] = 0;
            }
        }
        for (int i = 0; i < 2; i++) {
            if (bloomFarFbo[i] != 0) {
                GL30C.glDeleteFramebuffers(bloomFarFbo[i]);
                bloomFarFbo[i] = 0;
            }
            if (bloomFarTexture[i] != 0) {
                GL11C.glDeleteTextures(bloomFarTexture[i]);
                bloomFarTexture[i] = 0;
            }
            if (bloomNearFbo[i] != 0) {
                GL30C.glDeleteFramebuffers(bloomNearFbo[i]);
                bloomNearFbo[i] = 0;
            }
            if (bloomNearTexture[i] != 0) {
                GL11C.glDeleteTextures(bloomNearTexture[i]);
                bloomNearTexture[i] = 0;
            }
        }
        if (bloomMaskFbo != 0) {
            GL30C.glDeleteFramebuffers(bloomMaskFbo);
            bloomMaskFbo = 0;
        }
        if (bloomMaskTexture != 0) {
            GL11C.glDeleteTextures(bloomMaskTexture);
            bloomMaskTexture = 0;
        }
        bloomWidth = 0;
        bloomHeight = 0;
        bloomReady = false;
    }

    /**
     * Hardware copy of the Vulkan depth buffer into the game's, replacing a
     * gl_FragDepth write in the composite shader. Any driver complaint retires
     * the path for the rest of the session — the shader fallback is correct,
     * just slower.
     */
    private void blitDepth() {
        GL11C.glGetError();
        int prevRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, glDepthBlitFbo);
        GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevRead);
        int error = GL11C.glGetError();
        if (error != 0) {
            depthBlit = false;
            LOGGER.warn("glBlitFramebuffer(depth) rejected with 0x{} — using the gl_FragDepth composite",
                    Integer.toHexString(error));
        }
    }

    /** Copies the middle strip of the color target into the readback buffer. */
    private void recordColorReadback(MemoryStack stack) {
        if (readbackBuffer == 0) {
            return;
        }
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(colorImage);
        barrier.get(0).subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).imageOffset(o -> o.x(0).y(Math.max(0, height / 2 - READBACK_ROWS / 2)).z(0));
        region.get(0).imageExtent(e -> e.width(width).height(READBACK_ROWS).depth(1));
        region.get(0).imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        vkCmdCopyImageToBuffer(commandBuffer, colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                readbackBuffer, region);

        barrier.get(0)
                .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier);
        readbackRecorded = true;
    }

    /**
     * Proof-by-log for the first frames: how much geometry was submitted and
     * how much of the shared color image actually contains pixels when GL
     * reads it back. Separates "Vulkan drew nothing" from "composite failed".
     */
    private void logFrameDiagnostics() {
        // The GL half of this stops the world with glFinish and pulls the whole
        // colour attachment back over the bus, which at 4K is around thirty
        // megabytes. Twice a session is not much, but it lands in the seconds
        // where the world is being built, and it is answering a question that
        // was answered a long time ago.
        if (!STARTUP_READBACK || (frameCounter != 1 && frameCounter != 120)) {
            return;
        }
        double vkCoverage = -1.0;
        if (readbackRecorded) {
            readbackRecorded = false;
            try {
                check(vkWaitForFences(device(), fence, true, 1_000_000_000L), "vkWaitForFences(readback)");
                int samples = width * READBACK_ROWS;
                int covered = 0;
                for (int i = 0; i < samples; i++) {
                    if (MemoryUtil.memGetByte(readbackMapped + i * 4L + 3) != 0) {
                        covered++;
                    }
                }
                vkCoverage = 100.0 * covered / samples;
            } catch (Throwable t) {
                LOGGER.warn("VK-side readback failed", t);
            }
        }
        double coverage = -1.0;
        try {
            GL11C.glFinish(); // make sure the Vulkan frame + semaphore handoff completed
            int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glColorTexture);
            ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
            try {
                GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
                long covered = 0;
                for (int i = 3; i < pixels.capacity(); i += 4 * 16) { // sample every 16th pixel
                    if (pixels.get(i) != 0) {
                        covered++;
                    }
                }
                coverage = 100.0 * covered / (width * (long) height / 16);
            } finally {
                MemoryUtil.memFree(pixels);
            }
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
        } catch (Throwable t) {
            LOGGER.warn("Coverage readback failed", t);
        }
        LOGGER.info("Terrain frame {}: {} chunks, {} vertices, {} skipped (no mirror); "
                        + "VK-side coverage {}% (middle strip), GL-side coverage {}%",
                frameCounter, frameChunks, frameVertices, frameSkipped,
                vkCoverage < 0 ? "?" : String.format("%.1f", vkCoverage),
                coverage < 0 ? "?" : String.format("%.1f", coverage));
    }

    // ------------------------------------------------------------------
    // Lightmap (16x16, refreshed every frame from the GL texture)
    // ------------------------------------------------------------------

    /** ARGB ints → RGBA8 staging bytes; no GL involvement, no pipeline stall. */
    /** Order-sensitive so a swap of two texels still counts as a change. */
    private static int hashLightmap(int[] argb) {
        int hash = 1;
        for (int i = 0; i < argb.length; i++) {
            hash = hash * 31 + argb[i];
        }
        return hash;
    }

    private void writeLightmapStaging(int[] argb) {
        for (int i = 0; i < argb.length; i++) {
            int v = argb[i];
            long p = lightmapStagingMapped[activeFrameSlot] + i * 4L;
            MemoryUtil.memPutByte(p, (byte) (v >> 16));
            MemoryUtil.memPutByte(p + 1, (byte) (v >> 8));
            MemoryUtil.memPutByte(p + 2, (byte) v);
            MemoryUtil.memPutByte(p + 3, (byte) (v >>> 24));
        }
    }

    private void readLightmapFromGL() {
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lightmapGlId);
        lightmapReadBuffer.clear();
        GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, lightmapReadBuffer);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
        MemoryUtil.memCopy(MemoryUtil.memAddress(lightmapReadBuffer), lightmapStagingMapped[activeFrameSlot],
                LIGHTMAP_SIZE * LIGHTMAP_SIZE * 4);
    }

    private void recordLightmapUpload(MemoryStack stack) {
        VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(1, stack);
        toTransfer.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(lightmapImageInitialized ? VK_ACCESS_SHADER_READ_BIT : 0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(lightmapImageInitialized ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                        : VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(lightmapImage);
        toTransfer.get(0).subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer,
                lightmapImageInitialized ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).imageExtent(e -> e.width(LIGHTMAP_SIZE).height(LIGHTMAP_SIZE).depth(1));
        region.get(0).imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        vkCmdCopyBufferToImage(commandBuffer, lightmapStagingBuffer[activeFrameSlot], lightmapImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

        VkImageMemoryBarrier.Buffer toShader = VkImageMemoryBarrier.calloc(1, stack);
        toShader.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(lightmapImage);
        toShader.get(0).subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, toShader);
        lightmapImageInitialized = true;
    }

    // ------------------------------------------------------------------
    // Resource creation
    // ------------------------------------------------------------------

    private void ensureBaseResources() {
        if (baseReady) {
            return;
        }
        LOGGER.info("{}", VertexLayout.describe());
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(ctx.getGraphicsQueueFamily());
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(device(), poolInfo, null, pPool), "vkCreateCommandPool(terrain)");
            commandPool = pPool.get(0);
            createQueryPool(stack);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(framesInFlight);
            PointerBuffer pCmd = stack.mallocPointer(framesInFlight);
            check(vkAllocateCommandBuffers(device(), allocInfo, pCmd), "vkAllocateCommandBuffers(terrain)");
            commandBuffers = new VkCommandBuffer[framesInFlight];
            fences = new long[framesInFlight];
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer pFence = stack.mallocLong(1);
            for (int i = 0; i < framesInFlight; i++) {
                commandBuffers[i] = new VkCommandBuffer(pCmd.get(i), device());
                check(vkCreateFence(device(), fenceInfo, null, pFence), "vkCreateFence(terrain)");
                fences[i] = pFence.get(0);
            }
            commandBuffer = commandBuffers[0];
            fence = fences[0];

            // The translucent pass is a submission of its own, so it needs a
            // command buffer of its own per frame in flight — the opaque one is
            // already submitted and cannot be added to.
            PointerBuffer pTranslucentCmd = stack.mallocPointer(framesInFlight);
            check(vkAllocateCommandBuffers(device(), allocInfo, pTranslucentCmd),
                    "vkAllocateCommandBuffers(translucent)");
            translucentCommandBuffers = new VkCommandBuffer[framesInFlight];
            translucentFences = new long[framesInFlight];
            for (int i = 0; i < framesInFlight; i++) {
                translucentCommandBuffers[i] = new VkCommandBuffer(pTranslucentCmd.get(i), device());
                check(vkCreateFence(device(), fenceInfo, null, pFence), "vkCreateFence(translucent)");
                translucentFences[i] = pFence.get(0);
            }

            VkExportSemaphoreCreateInfo export = VkExportSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO)
                    .handleTypes(Interop.semaphoreHandleType());
            // Windows hands out a handle with access rights attached, and the
            // rights are only defaulted when this structure is absent — which
            // a driver is free to read as "none". A handle like that imports
            // without complaint and then never becomes signalled, so the wait
            // on the OpenGL side never returns and the card is reset out from
            // under the process. Asking for full access costs nothing and is
            // what the specification expects for an exported Win32 handle.
            long exportChain = Interop.appendWin32SemaphoreRights(stack, export.address());
            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                    .pNext(exportChain);
            LongBuffer pSem = stack.mallocLong(1);
            check(vkCreateSemaphore(device(), semInfo, null, pSem), "vkCreateSemaphore");
            vkSignalSemaphore = pSem.get(0);
            check(vkCreateSemaphore(device(), semInfo, null, pSem), "vkCreateSemaphore");
            vkWaitSemaphore = pSem.get(0);
            glWaitSemaphore = importSemaphore(stack, vkSignalSemaphore);
            glSignalSemaphore = importSemaphore(stack, vkWaitSemaphore);
            check(vkCreateSemaphore(device(), semInfo, null, pSem), "vkCreateSemaphore(translucent)");
            vkTranslucentSignalSemaphore = pSem.get(0);
            check(vkCreateSemaphore(device(), semInfo, null, pSem), "vkCreateSemaphore(translucent)");
            vkTranslucentWaitSemaphore = pSem.get(0);
            glTranslucentWaitSemaphore = importSemaphore(stack, vkTranslucentSignalSemaphore);
            glTranslucentSignalSemaphore = importSemaphore(stack, vkTranslucentWaitSemaphore);

            createDescriptorInfrastructure(stack);
            createDrawBatches(stack);
            decideColourDepth(stack);
            createRenderPass(stack);
            pipelineCacheHandle = pipelineCache.create(
                    device(), System.getProperty("vulkanmod112.pipelineCache"));
            createPipeline(stack);
            createSpriteResources(stack);
            createCompositeProgram();
        }
        updateDescriptors();
        baseReady = true;
        LOGGER.info("Terrain renderer base resources ready");
    }

    private long createAtlasSampler(MemoryStack stack) {
        VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_NEAREST)
                .minFilter(VK_FILTER_NEAREST)
                // Nearest inside a level keeps the pixel-art look; linear
                // between levels kills the shimmer on distant chunks. maxLod
                // is clamped by the image's actual level count.
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                .maxLod(VK_LOD_CLAMP_NONE)
                // Flat colours: every block face reads the smallest level of the
                // atlas, where a sprite has been reduced to a single texel.
                //
                // This is not the opposite of mipmapping, it is the far end of
                // it. Sampling costs what it costs because of cache misses, and
                // the whole purpose of a mip chain is to keep roughly one texel
                // per pixel so the cache stays warm; pinning it to the last
                // level means one texel per face, which is the cheapest a
                // texture read can be. Turning mipmaps off entirely — the
                // obvious-looking way to make textures cheap — does the reverse,
                // sending distant chunks to read the full-size atlas at random.
                //
                // One level short of the end rather than the end itself.
                //
                // The last level is a single texel per sprite, and an ore block
                // is stone with specks in it: averaged that far down, iron and
                // stone come out the same grey, and the preset this belongs to
                // is the one meant to be played on. Losing the ore there is not
                // losing prettiness, it is losing the game. One level back is
                // four texels — enough that ore reads as speckled and every
                // other block still reads as one colour with a hint of its own
                // pattern, and it is the block's real texture rather than a
                // second colour invented for it.
                //
                // Worked out from the atlas rather than written as a number: how
                // many levels there are depends on the pack, and clamping past
                // the end silently lands on the end, which is exactly the value
                // this is trying not to use.
                // Nought in both cases now. Under flat colours the trimming of
                // the mirror has already done what this used to do — the
                // levels it clamped away are no longer in the image to clamp —
                // and two mechanisms for one rule is how they drift apart.
                .minLod(0.0f)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        LongBuffer pSampler = stack.mallocLong(1);
        check(vkCreateSampler(device(), info, null, pSampler), "vkCreateSampler(atlas)");
        return pSampler.get(0);
    }

    private void createDescriptorInfrastructure(MemoryStack stack) {
        atlasSampler = createAtlasSampler(stack);
        // The light map: sixteen by sixteen, one level, read smoothly.
        //
        // Linear inside the level because this is a gradient and not a picture
        // — its two coordinates are how much block light and how much sky
        // light reach a vertex, and stepping between them in sixteenths shows
        // up as banding across every surface in the world. There is no mip
        // chain to walk, so the level clamp is zero and the mip mode never
        // comes up at all.
        //
        // What stood here was a copy of the atlas sampler's own paragraph,
        // explaining flat block colours and a level clamp of fifteen: on an
        // object that was then overwritten two lines further down, and about a
        // texture with one level and no flat-colour setting. None of it was
        // ever true of this sampler. The overwrite has gone with it — the
        // values below are the ones that were reaching the driver anyway.
        VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_LINEAR)
                .minFilter(VK_FILTER_LINEAR)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .maxLod(0.0f)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        LongBuffer pSampler = stack.mallocLong(1);
        check(vkCreateSampler(device(), samplerInfo, null, pSampler), "vkCreateSampler(lightmap)");
        lightmapSampler = pSampler.get(0);

        // Held at the edge and unfiltered. A ray walking off the side of the
        // picture must not come back with the other side of it — the same
        // default that had the corner shading reading the far edge of the
        // screen, and the reason that one is now said out loud everywhere.
        VkSamplerCreateInfo sceneSamplerInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).maxLod(0.0f);
        LongBuffer pSceneSampler = stack.mallocLong(1);
        check(vkCreateSampler(device(), sceneSamplerInfo, null, pSceneSampler),
                "vkCreateSampler(scene)");
        sceneSampler = pSceneSampler.get(0);

        createFrameUniforms(stack);

        boolean tracing = ctx.isRayTracingEnabled() && ctx.isRayQuerySupported();
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(tracing ? 7 : 6, stack);
        bindings.get(0).binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        bindings.get(1).binding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        bindings.get(2).binding(2)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
        // The frame's matrix is wanted in the vertex stage and its fog in the
        // fragment stage, so this one is visible to both.
        bindings.get(3).binding(3)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        // The finished picture behind the water, colour and depth, for the
        // reflection to walk across. Declared for every pipeline because the
        // layout is one, and pointed at the block atlas in all the passes that
        // must not read it — during those this colour image is the attachment
        // being written, and a pass may not read what it is writing.
        bindings.get(4).binding(4)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        bindings.get(5).binding(5)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        if (tracing) {
            // The world as a thing a ray can be fired into. Declared only where
            // the driver can trace: a layout naming a descriptor type an
            // extension brought in is not creatable without that extension.
            bindings.get(6).binding(7)
                    .descriptorType(org.lwjgl.vulkan.KHRAccelerationStructure
                            .VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        }
        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);
        LongBuffer pLayout = stack.mallocLong(1);
        check(vkCreateDescriptorSetLayout(device(), layoutInfo, null, pLayout), "vkCreateDescriptorSetLayout");
        descriptorSetLayout = pLayout.get(0);

        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(tracing ? 4 : 3, stack);
        poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(drawDescriptorSets.length * 4);
        poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(drawDescriptorSets.length);
        poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(drawDescriptorSets.length);
        if (tracing) {
            poolSizes.get(3).type(org.lwjgl.vulkan.KHRAccelerationStructure
                    .VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(drawDescriptorSets.length);
        }
        VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .pPoolSizes(poolSizes)
                .maxSets(drawDescriptorSets.length);
        LongBuffer pPool = stack.mallocLong(1);
        check(vkCreateDescriptorPool(device(), poolInfo, null, pPool), "vkCreateDescriptorPool");
        descriptorPool = pPool.get(0);

        VkDescriptorSetAllocateInfo setInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descriptorPool)
                .pSetLayouts(stack.mallocLong(drawDescriptorSets.length));
        for (int i = 0; i < drawDescriptorSets.length; i++) {
            setInfo.pSetLayouts().put(i, descriptorSetLayout);
        }
        LongBuffer pSet = stack.mallocLong(drawDescriptorSets.length);
        check(vkAllocateDescriptorSets(device(), setInfo, pSet), "vkAllocateDescriptorSets");
        for (int i = 0; i < drawDescriptorSets.length; i++) {
            drawDescriptorSets[i] = pSet.get(i);
        }
        descriptorSet = drawDescriptorSets[0];
    }

    /**
     * Allocates the per-frame, per-layer indirect batches.
     *
     * The GPU reads both halves of every batch on each frame: the vertex shader
     * fetches a chunk origin per draw, and the command processor fetches the
     * draw commands themselves. Left in ordinary host memory, that is thousands
     * of small reads across PCIe every frame, and it competes with chunk
     * geometry streaming over the same bus.
     *
     * So we ask for memory that is device-local *and* host-visible — the BAR
     * window, present on any card with resizable BAR and on integrated GPUs by
     * definition — and fall back to plain host-visible memory where the driver
     * does not expose such a type. Both are written the same way, so the
     * fallback costs nothing but the bandwidth it was going to cost anyway.
     *
     * What this memory is bad at is being read back: it is uncached on the CPU
     * side. Nothing on the frame path reads it; {@code logDrawInputs} does, on
     * two frames of a session, and that is why it stays a diagnostic.
     */
    private void createDrawBatches(MemoryStack stack) {
        for (int i = 0; i < drawBatchBuffers.length; i++) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(drawBatchBytes)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device(), info, null, pBuffer), "vkCreateBuffer(indirect terrain)");
            drawBatchBuffers[i] = pBuffer.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), drawBatchBuffers[i], req);
            int hostVisible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            int barType = findMemoryTypeOrNone(stack, req.memoryTypeBits(),
                    hostVisible | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            LongBuffer pMemory = stack.mallocLong(1);
            boolean deviceLocal = false;
            if (barType >= 0 && (i == 0 || indirectMemoryIsDeviceLocal)) {
                VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(req.size())
                        .memoryTypeIndex(barType);
                // Without resizable BAR this window is 256 MiB for the whole
                // system, so a driver saying no here is an ordinary outcome and
                // not an error. Host memory still works; it is only slower.
                deviceLocal = vkAllocateMemory(device(), alloc, null, pMemory) == VK_SUCCESS;
                if (!deviceLocal && i == 0) {
                    LOGGER.info("Indirect batches did not fit in BAR memory; using host memory");
                }
            }
            if (!deviceLocal) {
                VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(req.size())
                        .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), hostVisible));
                check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(indirect terrain)");
            }
            // One batch falling back means the window is full; report the whole
            // set as host memory and stop asking for the rest.
            indirectMemoryIsDeviceLocal = deviceLocal && (i == 0 || indirectMemoryIsDeviceLocal);
            drawBatchMemories[i] = pMemory.get(0);
            check(vkBindBufferMemory(device(), drawBatchBuffers[i], drawBatchMemories[i], 0),
                    "vkBindBufferMemory(indirect terrain)");
            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(device(), drawBatchMemories[i], 0, drawBatchBytes, 0, mapped),
                    "vkMapMemory(indirect terrain)");
            drawBatchMapped[i] = mapped.get(0);
        }
    }

    private void destroyDrawBatches() {
        for (int i = 0; i < drawBatchBuffers.length; i++) {
            if (drawBatchMemories[i] != 0) {
                vkUnmapMemory(device(), drawBatchMemories[i]);
                vkDestroyBuffer(device(), drawBatchBuffers[i], null);
                vkFreeMemory(device(), drawBatchMemories[i], null);
                drawBatchBuffers[i] = 0;
                drawBatchMemories[i] = 0;
                drawBatchMapped[i] = 0;
            }
        }
    }

    /**
     * Grows the indirect batch so a layer of {@code draws} chunks fits.
     *
     * Called before the frame's command buffer is opened, because the old
     * buffers may still be read by a frame in flight and the descriptor sets
     * that point at them have to be rewritten.
     */
    private void ensureDrawBatchCapacity(int draws) {
        if (draws <= indirectDrawCapacity || indirectDrawCapacity >= MAX_INDIRECT_DRAWS) {
            return;
        }
        // Headroom, because the visible-chunk count moves with every step.
        int target = Math.min(MAX_INDIRECT_DRAWS, Integer.highestOneBit(draws) * 2);
        if (target <= indirectDrawCapacity) {
            return;
        }
        vkDeviceWaitIdle(device());
        destroyDrawBatches();
        indirectDrawCapacity = target;
        drawCommandOffset = (long) target * DRAW_ORIGIN_BYTES;
        drawBatchBytes = drawCommandOffset + (long) target * DRAW_COMMAND_BYTES;
        try (MemoryStack stack = stackPush()) {
            createDrawBatches(stack);
            // Only binding 2 moved. Rewriting it directly also keeps this
            // independent of whether the atlas and lightmap are ready, which
            // updateDescriptors() requires and would otherwise skip — leaving
            // the sets pointing at buffers that were just destroyed.
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(drawDescriptorSets.length, stack);
            for (int i = 0; i < drawDescriptorSets.length; i++) {
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.get(0).buffer(drawBatchBuffers[i]).offset(0).range(drawCommandOffset);
                writes.get(i)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(drawDescriptorSets[i]).dstBinding(2).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bufferInfo);
            }
            vkUpdateDescriptorSets(device(), writes, null);
        }
        LOGGER.info("Indirect draw batches grown to {} draws ({} MiB across {} batches, {} memory)",
                target, String.format("%.1f", drawBatchBytes * drawBatchBuffers.length / (1024.0 * 1024.0)),
                drawBatchBuffers.length, indirectMemoryIsDeviceLocal ? "BAR" : "host");
    }

    /**
     * One permanently mapped uniform buffer per frame in flight.
     *
     * Host-visible and coherent rather than device-local: the contents are
     * rewritten by the CPU every frame and read once by the GPU, which is the
     * case staging would only add a copy to.
     */
    private void createFrameUniforms(MemoryStack stack) {
        VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(FRAME_UNIFORM_BYTES)
                .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
        LongBuffer pBuffer = stack.mallocLong(1);
        LongBuffer pMemory = stack.mallocLong(1);
        PointerBuffer ppData = stack.mallocPointer(1);
        for (int i = 0; i < framesInFlight; i++) {
            check(vkCreateBuffer(device(), bufferInfo, null, pBuffer), "vkCreateBuffer(frame uniforms)");
            frameUniformBuffers[i] = pBuffer.get(0);
            vkGetBufferMemoryRequirements(device(), frameUniformBuffers[i], req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(frame uniforms)");
            frameUniformMemories[i] = pMemory.get(0);
            check(vkBindBufferMemory(device(), frameUniformBuffers[i], frameUniformMemories[i], 0),
                    "vkBindBufferMemory(frame uniforms)");
            check(vkMapMemory(device(), frameUniformMemories[i], 0, FRAME_UNIFORM_BYTES, 0, ppData),
                    "vkMapMemory(frame uniforms)");
            frameUniformMapped[i] = ppData.get(0);
        }
    }

    /**
     * Writes this frame's matrix and fog where the shaders read them.
     *
     * {@code fogState} is copied straight through, and its eight floats land on
     * the two vec4s the shader declares: colour rgb, then the mode in the
     * alpha, then start, end and density. That is the same order the push
     * constants carried, so the shader reads the same bytes from a different
     * place.
     */
    private void writeFrameUniforms(float[] mvp, float[] fogState) {
        // Kept because the next frame will want to ask this one where a point
        // was. Copied rather than referenced: the array belongs to the game
        // side and is refilled every frame.
        System.arraycopy(mvp, 0, currentMvp, 0, 16);
        long base = frameUniformMapped[activeFrameSlot];
        if (base == 0L) {
            return;
        }
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(base + i * 4L, mvp[i]);
        }
        for (int i = 0; i < 8; i++) {
            MemoryUtil.memPutFloat(base + 64 + i * 4L, fogState[i]);
        }
        // vec4 lightInfo at 96: x is how many of the array below to read.
        MemoryUtil.memPutFloat(base + 96, dynamicLightCount);
        // y: the sun's own highlight on water and ice. Written once — the
        // first version of this wrote it here and then zeroed the padding a
        // line below, which made the whole effect unreachable while looking
        // exactly like a setting that did nothing.
        MemoryUtil.memPutFloat(base + 100, celestialGlint);
        // z: how much of a gradient the sky has, so that water can reflect the
        // sky the player actually has rather than a second one of its own.
        // The same number the sky pass is given, and it has to be the same
        // number: a reflection of a sky nobody is looking at is a wrong answer
        // that looks like an effect.
        MemoryUtil.memPutFloat(base + 104, skyGradient);
        MemoryUtil.memPutFloat(base + 108, 0.0f);
        for (int i = 0; i < dynamicLightCount * 4; i++) {
            MemoryUtil.memPutFloat(base + 112 + i * 4L, dynamicLights[i]);
        }
        // vec4 frameInfo at 624, straight after lights[32].
        //
        // The clock is read here rather than sent across the bridge. What
        // anything animated needs is a value that advances smoothly and never
        // jumps, and System.nanoTime is that on this side already; routing it
        // through the game would add a bridge call per frame and tie the
        // animation to the game thread for nothing. Reduced modulo an hour so
        // the float keeps its precision however long the session runs.
        float seconds = (float) (((System.nanoTime() - startedNanos) / 1_000_000L) % 3_600_000L)
                / 1000.0f;
        // Held still when asked, which is what makes two pictures comparable.
        // Everything animated here reads this one number, so a frame taken
        // twenty seconds after another differs in every blade of grass and
        // every ripple — differences that swamp whatever the two frames were
        // being compared for. Pinning it is a development switch and costs a
        // branch that predicts perfectly.
        if (frozenSeconds >= 0.0f) {
            seconds = frozenSeconds;
        }
        MemoryUtil.memPutFloat(base + 624, seconds);
        MemoryUtil.memPutFloat(base + 628, directionalDynamicLight);
        // z: whether the material buffer is bound at all. When it is not, the
        // second vertex binding is the geometry buffer read at stride one, and
        // what it hands back is meaningless.
        MemoryUtil.memPutFloat(base + 632, materialsBound ? 1.0f : 0.0f);
        MemoryUtil.memPutFloat(base + 636, showMaterials ? 1.0f : 0.0f);
        // vec4 heightFog at 640.
        MemoryUtil.memPutFloat(base + 640, heightFogStrength);
        MemoryUtil.memPutFloat(base + 644, heightFogFalloff);
        // z: how many of the sprite rectangles below are in use.
        MemoryUtil.memPutFloat(base + 648, materialSpriteCount);
        // w: how much of a water surface turns into sky at a grazing angle.
        MemoryUtil.memPutFloat(base + 652, waterReflection);
        // The sprite table at 656: two vec4s each, rectangle then material.
        for (int i = 0; i < materialSpriteCount * 8; i++) {
            MemoryUtil.memPutFloat(base + 656 + i * 4L, materialSprites[i]);
        }
        // vec4 water at 912, straight after the sprite table's sixteen vec4s.
        MemoryUtil.memPutFloat(base + 912, waterWaves);
        // yz: where the camera is on the wave lattice. The reduction happens
        // here, in double precision, because that is the only place it can:
        // world coordinates in this game reach tens of millions, and a float
        // stops being able to separate one block from the next long before
        // that. What crosses into the shader is a remainder under sixteen.
        MemoryUtil.memPutFloat(base + 916, (float) waveWrap(viewWorldX));
        MemoryUtil.memPutFloat(base + 920, (float) waveWrap(viewWorldZ));
        // w: how far a plant leans away from where the game put it.
        MemoryUtil.memPutFloat(base + 924, foliageSway);
        // How much of the reflection is traced against what is on screen
        // rather than taken from the fog colour.
        MemoryUtil.memPutFloat(base + 928, screenReflections);
        MemoryUtil.memPutFloat(base + 932, showReflections ? 1.0f : 0.0f);
        // The two numbers that turn a stored depth back into a distance. The
        // reflection needs them to tell "the ray crossed this surface" from
        // "the ray sailed past a long way behind it", and those two are the
        // same reading of the depth buffer without them.
        readProjectionPlanes();
        MemoryUtil.memPutFloat(base + 936, nearPlane);
        MemoryUtil.memPutFloat(base + 940, farPlane);
        // vec4 sun at 944: which way it is, and how much of a shadow to
        // believe. Zero strength is the whole of the off switch — the ray is
        // never initialised and the pipeline that could trace one is not even
        // bound.
        MemoryUtil.memPutFloat(base + 944, sunDirection[0]);
        MemoryUtil.memPutFloat(base + 948, sunDirection[1]);
        MemoryUtil.memPutFloat(base + 952, sunDirection[2]);
        // The raw strength: whether the sun is up is decided in the shader,
        // where the fade with its height already lives. Two places deciding
        // the same thing is how one of them ends up disagreeing.
        MemoryUtil.memPutFloat(base + 956, tracingWanted() ? sunShadowStrength() : 0.0f);
        // vec4 sunParams at 960: how far a shadow ray may go, and how much sky
        // light a fully shadowed surface keeps.
        MemoryUtil.memPutFloat(base + 960, VkRayTracing.radiusBlocks());
        MemoryUtil.memPutFloat(base + 964, SHADOW_SKY_KEPT);
        // How wide the sun is made to be, in radians of half-angle. The real
        // one is about a quarter of a degree; this goes far past that, because
        // what the setting is really choosing is how much of the staircase to
        // trade for dither.
        MemoryUtil.memPutFloat(base + 968,
                clampPercent(intProperty("vulkanmod112.shadowSoftness", 35)) * MAX_SUN_SPREAD);
        // How many moving lights a fragment may ask about. Nothing at all when
        // there is no structure to ask.
        MemoryUtil.memPutFloat(base + 972, tracingWanted() ? tracedLights() : 0.0f);
        // vec4 lightShadow at 976: how wide a torch's flame is treated as
        // being. Tied to the same softness the sun uses, because a player who
        // wants one edge soft wants the other soft too, and two sliders for one
        // preference is one slider too many.
        MemoryUtil.memPutFloat(base + 976,
                clampPercent(intProperty("vulkanmod112.lightSoftness", 30)) * MAX_LIGHT_RADIUS);
        // How much of the game's own block light to give up. Only where there
        // is something to give it up for.
        MemoryUtil.memPutFloat(base + 980, tracingWanted() && tracedLights() > 0
                ? clampPercent(intProperty("vulkanmod112.tracedBlockLight", 0)) : 0.0f);
        // How far the dither pattern is turned this frame. Zero unless frames
        // are being averaged — a pattern that moves under an eye with nothing
        // averaging it is a shadow edge that crawls, which is worse than the
        // grain the turning was for. See ditherValue in terrain.frag.
        MemoryUtil.memPutFloat(base + 984, ditherTurn);
        // How much the surface of water bends what is under it. Nothing at all
        // unless the scene images are bound — without them the sampler holds
        // the block atlas, and a riverbed made of atlas is worse than a
        // riverbed that does not move.
        MemoryUtil.memPutFloat(base + 988, sceneWanted() ? waterRefraction : 0.0f);
        // vec4 surface at 992.
        //
        // x: how much sky a sheet of ice gathers. Ice is drawn in the same
        // translucent pass as water and has carried a material tag of its own
        // since that pass was written, which nothing ever read.
        MemoryUtil.memPutFloat(base + 992, iceShine);
        // y: how much the bed under shallow water is banded by the surface
        // above it. Nothing at all unless refraction is fetching that bed —
        // there is no other picture of it to brighten.
        MemoryUtil.memPutFloat(base + 996,
                sceneWanted() && waterRefraction > 0.0f ? waterCaustics : 0.0f);
        // z: how wet an upward face is, which is the setting and the weather
        // multiplied. One number rather than two, because neither is any use
        // to the shader without the other, and a fragment should not have to
        // ask twice.
        MemoryUtil.memPutFloat(base + 1000, wetSurfaces * rainStrength);
        // w: how far the fog leans towards the sun's colour.
        MemoryUtil.memPutFloat(base + 1004, sunHaze);
        // vec4 world at 1008.
        //
        // x: how far the camera is above this world's sea level. The height fog
        // needs the fragment's own height, and a fragment only knows where it
        // is relative to the eye — this one number turns the second into the
        // first, and it is a number rather than a coordinate, so it stays exact
        // however far out the world runs.
        MemoryUtil.memPutFloat(base + 1008, (float) (viewWorldY - seaLevel));
        // How much light a leaf is allowed to let past a shadow ray, 0 for the
        // old behaviour where every quad stopped it like stone.
        MemoryUtil.memPutFloat(base + 1012, leafShadows);
        // Whether the target this shader writes into can hold more than one.
        // Read from the format that was actually granted rather than from the
        // setting: the driver is allowed to refuse, and a highlight aimed at
        // a headroom that was refused clips to a flat white patch, which is
        // the exact complaint the lower ceiling exists to answer.
        MemoryUtil.memPutFloat(base + 1016,
                colourFormat == VK_FORMAT_R16G16B16A16_SFLOAT ? 1.0f : 0.0f);
    }

    /**
     * The game's clouds, as they are this frame: sheet, height, drift.
     *
     * Zero for the sheet means there is nothing overhead to cast a shadow —
     * clouds switched off, no world, or the sheet not loaded yet — and the
     * pass reads that as "no shadow" rather than needing a flag of its own.
     */
    private volatile int cloudTexture;
    private volatile float cloudHeight;
    private volatile float cloudDrift;

    synchronized void setCloudState(int glTexture, float height, float driftBlocks) {
        cloudTexture = glTexture;
        cloudHeight = height;
        cloudDrift = driftBlocks;
    }

    /** How hard it is raining, 0 to 1; see VulkanBridge.updateWeather. */
    private volatile float rainStrength;

    synchronized void setRainStrength(float value) {
        rainStrength = value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }

    /**
     * Where this world puts the sea, which is what the height fog measures from.
     *
     * Sixty-three in the overworld and whatever a dimension mod says elsewhere,
     * so the fog pools at the bottom of that world rather than at the bottom of
     * the overworld's.
     */
    private volatile float seaLevel = 63.0f;

    synchronized void setSeaLevel(int level) {
        seaLevel = level;
    }

    /**
     * The most of its history a pixel is allowed to keep.
     *
     * Not one. At a weight of one a pixel takes nothing new ever, so the world
     * freezes into whatever it looked like when the effect came on; at 0.95 it
     * takes a twentieth of each frame, which settles in about a third of a
     * second and still answers a change in about the same time. The slider maps
     * onto this rather than onto the whole range, so the top of it is the most
     * smoothing that still leaves a working picture.
     */
    private static final float MAX_HISTORY_WEIGHT = 0.95f;

    /**
     * Advances the dither, or holds it still.
     *
     * The step is the golden ratio's fractional part. Successive multiples of
     * it are as evenly spread over the circle as any sequence can be, which is
     * what makes the first handful of frames already look like an average
     * rather than like two alternating pictures.
     */
    private void advanceDither() {
        ditherTurn = accumStrength > 0.0f && !accumFailed && tracingWanted()
                ? (float) ((frameCounter * 0.6180339887498949) % 1.0)
                : 0.0f;
    }

    /**
     * How dark a fully shadowed surface goes, as a fraction of its sky light.
     *
     * Not a setting. What a shadow should look like in this game is decided by
     * how the light map is built, not by taste: a surface in shade is a surface
     * the sky reaches less, and vanilla's own range from open sky to none is
     * what this is a fraction of. Left adjustable it would be the first thing
     * turned to zero, and a black shadow is the one thing that would make this
     * look pasted on.
     */
    private static final float SHADOW_SKY_KEPT = 0.45f;

    /**
     * The widest the sun may be made, in radians of half-angle.
     *
     * Three degrees, which is a dozen times the real sun. A physically sized
     * source gives a penumbra of a few centimetres at these distances — which
     * is to say a hard edge and the staircase back again. What this number is
     * really for is how far a single ray may be thrown off course, and past
     * about this much the dither stops reading as a soft edge and starts
     * reading as speckle.
     */
    private static final float MAX_SUN_SPREAD = 0.052f;

    /**
     * How wide a moving light may be treated as being, in blocks.
     *
     * Three quarters of a block at full softness, which is several times a
     * torch flame. A flame-sized source gives a border a few centimetres wide,
     * and a few centimetres at this resolution is the hard edge again — so
     * this, like the sun's width, is really choosing how far a single ray may
     * be thrown off rather than describing anything.
     */
    private static final float MAX_LIGHT_RADIUS = 0.75f;

    /** Camera-relative, which for this axis-aligned frame is world direction. */
    private final float[] sunDirection = {0.0f, 1.0f, 0.0f};

    synchronized void setSunDirection(float[] direction) {
        if (direction != null && direction.length >= 3) {
            System.arraycopy(direction, 0, sunDirection, 0, 3);
        }
    }

    private static float sunShadowStrength() {
        return clampPercent(intProperty("vulkanmod112.sunShadows", 0));
    }

    private static int tracedLights() {
        return Math.max(0, Math.min(8, intProperty("vulkanmod112.tracedLights", 2)));
    }

    /**
     * Whether this frame may trace anything at all.
     *
     * Every term is a thing that can be absent on a real machine: the card may
     * not trace, the structures may not have been built yet, both settings may
     * be at zero. A missing one costs the effect and nothing else.
     *
     * Deliberately not gated on the sun being up. A torch casts its shadow at
     * midnight in a cave, which is where it matters most, and tying the whole
     * tracing path to daylight would have taken that with it.
     */
    private boolean tracingWanted() {
        return rayTracing != null
                && ctx.isRayQuerySupported()
                && rayTracing.topLevel(activeFrameSlot) != 0
                && (sunShadowStrength() > 0.0f || tracedLights() > 0);
    }

    /** The wave lattice from terrain.frag, which this side has to agree with. */
    private static final double WAVE_LATTICE = 16.0;

    private static double waveWrap(double world) {
        return world - Math.floor(world / WAVE_LATTICE) * WAVE_LATTICE;
    }

    /**
     * Shader settings read once a frame from the properties the game side sets.
     *
     * Both are plain numbers in the frame's uniform buffer rather than
     * specialization constants, which is the whole point: a slider that
     * rebuilds a pipeline is a slider that stutters, and neither of these
     * changes what the shader costs enough to be worth a second variant of it.
     */
    /**
     * The settings, read only when they have moved.
     *
     * The two halves of this mod are in different classloaders and share
     * nothing but system properties, so every effect's setting arrives as one.
     * Reading them was done once a frame — about forty-five calls, each of
     * which takes the lock on the global property table, on the one thread that
     * has to finish the frame. Settings change when somebody moves a slider,
     * which is several thousand frames apart.
     *
     * So the settings side stamps a version whenever it publishes, and this
     * reads that one property and stops there when the number has not moved.
     * The first call cannot match, so a session always starts with a full read.
     *
     * The dither is advanced whatever happens: it is not a setting but the
     * frame counter the accumulation pass turns into a soft edge, and freezing
     * it would average a still pattern into itself.
     */
    private void refreshShaderSettings() {
        settingsFrames++;
        String version = System.getProperty(SETTINGS_VERSION_KEY);
        if (version == null || !version.equals(lastSettingsVersion)) {
            lastSettingsVersion = version;
            settingsReads++;
            readShaderSettings();
        }
        advanceDither();
    }

    /** Whether the settings have been read at all yet, and which stamp they were. */
    private String lastSettingsVersion = NEVER_READ;
    private long settingsFrames;
    private long settingsReads;

    /** A value the settings side will never publish, so the first read always happens. */
    private static final String NEVER_READ = "never read";
    private static final String SETTINGS_VERSION_KEY = "vulkanmod112.settingsVersion";

    private void readShaderSettings() {
        directionalDynamicLight =
                clampPercent(intProperty("vulkanmod112.directionalLight", 100));
        heightFogStrength = clampPercent(intProperty("vulkanmod112.heightFog", 0));
        // The setting is a depth in blocks; the shader wants a rate per block.
        // Two e-foldings over that depth, so the drop the slider names is where
        // the fog has taken about six sevenths of what its strength allows —
        // near enough to "this is where it is as thick as it gets" to set by
        // eye, which is how the slider is going to be used.
        int depth = Math.max(1, intProperty("vulkanmod112.heightFogDepth", 24));
        heightFogFalloff = 2.0f / depth;
        showMaterials = "true".equals(System.getProperty("vulkanmod112.showMaterials"));
        showOcclusion = "true".equals(System.getProperty("vulkanmod112.showOcclusion"));
        showMotion = "true".equals(System.getProperty("vulkanmod112.showMotion"));
        motionOverWorld = "true".equals(System.getProperty("vulkanmod112.motionOverWorld"));
        showReflections = "true".equals(System.getProperty("vulkanmod112.showReflections"));
        showAccumulation = "true".equals(System.getProperty("vulkanmod112.showAccumulation"));
        frameThrottled = "true".equals(System.getProperty("vulkanmod112.frameThrottled"));
        // The setting names how much of the history a still pixel keeps, and
        // the top of the slider is not 1.0: a pixel that keeps all of its
        // history never takes anything new, so the world would stop updating.
        accumStrength = clampPercent(intProperty("vulkanmod112.temporalAccumulation", 60))
                * MAX_HISTORY_WEIGHT;
        waterReflection = clampPercent(intProperty("vulkanmod112.waterReflection", 0));
        waterWaves = clampPercent(intProperty("vulkanmod112.waterWaves", 0));
        foliageSway = clampPercent(intProperty("vulkanmod112.foliageSway", 0));
        toneStrength = clampPercent(intProperty("vulkanmod112.sceneTone", 0));
        toneWarmth = clampPercent(intProperty("vulkanmod112.sceneWarmth", 50)) * 2.0f - 1.0f;
        bloomStrength = clampPercent(intProperty("vulkanmod112.bloom", 0));
        aoStrength = clampPercent(intProperty("vulkanmod112.ambientOcclusion", 0));
        contactShadows = clampPercent(intProperty("vulkanmod112.contactShadows", 0));
        creatureLight = clampPercent(intProperty("vulkanmod112.creatureLight", 0));
        showCreatureLight = Boolean.parseBoolean(
                System.getProperty("vulkanmod112.showCreatureLight", "false"));
        cloudShadows = clampPercent(intProperty("vulkanmod112.cloudShadows", 0));
        godRays = clampPercent(intProperty("vulkanmod112.godRays", 0));
        hdrFrame = Boolean.parseBoolean(
                System.getProperty("vulkanmod112.hdrFrameActive", "false"));
        exposure = clampPercent(intProperty("vulkanmod112.exposure", 50));
        skyGradient = clampPercent(intProperty("vulkanmod112.skyGradient", 0));
        sceneOcclusion = Boolean.parseBoolean(
                System.getProperty("vulkanmod112.sceneOcclusion", "false"));
        leafShadows = clampPercent(intProperty("vulkanmod112.leafShadows", 0));
        aoRadius = Math.max(1, Math.min(6, intProperty("vulkanmod112.aoRadius", 2)));
        iceShine = clampPercent(intProperty("vulkanmod112.iceShine", 0));
        waterCaustics = clampPercent(intProperty("vulkanmod112.waterCaustics", 0));
        wetSurfaces = clampPercent(intProperty("vulkanmod112.wetSurfaces", 0));
        sunHaze = clampPercent(intProperty("vulkanmod112.sunHaze", 0));
        celestialGlint = clampPercent(intProperty("vulkanmod112.celestialGlint", 0));
        float wantedRefraction = clampPercent(intProperty("vulkanmod112.waterRefraction", 0));
        float wantedReflections = clampPercent(intProperty("vulkanmod112.screenReflections", 0));
        // Both are read, then both are stored, and only then is the question
        // asked. Storing one and asking with the other still in its old value
        // is how turning reflections on stopped rewriting the descriptor sets —
        // and a set that was never rewritten still holds the block atlas, so
        // the water reflected every texture in the game at once. Two lines in
        // the wrong order, and the symptom named nothing that was near them.
        boolean sceneWasWanted = sceneWanted();
        waterRefraction = wantedRefraction;
        screenReflections = wantedReflections;
        if (sceneWanted() != sceneWasWanted) {
            // What the water is allowed to look at changed. Rewriting the sets
            // stops the device, so it happens here — on the change — and never
            // in a frame that did not ask for it.
            reflectionBindingsDirty = true;
        }
    }

    /**
     * Whether the water is allowed to look at the world behind it.
     *
     * Two effects want the same two images and either of them is reason enough
     * to bind them. Asked as one question because it is one: without them
     * these samplers hold the block atlas, which reflections and refraction
     * would both happily read as though it were the world.
     */
    /**
     * Whether anything in the terrain shader needs the picture of the world as
     * it stood a moment ago.
     *
     * When nothing does, both of those samplers are pointed at the block atlas
     * instead — there has to be something bound, and the atlas is already
     * there. Which means anything that reads them without being counted here
     * reads block textures and calls the answer a depth: the sun glint's
     * occlusion march did exactly that, and was being blanked in a pattern
     * taken from the colour of grass.
     */
    private boolean sceneWanted() {
        return screenReflections > 0.0f || waterRefraction > 0.0f;
    }

    /** How much the water bends what is seen through it; 0 = off. */
    private float waterRefraction;

    /** How much sky a sheet of ice gathers; 0 = off. */
    private float iceShine;

    /** How hard the bed under water is banded by the surface; 0 = off. */
    private float waterCaustics;

    /** How much rain wets an upward face; 0 = off. Multiplied by the weather. */
    private float wetSurfaces;

    /** How far the fog leans towards the sun's colour; 0 = off. */
    private float sunHaze;

    /** The sun's or moon's own highlight on water and ice; 0 = off. */
    private float celestialGlint;

    private static float clampPercent(int value) {
        return Math.max(0, Math.min(100, value)) / 100.0f;
    }

    private static int intProperty(String name, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * The light sources for the coming frame. Copied rather than referenced:
     * the array on the other side of the bridge belongs to the game thread and
     * is refilled every frame.
     */
    void setDynamicLights(float[] lights, int count) {
        int clamped = Math.max(0, Math.min(count, MAX_DYNAMIC_LIGHTS));
        System.arraycopy(lights, 0, dynamicLights, 0, clamped * 4);
        dynamicLightCount = clamped;
    }

    /** What the mirrored atlas was built for, so a change to it can be noticed. */
    private boolean samplerFlatColours;
    /** The game's name for the atlas, kept so the mirror can be rebuilt from it. */
    private int atlasGlId;

    /**
     * Rebuilds the atlas sampler when the flat-colour setting has moved.
     *
     * The setting decides one number inside a sampler, and a sampler cannot be
     * edited — it is made once and handed to a descriptor. Checked here, on the
     * frame path, because the alternative is what shipped first: the value was
     * read where the sampler is created, the sampler already existed by the time
     * anyone could press the switch, and the setting did nothing at all until
     * the world was reloaded. A switch that needs a world reload to be believed
     * is a switch nobody trusts.
     *
     * The comparison is against what the sampler was actually built for rather
     * than against a previous reading of the setting, so this stays right if
     * something else rebuilds the sampler.
     */
    private void refreshSamplerIfNeeded() {
        if (atlasImage == 0 || atlasGlId == 0 || samplerFlatColours == flatBlockColours()) {
            return;
        }
        // The whole copy, not the sampler. What the setting decides now is how
        // much of the game's mip chain is mirrored at all, and an image cannot
        // grow levels it was not created with any more than a sampler can be
        // edited. This is the same work a resource pack change does, on a
        // switch nobody flicks twice a second.
        updateAtlas(atlasGlId);
        LOGGER.info("Block texture sampling switched to {}: {} of the atlas' {} mip level(s) mirrored",
                flatBlockColours() ? "one flat colour per face" : "the full atlas",
                atlasLevels, atlasLevels + atlasBaseLevel);
    }

    /**
     * Points the descriptor sets at the current images, stopping the device
     * first.
     *
     * The wait is not optional in general: a set being rewritten while a frame
     * in flight is reading it is undefined, and the frames in flight are
     * exactly what makes that likely rather than theoretical. It is separated
     * from the writing itself only so that a caller which has already stopped
     * the device does not stop it again.
     */
    private void updateDescriptors() {
        if (descriptorSet == 0 || atlasImage == 0 || lightmapImage == 0) {
            return;
        }
        vkDeviceWaitIdle(device());
        writeDescriptors();
    }

    /** The writing, with no wait. Only safe where the device is already idle. */
    private void writeDescriptors() {
        if (descriptorSet == 0 || atlasImage == 0 || lightmapImage == 0) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer atlasInfo = VkDescriptorImageInfo.calloc(1, stack);
            atlasInfo.get(0)
                    .sampler(atlasSampler)
                    .imageView(atlasView)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkDescriptorImageInfo.Buffer lightmapInfo = VkDescriptorImageInfo.calloc(1, stack);
            lightmapInfo.get(0)
                    .sampler(lightmapSampler)
                    .imageView(lightmapView)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            // Only the translucent set is shown the scene. The sets are laid
            // out one per layer per frame in flight, so which one that is falls
            // straight out of the index — no separate layout, no second pool.
            VkDescriptorImageInfo.Buffer sceneColorInfo = VkDescriptorImageInfo.calloc(1, stack);
            boolean sceneReady = colorView != 0 && depthView != 0 && sceneWanted();
            sceneColorInfo.get(0)
                    .sampler(sceneSampler)
                    .imageView(sceneReady ? colorView : atlasView)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkDescriptorImageInfo.Buffer sceneDepthInfo = VkDescriptorImageInfo.calloc(1, stack);
            sceneDepthInfo.get(0)
                    .sampler(sceneSampler)
                    .imageView(sceneReady ? depthView : atlasView)
                    .imageLayout(sceneReady
                            ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL
                            : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(drawDescriptorSets.length * 6, stack);
            for (int i = 0; i < drawDescriptorSets.length; i++) {
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.get(0).buffer(drawBatchBuffers[i]).offset(0).range(drawCommandOffset);
                // Three sets per frame in flight, one per layer, and they all
                // read the same frame constants.
                VkDescriptorBufferInfo.Buffer frameInfo = VkDescriptorBufferInfo.calloc(1, stack);
                frameInfo.get(0).buffer(frameUniformBuffers[i / BATCHES_PER_FRAME]).offset(0).range(FRAME_UNIFORM_BYTES);
                // Only while the effect is actually on. With it off these two
                // point at the atlas like every other pass, which puts the
                // whole arrangement back to what it was before reflections
                // existed — nothing bound that the frame is also using, and
                // nothing for a driver to object to. The sets are rewritten
                // when the setting changes, which is rare enough to afford it.
                boolean waterSet = sceneWanted()
                        && (i % BATCHES_PER_FRAME) == LAYER_TRANSLUCENT;
                int write = i * 6;
                writes.get(write)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(drawDescriptorSets[i]).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(atlasInfo);
                writes.get(write + 1)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(drawDescriptorSets[i]).dstBinding(1).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(lightmapInfo);
                writes.get(write + 2)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(drawDescriptorSets[i]).dstBinding(2).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bufferInfo);
                writes.get(write + 3)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(drawDescriptorSets[i]).dstBinding(3).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(frameInfo);
                writes.get(write + 4)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(drawDescriptorSets[i]).dstBinding(4).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(waterSet ? sceneColorInfo : atlasInfo);
                writes.get(write + 5)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(drawDescriptorSets[i]).dstBinding(5).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(waterSet ? sceneDepthInfo : atlasInfo);
            }
            vkUpdateDescriptorSets(device(), writes, null);
        }
        // Slot 0 of the sprite textures is the block atlas itself — the image
        // the terrain already draws from, not a copy of it. Block-shaped
        // particles are a handful of quads a second; a second atlas for them
        // would be sixteen megabytes of video memory on a high-resolution
        // resource pack.
        writeSpriteSet(0, atlasView, atlasSampler);
    }

    /**
     * Whether the shared colour targets carry sixteen bits a channel this
     * session, decided once and never changed.
     *
     * Once, because the format is written into the render passes and every
     * pipeline built against them: changing it later would mean rebuilding all
     * of that mid-frame, which is a great deal of machinery for a setting
     * nobody flips twice.
     *
     * What it buys is a ceiling. Everything this shader computes about water —
     * the reflection, the refraction, the caustics, the glint — is written into
     * this target and read back out of it by the same shader, and at eight bits
     * that round trip is where the banding comes from. This is one half of
     * high dynamic range and the game's own frame is the other: a highlight
     * has to survive reflection and refraction here, and then the glow and
     * the tone curve there. Both halves answer to the same switch, because
     * buying the headroom and losing it one step later looks exactly like
     * the setting not working.
     *
     * Asked of the driver rather than assumed. An exportable image is not the
     * same question as an ordinary one — it is the pair of drivers that has to
     * agree — so a refusal here is expected on some machines and answered by
     * staying where we were, with a line in the log saying which it was.
     */
    private int colourFormat = VK_FORMAT_R8G8B8A8_UNORM;
    private int glColourFormat = org.lwjgl.opengl.GL11.GL_RGBA8;

    private int colourBytesPerPixel() {
        return colourFormat == VK_FORMAT_R16G16B16A16_SFLOAT ? 8 : 4;
    }

    private void decideColourDepth(MemoryStack stack) {
        // The settings switch and the key are one question asked twice. The
        // switch is what a player turns on and it means the whole of high
        // dynamic range, both halves of it: this target, so that a highlight
        // survives reflection and refraction, and the game's own frame, so
        // that it survives the glow and the tone curve. Keeping them apart
        // would let somebody buy the headroom and lose it one step later,
        // which is indistinguishable from the setting not working. The key
        // stays because it is how this was tried before there was a switch,
        // and because it can be given to somebody whose driver is suspect
        // without walking them through a menu.
        boolean wantedBySwitch = Boolean.parseBoolean(
                System.getProperty("vulkanmod112.hdrFrame", "false"));
        boolean wantedByKey = Boolean.parseBoolean(
                System.getProperty("vulkanmod112.hdrTargets", "false"));
        if (!wantedBySwitch && !wantedByKey) {
            return;
        }
        int wanted = VK_FORMAT_R16G16B16A16_SFLOAT;
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        org.lwjgl.vulkan.VkImageFormatProperties props =
                org.lwjgl.vulkan.VkImageFormatProperties.calloc(stack);
        int result = vkGetPhysicalDeviceImageFormatProperties(ctx.getPhysicalDevice(), wanted,
                VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL, usage, 0, props);
        if (result != VK_SUCCESS) {
            LOGGER.warn("Sixteen-bit colour targets were asked for and this device will not "
                    + "make one ({}); staying at eight bits", result);
            return;
        }
        colourFormat = wanted;
        glColourFormat = GL30C.GL_RGBA16F;
        LOGGER.info("Colour targets are sixteen bits a channel this session");
    }

    private void createRenderPass(MemoryStack stack) {
        VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(2, stack);
        attachments.get(0)
                .format(colourFormat)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(sharedLayout());
        attachments.get(1)
                .format(depthFormat(stack))
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(sharedLayout());

        VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
        colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

        VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
        subpass.get(0)
                .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                .colorAttachmentCount(1)
                .pColorAttachments(colorRef)
                .pDepthStencilAttachment(depthRef);

        VkRenderPassCreateInfo rpInfo = VkRenderPassCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                .pAttachments(attachments)
                .pSubpasses(subpass);
        LongBuffer pRenderPass = stack.mallocLong(1);
        check(vkCreateRenderPass(device(), rpInfo, null, pRenderPass), "vkCreateRenderPass(terrain)");
        renderPass = pRenderPass.get(0);

        createTranslucentRenderPass(stack);
    }

    /**
     * The pass the translucent layer is drawn in.
     *
     * Two things separate it from the opaque one. Its colour attachment starts
     * cleared to fully transparent, because what it produces is composited over
     * a frame OpenGL has meanwhile drawn entities into rather than replacing
     * it. And its depth attachment is loaded rather than cleared, and never
     * stored: the layer is depth-tested against what is already there and
     * writes nothing back, which is what vanilla does too — {@code
     * depthMask(false)} right before it asks for the layer.
     */
    private void createTranslucentRenderPass(MemoryStack stack) {
        VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(2, stack);
        attachments.get(0)
                .format(colourFormat)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(sharedLayout());
        attachments.get(1)
                .format(depthFormat(stack))
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                // Not what the opaque pass left it in — what OpenGL says it is
                // handing over, which is a different thing and was the source
                // of a hang.
                //
                // Between the two passes the GL side writes the game's depth
                // into this image as an attachment and then signals, naming
                // GL_LAYOUT_DEPTH_STENCIL_ATTACHMENT_EXT. That name is the
                // whole of the agreement: a layout is how the card has packed
                // and compressed the pixels, and the two APIs exchange it in
                // the semaphore operation and nowhere else. Claiming here that
                // the image arrives as a shader-read texture while OpenGL
                // states it left as an attachment is not a mismatch of
                // paperwork — it is reading one compression scheme as another.
                .initialLayout(depthHandoffLayout())
                .finalLayout(sharedLayout());

        VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
        colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        // Writable depth, for creatures. This is the whole of the fix for them:
        // the layout of a depth attachment is declared per subpass rather than
        // per pass, so one pass can hold both answers.
        VkAttachmentReference creatureDepthRef = VkAttachmentReference.calloc(stack)
                .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        // Read-only depth: the test runs, nothing is written.
        VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);

        // Two subpasses, and the order is the point.
        //
        // Subpass 0 draws creatures with depth writes on, so a mob hides the mob
        // behind it and a head hides the back of its own skull — which the first
        // attempt at drawing entities could not do at all, and looked exactly
        // like that. Subpass 1 is everything this pass drew before, unchanged,
        // with the depth attachment read-only: the water shader samples that
        // same image for its reflections, and an image cannot be written as an
        // attachment and read as a texture in one subpass.
        //
        // The gain is not only that mobs occlude each other. Water is now tested
        // against depth that has creatures in it, so a mob under the surface is
        // under it because it is, rather than because of the order the two were
        // drawn in; particles stop showing through mobs for the same reason.
        VkSubpassDescription.Buffer subpasses = VkSubpassDescription.calloc(2, stack);
        subpasses.get(SUBPASS_CREATURES)
                .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                .colorAttachmentCount(1)
                .pColorAttachments(colorRef)
                .pDepthStencilAttachment(creatureDepthRef);
        subpasses.get(SUBPASS_TRANSLUCENT)
                .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                .colorAttachmentCount(1)
                .pColorAttachments(colorRef)
                .pDepthStencilAttachment(depthRef);

        // What the second subpass has to wait for, spelled out twice because it
        // reads the same image two different ways: as a depth attachment it
        // tests against, and as a texture the water shader samples. The colour
        // attachment is in here as well — subpass 0 blends creatures into it and
        // subpass 1 blends over them, and blending is a read as much as a write.
        //
        // Not BY_REGION: the reflection march walks across the screen, so a
        // fragment of water reads depth at pixels other than its own, and a
        // by-region promise would be a lie the driver is entitled to believe.
        VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack);
        dependency.get(0)
                .srcSubpass(SUBPASS_CREATURES)
                .dstSubpass(SUBPASS_TRANSLUCENT)
                .srcStageMask(VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT
                        | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                        | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                        | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                .srcAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                        | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                        | VK_ACCESS_SHADER_READ_BIT
                        | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                        | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

        VkRenderPassCreateInfo rpInfo = VkRenderPassCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                .pAttachments(attachments)
                .pSubpasses(subpasses)
                .pDependencies(dependency);
        LongBuffer pRenderPass = stack.mallocLong(1);
        check(vkCreateRenderPass(device(), rpInfo, null, pRenderPass),
                "vkCreateRenderPass(translucent)");
        translucentRenderPass = pRenderPass.get(0);
    }

    /**
     * Which pipeline draws a vanilla render layer, or -1 if this renderer does
     * not draw that layer at all.
     */
    private static int pipelineForLayer(int layerOrdinal) {
        if (layerOrdinal < 0 || layerOrdinal >= LAYER_PIPELINE.length) {
            return -1;
        }
        int variant = LAYER_PIPELINE[layerOrdinal];
        return variant < TERRAIN_PIPELINES.length ? variant : -1;
    }

    private void createPipeline(MemoryStack stack) {
        createPipelineSet(stack, false);
        if (ctx.isRayTracingEnabled() && ctx.isRayQuerySupported()) {
            // A second set of exactly the same pipelines, differing only in
            // which build of the fragment shader they carry. Two sets rather
            // than one with a switch inside: the tracing build names an
            // acceleration structure, which is a capability the driver either
            // has or refuses the pipeline for — and this renderer has to keep
            // working on the cards that refuse.
            createPipelineSet(stack, true);
        }
    }

    private void createPipelineSet(MemoryStack stack, boolean rayQuery) {
        long vertModule = createShaderModule(stack, VertexLayout.isCompact()
                ? "vulkanmod112/shaders/terrain_compact.vert.spv"
                : "vulkanmod112/shaders/terrain.vert.spv");
        long fragModule = createShaderModule(stack, rayQuery
                ? "vulkanmod112/shaders/terrain_rt.frag.spv"
                : "vulkanmod112/shaders/terrain.frag.spv");

        ByteBuffer entryPoint = stack.UTF8("main");

        // Either vanilla's twenty-eight bytes mirrored unchanged, or the
        // sixteen-byte packing described in VertexLayout. The two are different
        // enough that the vertex shader is built twice, because a shader's
        // declared inputs have to match the attributes the pipeline supplies.
        boolean packed = VertexLayout.isCompact();
        VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(2, stack);
        binding.get(0).binding(0).stride(VertexLayout.stride()).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        // One byte per vertex, in a buffer of its own, because there is nowhere
        // in the vertex to put it — vanilla's is mirrored unchanged and the
        // packed one is full. A second binding costs nothing here because the
        // indirect draw's vertexOffset applies to every bound buffer, so the
        // same per-chunk number already lands on the right materials.
        binding.get(1).binding(1).stride(1).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        VkVertexInputAttributeDescription.Buffer attrs =
                VkVertexInputAttributeDescription.calloc(packed ? 4 : 5, stack);
        if (packed) {
            // Position and both light values in one fetch. Signed integers
            // rather than a normalised float: the fourth component is two
            // numbers side by side, and normalising would fold them into one.
            attrs.get(0).location(0).binding(0).format(VK_FORMAT_R16G16B16A16_SINT).offset(0);
            attrs.get(1).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(8);
            attrs.get(2).location(2).binding(0).format(VK_FORMAT_R16G16_UNORM).offset(12);
            attrs.get(3).location(4).binding(1).format(VK_FORMAT_R8_UINT).offset(0);
        } else {
            attrs.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            attrs.get(1).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(12);
            attrs.get(2).location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(16);
            attrs.get(3).location(3).binding(0).format(VK_FORMAT_R16G16_SSCALED).offset(24);
            attrs.get(4).location(4).binding(1).format(VK_FORMAT_R8_UINT).offset(0);
        }
        VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(binding)
                .pVertexAttributeDescriptions(attrs);

        VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

        VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                .viewportCount(1)
                .scissorCount(1);

        // Vanilla renders terrain with backface culling (GL front = CCW). Our
        // image is vertically flipped relative to GL, which mirrors winding:
        // front faces arrive clockwise. -Dvulkanmod112.cull=false to disable.
        boolean cull = !"false".equals(System.getProperty("vulkanmod112.cull"));
        VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .polygonMode(VK_POLYGON_MODE_FILL)
                .cullMode(cull ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
                .frontFace(VK_FRONT_FACE_CLOCKWISE)
                .lineWidth(1.0f);
        VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

        // Depth and blend state are per pipeline, so they are built inside the
        // loop below rather than shared the way the rest of the state is.

        VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

        VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
        pushRange.get(0)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .offset(0)
                // One vec4 of per-draw parameters, x = alpha cutoff. Everything
                // that is the same for a whole frame moved into a uniform
                // buffer; this used to be 112 of the 128 bytes Vulkan
                // guarantees, which left nothing to grow into.
                .size(16);
        if (pipelineLayout == 0) {
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device(), layoutInfo, null, pLayout),
                    "vkCreatePipelineLayout(terrain)");
            pipelineLayout = pLayout.get(0);
        }

        // ALPHA_TEST (constant_id 0) off for SOLID, on for the CUTOUT layers.
        // Booleans travel as a 32-bit value, like VkBool32.
        VkSpecializationMapEntry.Buffer specEntry = VkSpecializationMapEntry.calloc(2, stack);
        specEntry.get(0).constantID(0).offset(0).size(4);
        specEntry.get(1).constantID(1).offset(4).size(4);

        VkGraphicsPipelineCreateInfo.Buffer pipelineInfo =
                VkGraphicsPipelineCreateInfo.calloc(TERRAIN_PIPELINES.length, stack);
        for (int variant = 0; variant < TERRAIN_PIPELINES.length; variant++) {
            TerrainPipeline spec = TERRAIN_PIPELINES[variant];
            VkSpecializationInfo specInfo = VkSpecializationInfo.calloc(stack)
                    .pMapEntries(specEntry)
                    .pData(stack.bytes(
                            (byte) (spec.alphaTest ? 1 : 0), (byte) 0, (byte) 0, (byte) 0,
                            (byte) (spec.blend ? 1 : 0), (byte) 0, (byte) 0, (byte) 0));

            VkPipelineDepthStencilStateCreateInfo depthState =
                    VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                            .depthTestEnable(true)
                            .depthWriteEnable(spec.depthWrite)
                            .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttachment.get(0)
                    .blendEnable(spec.blend)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            if (spec.blend) {
                // Premultiplied "over", not the SRC_ALPHA form the game uses.
                //
                // Vanilla blends water straight onto its finished frame, once.
                // Here it happens twice — into a target of its own, and then
                // compositing that target over the frame — and "over" only
                // survives being split like that if the colour carries its
                // coverage. With straight alpha, two overlapping water surfaces
                // would each be scaled by their alpha again at composite time
                // and the overlap would come out too dark. The shader writes
                // colour already multiplied by alpha; see BLEND in terrain.frag.
                blendAttachment.get(0)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK_BLEND_OP_ADD);
            }
            VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .pAttachments(blendAttachment);

            VkPipelineShaderStageCreateInfo.Buffer variantStages =
                    VkPipelineShaderStageCreateInfo.calloc(2, stack);
            variantStages.get(0)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(entryPoint);
            variantStages.get(1)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(entryPoint)
                    .pSpecializationInfo(specInfo);
            pipelineInfo.get(variant)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(variantStages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(raster)
                    .pMultisampleState(multisample)
                    .pDepthStencilState(depthState)
                    .pColorBlendState(blend)
                    .pDynamicState(dynamic)
                    .layout(pipelineLayout)
                    // A pipeline belongs to the pass it is used in, and the
                    // blended one is drawn in the translucent pass because that
                    // is the pass whose depth is loaded rather than cleared.
                    .renderPass(spec.blend ? translucentRenderPass : renderPass)
                    // The opaque pass has one subpass; the translucent one has
                    // two, and everything that is not a creature is in the
                    // second. A pipeline naming the wrong one is refused.
                    .subpass(spec.blend ? SUBPASS_TRANSLUCENT : 0);
        }
        LongBuffer pPipeline = stack.mallocLong(TERRAIN_PIPELINES.length);
        check(vkCreateGraphicsPipelines(device(), pipelineCacheHandle, pipelineInfo, null,
                pPipeline), "vkCreateGraphicsPipelines(terrain)");
        for (int variant = 0; variant < TERRAIN_PIPELINES.length; variant++) {
            if (rayQuery) {
                tracingPipelines[variant] = pPipeline.get(variant);
            } else {
                pipelines[variant] = pPipeline.get(variant);
            }
        }

        vkDestroyShaderModule(device(), vertModule, null);
        vkDestroyShaderModule(device(), fragModule, null);
    }

    /**
     * The one pipeline that draws everything the game builds as camera-facing
     * quads, and the descriptors it picks a texture with.
     *
     * It lives in the translucent render pass, which is what makes the whole
     * arrangement worth having: that pass already has the game's depth loaded
     * into it and already ends in a composite over the game's frame. Particles
     * and weather ride along in the submission that was going to happen
     * anyway, and cost no extra transfer between the two APIs at all.
     */
    private void createSpriteResources(MemoryStack stack) {
        VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).maxLod(0.0f)
                // Rain and snow scroll their texture coordinates past 1 to make
                // the fall, so this one sheet genuinely needs to repeat.
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT);
        LongBuffer pSampler = stack.mallocLong(1);
        check(vkCreateSampler(device(), samplerInfo, null, pSampler), "vkCreateSampler(sprite)");
        spriteSampler = pSampler.get(0);

        VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
        binding.get(0).binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(binding);
        LongBuffer pLayout = stack.mallocLong(1);
        check(vkCreateDescriptorSetLayout(device(), layoutInfo, null, pLayout),
                "vkCreateDescriptorSetLayout(sprite)");
        spriteSetLayout = pLayout.get(0);

        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
        poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SPRITE_SLOTS);
        VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .pPoolSizes(poolSizes)
                .maxSets(SPRITE_SLOTS);
        LongBuffer pPool = stack.mallocLong(1);
        check(vkCreateDescriptorPool(device(), poolInfo, null, pPool), "vkCreateDescriptorPool(sprite)");
        spriteDescriptorPool = pPool.get(0);

        VkDescriptorSetAllocateInfo setInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(spriteDescriptorPool)
                .pSetLayouts(stack.mallocLong(SPRITE_SLOTS));
        for (int i = 0; i < SPRITE_SLOTS; i++) {
            setInfo.pSetLayouts().put(i, spriteSetLayout);
        }
        LongBuffer pSets = stack.mallocLong(SPRITE_SLOTS);
        check(vkAllocateDescriptorSets(device(), setInfo, pSets), "vkAllocateDescriptorSets(sprite)");
        for (int i = 0; i < SPRITE_SLOTS; i++) {
            spriteSets[i] = pSets.get(i);
        }

        long vertModule = createShaderModule(stack, "vulkanmod112/shaders/sprite.vert.spv");
        long fragModule = createShaderModule(stack, "vulkanmod112/shaders/sprite.frag.spv");
        ByteBuffer entryPoint = stack.UTF8("main");

        VkVertexInputBindingDescription.Buffer vertexBinding =
                VkVertexInputBindingDescription.calloc(1, stack);
        vertexBinding.get(0).binding(0).stride(SPRITE_VERTEX_STRIDE)
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        VkVertexInputAttributeDescription.Buffer attrs =
                VkVertexInputAttributeDescription.calloc(4, stack);
        attrs.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
        attrs.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(12);
        attrs.get(2).location(2).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(20);
        attrs.get(3).location(3).binding(0).format(VK_FORMAT_R16G16_SSCALED).offset(24);
        VkPipelineVertexInputStateCreateInfo vertexInput =
                VkPipelineVertexInputStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                        .pVertexBindingDescriptions(vertexBinding)
                        .pVertexAttributeDescriptions(attrs);

        VkPipelineInputAssemblyStateCreateInfo inputAssembly =
                VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                        .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
        VkPipelineViewportStateCreateInfo viewportState =
                VkPipelineViewportStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                        .viewportCount(1).scissorCount(1);
        // No culling, and vanilla agrees: a particle is a quad turned to face
        // the camera and weather turns culling off by hand. Which way round
        // either of them comes out is not a fact anyone maintains.
        VkPipelineRasterizationStateCreateInfo raster =
                VkPipelineRasterizationStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                        .polygonMode(VK_POLYGON_MODE_FILL)
                        .cullMode(VK_CULL_MODE_NONE)
                        .frontFace(VK_FRONT_FACE_CLOCKWISE)
                        .lineWidth(1.0f);
        VkPipelineMultisampleStateCreateInfo multisample =
                VkPipelineMultisampleStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                        .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
        // Tested, never written. The depth in this pass is the game's own,
        // borrowed for the length of the pass and handed straight back, and the
        // attachment is declared read-only for exactly that reason. What is
        // lost by it is particles occluding each other, which vanilla does for
        // one of its six queues; what would be lost by writing is the depth the
        // game goes on drawing entities against.
        VkPipelineDepthStencilStateCreateInfo depthState =
                VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                        .depthTestEnable(true)
                        .depthWriteEnable(false)
                        .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
        VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                VkPipelineColorBlendAttachmentState.calloc(1, stack);
        blendAttachment.get(0)
                .blendEnable(true)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                        | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                .srcColorBlendFactor(VK_BLEND_FACTOR_ONE)
                .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .alphaBlendOp(VK_BLEND_OP_ADD);
        VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .pAttachments(blendAttachment);
        VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

        VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
        pushRange.get(0)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                // Three vec4: the per-batch parameters, where the sun is, and
                // the colour laid over a creature that has just been hurt.
                // The sun could have come from the frame block this pass
                // already binds, but reaching it there means declaring every
                // field in front of it a second time, and a uniform block
                // written out twice is a layout with two authors. It went wrong
                // that way once already.
                .offset(0).size(48);
        VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(descriptorSetLayout, spriteSetLayout))
                .pPushConstantRanges(pushRange);
        LongBuffer pPipelineLayout = stack.mallocLong(1);
        check(vkCreatePipelineLayout(device(), pipelineLayoutInfo, null, pPipelineLayout),
                "vkCreatePipelineLayout(sprite)");
        spritePipelineLayout = pPipelineLayout.get(0);

        VkPipelineShaderStageCreateInfo.Buffer stages =
                VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(entryPoint);
        stages.get(1)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(entryPoint);
        VkGraphicsPipelineCreateInfo.Buffer pipelineInfo =
                VkGraphicsPipelineCreateInfo.calloc(1, stack);
        pipelineInfo.get(0)
                .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(inputAssembly)
                .pViewportState(viewportState)
                .pRasterizationState(raster)
                .pMultisampleState(multisample)
                .pDepthStencilState(depthState)
                .pColorBlendState(blend)
                .pDynamicState(dynamic)
                .layout(spritePipelineLayout)
                .renderPass(translucentRenderPass)
                .subpass(SUBPASS_TRANSLUCENT);
        LongBuffer pPipeline = stack.mallocLong(1);
        check(vkCreateGraphicsPipelines(device(), pipelineCacheHandle, pipelineInfo, null, pPipeline),
                "vkCreateGraphicsPipelines(sprite)");
        spritePipeline = pPipeline.get(0);

        // A creature is not a particle: an opaque skin has to be opaque, and the
        // cutoff already in the fragment shader still discards where the texture
        // is transparent, which is what a cutout wants.
        //
        // This one is kept for a creature whose skin arrives while the second
        // subpass is being recorded — an item frame's contents, anything the
        // sprite path is handed late. It does not write depth, because nothing
        // in that subpass may.
        blendAttachment.get(0).blendEnable(false);
        check(vkCreateGraphicsPipelines(device(), pipelineCacheHandle, pipelineInfo, null, pPipeline),
                "vkCreateGraphicsPipelines(sprite opaque)");
        spriteOpaquePipeline = pPipeline.get(0);

        // And the one creatures are actually drawn with: the first subpass,
        // where depth is an ordinary writable attachment.
        //
        // Depth writing is the whole reason this subpass exists. Without it a
        // model has no inside: the far side of a head is drawn over the near
        // side whenever it happens to come later in the batch, which is what
        // "heads with no texture" turned out to be, and one mob is drawn
        // through another.
        //
        // Culling stays off, and that is vanilla's decision rather than ours:
        // RenderLivingBase turns face culling off for the whole of every living
        // creature it draws, so the models are built with no promise about
        // which way a face points. Turning it on here would not be an
        // optimisation, it would be a new rule the models were never written
        // to.
        VkPipelineDepthStencilStateCreateInfo creatureDepth =
                VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                        .depthTestEnable(true)
                        .depthWriteEnable(true)
                        .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
        pipelineInfo.get(0)
                .pDepthStencilState(creatureDepth)
                .subpass(SUBPASS_CREATURES);
        check(vkCreateGraphicsPipelines(device(), pipelineCacheHandle, pipelineInfo, null, pPipeline),
                "vkCreateGraphicsPipelines(creature)");
        creaturePipeline = pPipeline.get(0);

        // And the shimmer over enchanted armour, which is the same geometry a
        // third and fourth time with the texture coordinates slid across it.
        //
        // Added to what is already there and weighted by its own colour, which
        // is the game's own SRC_COLOR/ONE — the pattern brightens where it is
        // bright and leaves the skin alone where it is dark. Alpha is left
        // exactly as it was: this target is composited over the game's frame by
        // its alpha, and a glint that raised it would take a bite out of the
        // world behind the creature instead of lying on top of it.
        //
        // Depth tested and not written, so it can only appear where the skin it
        // belongs to already is. That is also why the batches are ordered so
        // that skins go first.
        blendAttachment.get(0)
                .blendEnable(true)
                .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_COLOR)
                .dstColorBlendFactor(VK_BLEND_FACTOR_ONE)
                .colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
                .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                .alphaBlendOp(VK_BLEND_OP_ADD);
        VkPipelineDepthStencilStateCreateInfo glintDepth =
                VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                        .depthTestEnable(true)
                        .depthWriteEnable(false)
                        .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
        pipelineInfo.get(0).pDepthStencilState(glintDepth);
        check(vkCreateGraphicsPipelines(device(), pipelineCacheHandle, pipelineInfo, null, pPipeline),
                "vkCreateGraphicsPipelines(creature glint)");
        creatureGlintPipeline = pPipeline.get(0);

        vkDestroyShaderModule(device(), vertModule, null);
        vkDestroyShaderModule(device(), fragModule, null);
    }

    private void createCompositeProgram() {
        compositePrograms[0] = buildCompositeProgram(true);
        compositePrograms[1] = buildCompositeProgram(false);
        translucentCompositeProgram = buildTranslucentCompositeProgram();
        depthImportProgram = buildDepthImportProgram();
        depthImportInvSizeUniform = GL20C.glGetUniformLocation(depthImportProgram, "uInvSize");
    }

    /**
     * Writes the game's depth into the shared image one fragment at a time.
     *
     * The hardware copy that normally does this only works between buffers of
     * the same depth format, and half the cards in use have no sampleable
     * 24-bit depth at all — their shared image is 32-bit float while the game's
     * buffer stays 24-bit integer, so that copy is refused and the translucent
     * layer had nothing to test itself against. It was declining every frame on
     * those machines, which is a strange way to describe "no water in Vulkan on
     * every AMD card".
     *
     * A fragment shader does not care that the two formats differ: it reads a
     * number and writes a number, and the hardware converts on the way in and
     * on the way out. This is the same trick the opaque composite already uses
     * to send depth the other way, pointed backwards.
     */
    /**
     * Deepens the sky away from the horizon, over the sky the game drew.
     *
     * The colour is not invented: it is the game's own fog colour darkened,
     * which is the same colour vanilla fades its distance into and therefore
     * cannot disagree with the horizon underneath it. What the gradient adds is
     * only the falling-off — flat at the horizon, deepest overhead.
     *
     * Masked by this renderer's own depth. Where the terrain drew, the depth is
     * short of one and nothing is painted; where nothing drew, it stands at the
     * clear value, and that is exactly the sky. No test on colour, which would
     * catch a white cloud or a snowy peak.
     *
     * Screen height rather than a true view direction, and this is the honest
     * limit of it: looking straight up puts the deepest part of the gradient
     * across the middle of the screen instead of at the point overhead. That is
     * what every cheap version of this does, it is a look rather than a sky
     * model, and the slider is where somebody decides how much of it they want.
     */
    private int buildSkyGradientProgram() {
        return buildQuadProgram(
                "uniform sampler2D uDepth;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "uniform mat4 uInvMvp;\n"
                        + "uniform vec3 uSun;\n"
                        + "uniform vec3 uZenith;\n"
                        + "uniform vec3 uGlow;\n"
                        + "uniform float uStrength;\n"
                        + "uniform float uDay;\n"
                        + "void main() {\n"
                        + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                        // Anything the terrain touched is not sky. The clear
                        // value is one, and a drawn pixel is always below it.
                        + "    if (texture2D(uDepth, uv).r < 0.9999) discard;\n"
                        // Where this pixel actually looks, in the world.
                        //
                        // The first version used the height of the pixel on the
                        // screen, which is the same thing only while the camera
                        // is level: look up and the deepest part of the sky lay
                        // across the middle of the view instead of overhead.
                        // Unprojecting the far plane costs one matrix multiply
                        // and answers the real question.
                        + "    vec4 far = uInvMvp * vec4(uv * 2.0 - 1.0, 1.0, 1.0);\n"
                        + "    vec3 dir = normalize(far.xyz / far.w);\n"
                        + "    float up = clamp(dir.y, 0.0, 1.0);\n"
                        // Deepest overhead, nothing at the horizon — where the
                        // game's own colour is already right and the terrain
                        // fades into it.
                        + "    float deep = pow(up, 0.65);\n"
                        // And warm where the sky meets the sun, which is the
                        // other half of what a sky looks like and the half a
                        // gradient alone cannot give. Held to the horizon and
                        // to daylight: a glow around a sun that has set is the
                        // sort of thing that reads as a bug.
                        + "    float toSun = clamp(dot(dir, uSun), 0.0, 1.0);\n"
                        + "    float glow = pow(toSun, 6.0) * (1.0 - up) * uDay;\n"
                        + "    vec3 tint = mix(uZenith, uGlow, glow);\n"
                        + "    gl_FragColor = vec4(tint, uStrength * max(deep, glow));\n"
                        + "}\n");
    }

    private void paintSkyGradient() {
        if (skyGradientProgram == 0) {
            skyGradientProgram = buildSkyGradientProgram();
            skyGradientStrengthUniform =
                    GL20C.glGetUniformLocation(skyGradientProgram, "uStrength");
            skyGradientTopUniform = GL20C.glGetUniformLocation(skyGradientProgram, "uZenith");
            skyGradientGlowUniform = GL20C.glGetUniformLocation(skyGradientProgram, "uGlow");
            skyGradientSunUniform = GL20C.glGetUniformLocation(skyGradientProgram, "uSun");
            skyGradientDayUniform = GL20C.glGetUniformLocation(skyGradientProgram, "uDay");
            skyGradientInvMvpUniform =
                    GL20C.glGetUniformLocation(skyGradientProgram, "uInvMvp");
            skyGradientInvSizeUniform =
                    GL20C.glGetUniformLocation(skyGradientProgram, "uInvSize");
            int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            GL20C.glUseProgram(skyGradientProgram);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(skyGradientProgram, "uDepth"), 1);
            GL20C.glUseProgram(prev);
        }
        // Without an invertible matrix there is no direction to shade by, and a
        // sky painted by screen height was the thing being fixed. Skipped for
        // the frame rather than approximated.
        if (!invert(currentMvp, skyInverse)) {
            return;
        }
        GL20C.glUseProgram(skyGradientProgram);
        GL20C.glUniform2f(skyGradientInvSizeUniform, 1.0f / width, 1.0f / height);
        skyMatrixBuffer.clear();
        skyMatrixBuffer.put(skyInverse).flip();
        GL20C.glUniformMatrix4fv(skyGradientInvMvpUniform, false, skyMatrixBuffer);
        GL20C.glUniform3f(skyGradientSunUniform,
                sunDirection[0], sunDirection[1], sunDirection[2]);
        // Nothing while the sun is under the horizon, and eased in rather than
        // switched on as it rises — the same shape the glint uses for the same
        // reason.
        GL20C.glUniform1f(skyGradientDayUniform,
                Math.max(0.0f, Math.min(sunDirection[1] * 4.0f, 1.0f)));
        // The fog colour, taken down towards a night sky rather than towards
        // black: a zenith that goes grey reads as haze, and haze is the one
        // thing the horizon already has.
        GL20C.glUniform3f(skyGradientTopUniform,
                fogState[0] * 0.42f, fogState[1] * 0.46f, fogState[2] * 0.62f);
        // And up towards warm where the sky meets the sun. Built from the same
        // fog colour so that it stays this world's sky rather than a colour
        // this mod picked: in the Nether, or under a mod's own sky, it leans
        // whatever is already there.
        GL20C.glUniform3f(skyGradientGlowUniform,
                Math.min(fogState[0] * 1.35f, 1.0f),
                Math.min(fogState[1] * 1.12f, 1.0f),
                Math.min(fogState[2] * 0.86f, 1.0f));
        GL20C.glUniform1f(skyGradientStrengthUniform, skyGradient);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glDepthTexture);
        GL11C.glEnable(GL11C.GL_BLEND);
        GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        org.lwjgl.opengl.GL11.glVertex2f(-1.0f, -1.0f);
        org.lwjgl.opengl.GL11.glVertex2f(1.0f, -1.0f);
        org.lwjgl.opengl.GL11.glVertex2f(1.0f, 1.0f);
        org.lwjgl.opengl.GL11.glVertex2f(-1.0f, 1.0f);
        org.lwjgl.opengl.GL11.glEnd();
        GL11C.glDisable(GL11C.GL_BLEND);
    }

    private int buildDepthImportProgram() {
        return buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "void main() {\n"
                        + "    gl_FragDepth = texture2D(uSource, gl_FragCoord.xy * uInvSize).r;\n"
                        // Nothing is written to colour: the pass runs with the
                        // colour mask closed, and a shader that assigns nothing
                        // to gl_FragColor is legal in GLSL 120.
                        + "}\n");
    }

    /**
     * Composite for the translucent target, which differs in one thing that
     * matters: it keeps the alpha it sampled instead of forcing it to 1. The
     * opaque composite replaces what is underneath, this one is blended over
     * it, and the proportion is carried in that channel.
     */
    private int buildTranslucentCompositeProgram() {
        String vertSrc = "#version 120\n"
                + "void main() { gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0); }\n";
        String fragSrc = "#version 120\n"
                + "uniform sampler2D uColor;\n"
                + "uniform vec2 uInvSize;\n"
                + "void main() {\n"
                + "    vec4 c = texture2D(uColor, gl_FragCoord.xy * uInvSize);\n"
                + "    if (c.a < 0.004) discard;\n"
                + "    gl_FragColor = c;\n"
                + "}\n";
        int vert = compileGlShader(GL20C.GL_VERTEX_SHADER, vertSrc);
        int frag = compileGlShader(GL20C.GL_FRAGMENT_SHADER, fragSrc);
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vert);
        GL20C.glAttachShader(program, frag);
        GL20C.glLinkProgram(program);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Translucent composite link failed: "
                    + GL20C.glGetProgramInfoLog(program));
        }
        GL20C.glDeleteShader(vert);
        GL20C.glDeleteShader(frag);
        int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        GL20C.glUseProgram(program);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uColor"), 0);
        translucentInvSizeUniform = GL20C.glGetUniformLocation(program, "uInvSize");
        GL20C.glUseProgram(prev);
        return program;
    }

    /**
     * @param writeDepth export the Vulkan depth per fragment; false when the
     *                   depth buffer is filled by glBlitFramebuffer instead
     */
    /**
     * The one blur three passes share, built by whichever of them arrives first.
     *
     * It lived inside the bloom's own setup and was used by the occlusion
     * regardless, so with the glow switched off the occlusion asked for program
     * zero and drew its two smoothing quads with no shader at all — which does
     * not fail, it writes something else over the answer. Nobody saw it because
     * the presets that turn the occlusion on turn the glow on beside it.
     */
    private void buildBlurProgram() {
        if (bloomBlurProgram != 0) {
            return;
        }
        // One axis per pass. A gaussian is separable, so two passes of five
        // taps do what one of twenty-five would, and the offsets sit between
        // texels on purpose: linear filtering makes each of those one read
        // where the weights say two.
        bloomBlurProgram = buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "uniform vec2 uStep;\n"
                        + "void main() {\n"
                        + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                        + "    vec2 d = uStep * uInvSize;\n"
                        + "    vec3 sum = texture2D(uSource, uv).rgb * 0.227027;\n"
                        + "    sum += (texture2D(uSource, uv + d * 1.3846154).rgb\n"
                        + "          + texture2D(uSource, uv - d * 1.3846154).rgb) * 0.3162162;\n"
                        + "    sum += (texture2D(uSource, uv + d * 3.2307692).rgb\n"
                        + "          + texture2D(uSource, uv - d * 3.2307692).rgb) * 0.0702703;\n"
                        + "    gl_FragColor = vec4(sum, 1.0);\n"
                        + "}\n");
        bloomBlurInvSize = GL20C.glGetUniformLocation(bloomBlurProgram, "uInvSize");
        bloomBlurStep = GL20C.glGetUniformLocation(bloomBlurProgram, "uStep");
    }

    private void buildBloomPrograms() {
        if (bloomExtractProgram != 0) {
            return;
        }
        // What is glowing, at half resolution. The test is not "is this pixel
        // bright" — snow and sand in sunlight are as bright on screen as lava
        // and are not lights. It is what the terrain shader wrote into the
        // alpha it was spending on the constant 1.0.
        bloomExtractProgram = buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform sampler2D uTerrain;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "uniform vec2 uTexel;\n"
                        + "void main() {\n"
                        + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                        // The four full-size pixels this one covers, averaged
                        // by hand. The game's frame is filtered nearest and has
                        // no mip chain, so asking for one sample of it returns
                        // one pixel however far the target has been shrunk —
                        // and a lamp post a few pixels wide falls between the
                        // samples and contributes nothing at all, while a lava
                        // lake covers so many that it cannot be missed. That
                        // was the whole of why small lights had no reach.
                        + "    vec2 h = uTexel * 0.5;\n"
                        + "    vec3 scene = 0.25 * (texture2D(uSource, uv + vec2( h.x,  h.y)).rgb\n"
                        + "                      + texture2D(uSource, uv + vec2(-h.x,  h.y)).rgb\n"
                        + "                      + texture2D(uSource, uv + vec2( h.x, -h.y)).rgb\n"
                        + "                      + texture2D(uSource, uv + vec2(-h.x, -h.y)).rgb);\n"
                        + "    vec4 ref = texture2D(uTerrain, uv);\n"
                        // Covered by something the game drew afterwards? Then
                        // there is no light here to spill. The frame holds
                        // exactly what the terrain wrote wherever nothing was
                        // put over it, so a plain comparison answers it, and
                        // the tolerance is there for the filtering rather than
                        // for any real difference.
                        + "    float visible = step(length(scene - ref.rgb), 0.06);\n"
                        + "    gl_FragColor = vec4(scene * ref.a * visible, 1.0);\n"
                        + "}\n");
        bloomExtractInvSize = GL20C.glGetUniformLocation(bloomExtractProgram, "uInvSize");
        bloomExtractTexel = GL20C.glGetUniformLocation(bloomExtractProgram, "uTexel");
        GL20C.glUseProgram(bloomExtractProgram);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(bloomExtractProgram, "uTerrain"), 1);
        GL20C.glUseProgram(0);

        buildBlurProgram();

        // Added, not laid over: the alpha is zero so a blend of one and one
        // leaves the frame's own alpha alone.
        bloomAddProgram = buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform sampler2D uScene;\n"
                        + "uniform sampler2D uNear;\n"
                        + "uniform sampler2D uFar;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "uniform float uStrength;\n"
                        + "void main() {\n"
                        + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                        + "    vec3 glow = texture2D(uNear, uv).rgb\n"
                        + "             + texture2D(uSource, uv).rgb * 2.0\n"
                        + "             + texture2D(uFar, uv).rgb * 2.5;\n"
                        // Held back on the surfaces producing it, and this is
                        // not taste. Adding light to a pixel that is already
                        // near the top of an eight-bit channel does not make it
                        // brighter, it makes it flat: the frame has no headroom
                        // anywhere, so the only thing the addition can spend is
                        // the texture's own detail. Lava came out as a sheet of
                        // orange with its pattern gone. What a glow is, is the
                        // light that landed somewhere else, so that is what is
                        // added — the source keeps the look it earned.
                        + "    float self = texture2D(uScene, uv).a;\n"
                        + "    gl_FragColor = vec4(glow * uStrength * 1.8 * (1.0 - self), 0.0);\n"
                        + "}\n");
        GL20C.glUseProgram(bloomAddProgram);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(bloomAddProgram, "uScene"), 1);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(bloomAddProgram, "uNear"), 2);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(bloomAddProgram, "uFar"), 3);
        GL20C.glUseProgram(0);
        bloomAddInvSize = GL20C.glGetUniformLocation(bloomAddProgram, "uInvSize");

        // Nothing but the mask, kept for the pass that runs after the Vulkan
        // target has been handed back.
        bloomMaskProgram = buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform sampler2D uAo;\n"
                        + "uniform float uAo_on;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "void main() {\n"
                        + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                        + "    vec2 h = uInvSize * 0.25;\n"
                        // Averaged for the same reason as the extract: the
                        // Vulkan target is filtered nearest as well.
                        + "    vec4 c = 0.25 * (texture2D(uSource, uv + vec2( h.x,  h.y))\n"
                        + "                  + texture2D(uSource, uv + vec2(-h.x,  h.y))\n"
                        + "                  + texture2D(uSource, uv + vec2( h.x, -h.y))\n"
                        + "                  + texture2D(uSource, uv + vec2(-h.x, -h.y)));\n"
                        // Colour and mask together in one texture: the colour
                        // to recognise the terrain again in the finished frame,
                        // the mask to say which of it is a light.
                        + "    vec2 shade = texture2D(uAo, uv).rg;\n"
                        + "    c.rgb *= mix(1.0, shade.r * (1.0 - shade.g), uAo_on);\n"
                        + "    gl_FragColor = vec4(c.rgb, clamp((c.a - 0.5) * 2.0, 0.0, 1.0));\n"
                        + "}\n");
        bloomMaskInvSize = GL20C.glGetUniformLocation(bloomMaskProgram, "uInvSize");
        bloomMaskAoUniform = GL20C.glGetUniformLocation(bloomMaskProgram, "uAo_on");
        GL20C.glUseProgram(bloomMaskProgram);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(bloomMaskProgram, "uAo"), 1);
        GL20C.glUseProgram(0);

        // Four to one on each axis, as four bilinear reads of a texture this
        // renderer owns and filters linearly — so each read is already the
        // average of two by two, and the four together are a sixteen-pixel box.
        // Nothing may be point-sampled on the way down; that is what lost the
        // small lights.
        bloomDownProgram = buildQuadProgram(
                "uniform sampler2D uSource;\n"
                        + "uniform vec2 uInvSize;\n"
                        + "uniform vec2 uTexel;\n"
                        + "void main() {\n"
                        + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                        + "    gl_FragColor = 0.25 * (texture2D(uSource, uv + uTexel)\n"
                        + "                        + texture2D(uSource, uv + vec2(uTexel.x, -uTexel.y))\n"
                        + "                        + texture2D(uSource, uv + vec2(-uTexel.x, uTexel.y))\n"
                        + "                        + texture2D(uSource, uv - uTexel));\n"
                        + "}\n");
        bloomDownInvSize = GL20C.glGetUniformLocation(bloomDownProgram, "uInvSize");
        bloomDownTexel = GL20C.glGetUniformLocation(bloomDownProgram, "uTexel");
        bloomAddStrength = GL20C.glGetUniformLocation(bloomAddProgram, "uStrength");
    }

    /** A fragment shader over a fullscreen quad, with uSource on unit 0. */
    private int buildQuadProgram(String body) {
        int vert = compileGlShader(GL20C.GL_VERTEX_SHADER, "#version 120\n"
                + "void main() { gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0); }\n");
        int frag = compileGlShader(GL20C.GL_FRAGMENT_SHADER, "#version 120\n" + body);
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vert);
        GL20C.glAttachShader(program, frag);
        GL20C.glLinkProgram(program);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Bloom program link failed: "
                    + GL20C.glGetProgramInfoLog(program));
        }
        GL20C.glDeleteShader(vert);
        GL20C.glDeleteShader(frag);
        int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        GL20C.glUseProgram(program);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uSource"), 0);
        GL20C.glUseProgram(prev);
        return program;
    }

    private int buildCompositeProgram(boolean writeDepth) {
        String vertSrc = "#version 120\n"
                + "void main() { gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0); }\n";
        String fragSrc = "#version 120\n"
                + "uniform sampler2D uColor;\n"
                + "uniform sampler2D uDepth;\n"
                + "uniform sampler2D uAo;\n"
                + "uniform sampler2D uMotion;\n"
                + "uniform sampler2D uAccum;\n"
                + "uniform float uAo_on;\n"
                + "uniform float uAo_only;\n"
                + "uniform float uMotion_show;\n"
                + "uniform float uMotion_ghost;\n"
                + "uniform float uAccum_on;\n"
                + "uniform vec2 uInvSize;\n"
                + "void main() {\n"
                + "    vec2 uv = gl_FragCoord.xy * uInvSize;\n"
                + "    vec4 c = texture2D(uColor, uv);\n"
                + "    if (c.a < 0.004) discard;\n"
                // This frame's colour averaged with the ones before it, where
                // that pass ran. Only the colour: whether there is terrain here
                // at all, and whether it glows, are this frame's business and
                // are read above from the image Vulkan wrote.
                + "    c.rgb = mix(c.rgb, texture2D(uAccum, uv).rgb, uAccum_on);\n"
                // How much of its surroundings this point can see. The game
                // shades a face by which way it points and by nothing else, so
                // without this an inside corner is lit exactly like open wall.
                // How enclosed this point is, and how much of the sun it
                // loses — the second weighed by how much sky it was getting.
                //
                // The share arrives in the lower half of the terrain's own
                // alpha, which is where the terrain shader put it. Without it
                // a shadow drawn over the finished frame darkens a cave wall
                // lit by a torch, which the traced shadow is careful never to
                // do; with it the two agree.
                + "    vec2 shade = texture2D(uAo, uv).rg;\n"
                + "    float sky = c.a > 0.5 ? 1.0 : clamp(c.a / 0.49, 0.0, 1.0);\n"
                + "    float ao = mix(1.0, shade.r * (1.0 - shade.g * sky), uAo_on);\n"
                // On its own, as flat grey, when asked for. Vanilla darkens the
                // corners of its own blocks and darkens a face by which way it
                // points, so a dark seam in a lit room is not evidence of
                // anything until those two are out of the picture. This takes
                // them out: what is left on screen is this effect and nothing
                // else, and a defect either survives that or was never here.
                // The diagnostic shows the occlusion by itself, not the
                // occlusion times the shadow: it exists to answer one question
                // and a view that answers two answers neither.
                + "    c.rgb = mix(c.rgb * ao, vec3(shade.r), uAo_only);\n"
                // Where this pixel was a frame ago, as a colour.
                //
                // Direction is the hue and speed is the brightness, which is
                // the one encoding of this that can be read without a key.
                // Putting the two axes in the red and green channels seemed
                // simpler and was not: half of every direction is a channel
                // going negative, so looking down came out violet and looked
                // like a different thing happening rather than the opposite of
                // looking up. On a wheel, opposite directions are opposite
                // colours and every direction has one of its own.
                + "    vec2 step = texture2D(uMotion, uv).rg;\n"
                + "    float speed = clamp(length(step) * 40.0, 0.0, 1.0);\n"
                + "    float turn = atan(step.y, step.x) * 0.1591549 + 0.5;\n"
                + "    vec3 wheel = clamp(abs(fract(turn + vec3(0.0, 0.6666667, 0.3333333))\n"
                + "                       * 6.0 - 3.0) - 1.0, 0.0, 1.0);\n"
                // Optionally over a ghost of the world rather than over
                // nothing. Black answers "is any of this moving" and the ghost
                // answers "which part of it" — a wall and the floor beside it
                // move differently and on black there is no telling which was
                // which.
                + "    float grey = dot(c.rgb, vec3(0.299, 0.587, 0.114)) * 0.28;\n"
                + "    c.rgb = mix(c.rgb, wheel * speed + vec3(grey) * uMotion_ghost,\n"
                + "                uMotion_show);\n"
                + (writeDepth ? "    gl_FragDepth = texture2D(uDepth, uv).r;\n" : "")
                + "    gl_FragColor = vec4(c.rgb, 1.0);\n"
                + "}\n";
        int vert = compileGlShader(GL20C.GL_VERTEX_SHADER, vertSrc);
        int frag = compileGlShader(GL20C.GL_FRAGMENT_SHADER, fragSrc);
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vert);
        GL20C.glAttachShader(program, frag);
        GL20C.glLinkProgram(program);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Composite program link failed: "
                    + GL20C.glGetProgramInfoLog(program));
        }
        GL20C.glDeleteShader(vert);
        GL20C.glDeleteShader(frag);

        int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        GL20C.glUseProgram(program);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uColor"), 0);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uDepth"), 1);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uAo"), 2);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uMotion"), 3);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uAccum"), 4);
        compositeAccumUniforms[writeDepth ? 0 : 1] =
                GL20C.glGetUniformLocation(program, "uAccum_on");
        compositeAoUniforms[writeDepth ? 0 : 1] = GL20C.glGetUniformLocation(program, "uAo_on");
        compositeMotionUniforms[writeDepth ? 0 : 1] =
                GL20C.glGetUniformLocation(program, "uMotion_show");
        compositeMotionGhostUniforms[writeDepth ? 0 : 1] =
                GL20C.glGetUniformLocation(program, "uMotion_ghost");
        compositeAoOnlyUniforms[writeDepth ? 0 : 1] =
                GL20C.glGetUniformLocation(program, "uAo_only");
        compositeInvSizeUniforms[writeDepth ? 0 : 1] =
                GL20C.glGetUniformLocation(program, "uInvSize");
        GL20C.glUseProgram(prev);
        return program;
    }

    private static int compileGlShader(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("Composite shader compile failed: "
                    + GL20C.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private void ensureTargets(int fbWidth, int fbHeight) {
        if (fbWidth == width && fbHeight == height && colorImage != 0) {
            return;
        }
        destroyTargets();
        width = fbWidth;
        height = fbHeight;
        try (MemoryStack stack = stackPush()) {
            long[] colorOut = new long[4];
            createExportedTarget(stack, colourFormat,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT, colorOut);
            colorImage = colorOut[0];
            colorMemory = colorOut[1];
            colorView = colorOut[2];
            glColorMemoryObject = importMemoryToGL(stack, colorMemory, colorOut[3]);
            glColorTexture = createGlTexture(glColorMemoryObject, glColourFormat);

            long[] depthOut = new long[4];
            createExportedTarget(stack, depthFormat(stack),
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT, depthOut);
            depthImage = depthOut[0];
            depthMemory = depthOut[1];
            depthView = depthOut[2];
            glDepthMemoryObject = importMemoryToGL(stack, depthMemory, depthOut[3]);
            boolean depth24 = depthFormat(stack) == VK_FORMAT_X8_D24_UNORM_PACK32;
            glDepthTexture = createGlTexture(glDepthMemoryObject, depth24
                    ? org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24
                    : org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT32F);
            // glBlitFramebuffer only copies depth between matching formats,
            // and the game's framebuffer is 24-bit.
            // Before anything is drawn with them, and before the depth blit is
            // even considered: if OpenGL will not take these, none of what
            // follows can work and the frame that finds out costs the display.
            verifyImportedTargets();
            probeImportedRead();
            depthBlit = depth24 && depthBlitAllowed();
            if (depthBlit) {
                glDepthBlitFbo = createDepthReadFbo();
                depthBlit = glDepthBlitFbo != -1;
            }
            if (!depthBlit) {
                createDepthImportTargets();
            }

            long[] translucentOut = new long[4];
            createExportedTarget(stack, colourFormat,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT, translucentOut);
            translucentImage = translucentOut[0];
            translucentMemory = translucentOut[1];
            translucentView = translucentOut[2];
            glTranslucentMemoryObject = importMemoryToGL(stack, translucentMemory, translucentOut[3]);
            glTranslucentTexture = createGlTexture(glTranslucentMemoryObject, glColourFormat);

            VkFramebufferCreateInfo fbInfo = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(stack.longs(colorView, depthView))
                    .width(width)
                    .height(height)
                    .layers(1);
            LongBuffer pFb = stack.mallocLong(1);
            check(vkCreateFramebuffer(device(), fbInfo, null, pFb), "vkCreateFramebuffer(terrain)");
            framebuffer = pFb.get(0);

            // Same depth image, so the translucent layer is hidden by opaque
            // terrain in front of it without any work of its own.
            fbInfo.renderPass(translucentRenderPass)
                    .pAttachments(stack.longs(translucentView, depthView));
            check(vkCreateFramebuffer(device(), fbInfo, null, pFb),
                    "vkCreateFramebuffer(translucent)");
            translucentFramebuffer = pFb.get(0);

            int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            for (int i = 0; i < compositePrograms.length; i++) {
                GL20C.glUseProgram(compositePrograms[i]);
                GL20C.glUniform2f(compositeInvSizeUniforms[i], 1.0f / width, 1.0f / height);
            }
            if (translucentCompositeProgram != 0 && translucentInvSizeUniform != -1) {
                GL20C.glUseProgram(translucentCompositeProgram);
                GL20C.glUniform2f(translucentInvSizeUniform, 1.0f / width, 1.0f / height);
            }
            GL20C.glUseProgram(prev);

            // Diagnostic readback strip (host-visible, persistently mapped)
            // Four bytes a pixel was the format rather than a fact: a
            // sixteen-bit target is eight, and a buffer sized for the old one
            // is a copy that writes past its end.
            int readbackSize = width * READBACK_ROWS * colourBytesPerPixel();
            VkBufferCreateInfo rbInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(readbackSize)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pRb = stack.mallocLong(1);
            check(vkCreateBuffer(device(), rbInfo, null, pRb), "vkCreateBuffer(readback)");
            readbackBuffer = pRb.get(0);
            VkMemoryRequirements rbReq = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), readbackBuffer, rbReq);
            VkMemoryAllocateInfo rbAlloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(rbReq.size())
                    .memoryTypeIndex(findMemoryType(stack, rbReq.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pRbMem = stack.mallocLong(1);
            check(vkAllocateMemory(device(), rbAlloc, null, pRbMem), "vkAllocateMemory(readback)");
            readbackMemory = pRbMem.get(0);
            check(vkBindBufferMemory(device(), readbackBuffer, readbackMemory, 0), "vkBindBufferMemory(readback)");
            PointerBuffer ppRb = stack.mallocPointer(1);
            check(vkMapMemory(device(), readbackMemory, 0, readbackSize, 0, ppRb), "vkMapMemory(readback)");
            readbackMapped = ppRb.get(0);
        }
        firstFrame = true;
        // The water's view of the scene is these two images, and they are new.
        // Without this the reflection would go on reading whatever the sets
        // were filled with before — the block atlas, in a puddle.
        updateDescriptors();
        LOGGER.info("Terrain targets (re)created: {}x{} color+depth shared with GL (textures {}/{})",
                width, height, glColorTexture, glDepthTexture);
    }

    private static boolean depthBlitAllowed() {
        return !"false".equals(System.getProperty("vulkanmod112.depthBlit"));
    }

    /** Read where the sampler is built; the world has to be reloaded to change it. */
    private static boolean flatBlockColours() {
        return "true".equals(System.getProperty("vulkanmod112.flatBlockColours"));
    }

    /**
     * Prefers 24-bit depth so the result can be blitted straight into the
     * game's depth buffer; falls back to D32_SFLOAT where the driver has no
     * sampleable D24 (common on AMD).
     */
    private int depthFormat(MemoryStack stack) {
        if (depthFormat != 0) {
            return depthFormat;
        }
        if (!depthBlitAllowed()) {
            return depthFormat = VK_FORMAT_D32_SFLOAT;
        }
        VkFormatProperties props = VkFormatProperties.malloc(stack);
        vkGetPhysicalDeviceFormatProperties(ctx.getPhysicalDevice(),
                VK_FORMAT_X8_D24_UNORM_PACK32, props);
        int needed = VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT
                | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        return depthFormat = (props.optimalTilingFeatures() & needed) == needed
                ? VK_FORMAT_X8_D24_UNORM_PACK32
                : VK_FORMAT_D32_SFLOAT;
    }

    /**
     * Asks OpenGL, in words, whether it can actually use the textures we just
     * handed it out of Vulkan's memory.
     *
     * With the semaphores switched off entirely and the layouts agreed, the one
     * thing left in the frame was OpenGL sampling these two textures — and the
     * card still stopped. Vulkan rendering into its own image says nothing about
     * what OpenGL sees through the import, which is what was wrongly concluded
     * from it twice.
     *
     * Attachment completeness is answered on the processor, by the driver's own
     * bookkeeping, without a single command reaching the card. So a driver that
     * cannot really use the import can say so here instead of dying four calls
     * later with the machine's display reset — and the mod steps aside to
     * vanilla with a sentence a player can act on, rather than taking the game
     * down.
     *
     * A pass is not a promise: it means the driver accepts the textures as
     * attachments, not that its idea of their memory layout matches Vulkan's.
     * On a vendor whose OpenGL and Vulkan drivers are separate implementations
     * that is a real distinction, and it is the next thing to look at if this
     * reports everything is fine and the card still stops.
     */
    private void verifyImportedTargets() {
        int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int fbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, glColorTexture, 0);
        int colorStatus = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, 0, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11C.GL_TEXTURE_2D, glDepthTexture, 0);
        int depthStatus = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
        GL30C.glDeleteFramebuffers(fbo);

        // Read back through OpenGL rather than trusted from what we asked for:
        // the numbers the driver reports are the ones it will render by.
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glColorTexture);
        int gotWidth = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
        int gotHeight = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        int error = GL11C.glGetError();

        LOGGER.info("Imported targets checked by OpenGL: colour attachment 0x{}, depth attachment "
                        + "0x{}, colour texture reads back as {}x{} (asked for {}x{}), glGetError 0x{}",
                Integer.toHexString(colorStatus), Integer.toHexString(depthStatus),
                gotWidth, gotHeight, width, height, Integer.toHexString(error));

        if (colorStatus != GL30C.GL_FRAMEBUFFER_COMPLETE
                || depthStatus != GL30C.GL_FRAMEBUFFER_COMPLETE
                || gotWidth != width || gotHeight != height) {
            throw new net.vulkanmod112.VulkanUnavailableException(
                    "This driver's OpenGL side will not use the images Vulkan shared with it"
                    + " (colour 0x" + Integer.toHexString(colorStatus)
                    + ", depth 0x" + Integer.toHexString(depthStatus)
                    + ", size " + gotWidth + "x" + gotHeight + "). Sharing frames between the two"
                    + " is what this renderer is built on, so it stands aside here.");
        }
    }

    /** Off with -Dvulkanmod112.probeImportedRead=false if the probe itself becomes a problem. */
    private static final boolean PROBE_IMPORTED_READ =
            !"false".equals(System.getProperty("vulkanmod112.probeImportedRead"));
    private static boolean importedReadProbed;

    /**
     * Makes OpenGL actually touch the memory Vulkan shared with it, in three
     * separate steps, each announced before it runs.
     *
     * {@link #verifyImportedTargets()} passed on the machine that dies, so the
     * driver's bookkeeping accepts the import; what it cannot answer is whether
     * its idea of the memory layout matches Vulkan's. Only a command that reads
     * the memory can, and on that machine the first such command takes the
     * display with it — which is why this cannot be a return value. It is the
     * log line that does the work: whichever step is announced but never
     * reports back is the one that kills the driver.
     *
     * Deliberately before Vulkan has drawn or signalled anything. The contents
     * are undefined here and that is fine — the question is whether the memory
     * can be read at all, and asking it without a semaphore in the picture is
     * what makes the answer mean only one thing.
     */
    private void probeImportedRead() {
        if (!PROBE_IMPORTED_READ || importedReadProbed) {
            return;
        }
        importedReadProbed = true;

        int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int prevTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int prevActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int scratchTexture = 0;
        int readFbo = 0;
        int drawFbo = 0;

        try (MemoryStack stack = stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            java.nio.ByteBuffer pixel = stack.calloc(16);

            org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT
                    | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
                    | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                    | org.lwjgl.opengl.GL11.GL_TEXTURE_BIT
                    | org.lwjgl.opengl.GL11.GL_CURRENT_BIT
                    | org.lwjgl.opengl.GL11.GL_VIEWPORT_BIT
                    | org.lwjgl.opengl.GL11.GL_POLYGON_BIT);
            GL20C.glUseProgram(0);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
            GL11C.glDepthMask(false);

            // 1. The shared colour read straight out as an attachment. The
            // shortest path from that memory to the processor there is.
            readFbo = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, glColorTexture, 0);
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            LOGGER.info("Shared memory probe 1 of 3: reading the shared colour back as an attachment");
            GL11C.glReadPixels(0, 0, 1, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixel);
            GL11C.glFinish();
            LOGGER.info("Shared memory probe 1 of 3: survived (glGetError 0x{})",
                    Integer.toHexString(GL11C.glGetError()));

            // 2. The shared colour sampled through a texture unit — what the
            // composite quad does every frame, on four pixels instead of two
            // million.
            scratchTexture = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scratchTexture);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA8, 4, 4, 0,
                    GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            drawFbo = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, scratchTexture, 0);
            GL11C.glViewport(0, 0, 4, 4);

            probeSample("2 of 3", "colour", glColorTexture);
            probeSample("3 of 3", "depth", glDepthTexture);

            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, drawFbo);
            GL11C.glReadPixels(0, 0, 1, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixel);
            GL11C.glFinish();
            LOGGER.info("Shared memory probe: all three steps survived — this driver can read"
                    + " what Vulkan shared with it");

            GL11C.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            org.lwjgl.opengl.GL11.glPopAttrib();
        } finally {
            if (scratchTexture != 0) {
                GL11C.glDeleteTextures(scratchTexture);
            }
            if (readFbo != 0) {
                GL30C.glDeleteFramebuffers(readFbo);
            }
            if (drawFbo != 0) {
                GL30C.glDeleteFramebuffers(drawFbo);
            }
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevRead);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL13C.glActiveTexture(prevActive);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
            GL20C.glUseProgram(prevProgram);
        }
    }

    /** One textured quad off the shared texture, announced before and after. */
    private void probeSample(String step, String which, int texture) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glEnable(GL11C.GL_TEXTURE_2D);
        LOGGER.info("Shared memory probe {}: sampling the shared {} through a texture unit",
                step, which);
        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        org.lwjgl.opengl.GL11.glTexCoord2f(0.0f, 0.0f);
        org.lwjgl.opengl.GL11.glVertex2f(-1.0f, -1.0f);
        org.lwjgl.opengl.GL11.glTexCoord2f(1.0f, 0.0f);
        org.lwjgl.opengl.GL11.glVertex2f(1.0f, -1.0f);
        org.lwjgl.opengl.GL11.glTexCoord2f(1.0f, 1.0f);
        org.lwjgl.opengl.GL11.glVertex2f(1.0f, 1.0f);
        org.lwjgl.opengl.GL11.glTexCoord2f(0.0f, 1.0f);
        org.lwjgl.opengl.GL11.glVertex2f(-1.0f, 1.0f);
        org.lwjgl.opengl.GL11.glEnd();
        GL11C.glFinish();
        LOGGER.info("Shared memory probe {}: survived (glGetError 0x{})",
                step, Integer.toHexString(GL11C.glGetError()));
    }

    /**
     * The two things the shader path needs, built only when the hardware copy
     * is unavailable: a texture in the game's depth format to copy into, and a
     * draw target whose depth attachment is the shared image.
     *
     * A failure here is not fatal. It costs the translucent layer in Vulkan —
     * the game keeps drawing water itself, exactly as before this existed.
     */
    private void createDepthImportTargets() {
        int prevTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        // The read binding as well, because the read buffer is set below and
        // that setting lands on whichever framebuffer is bound for reading.
        int prevRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        try {
            gameDepthTexture = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, gameDepthTexture);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            // Sampled as a plain number, not compared against anything: the
            // shader wants the depth itself, and a texture left in comparison
            // mode answers with a nought or a one instead.
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24, width, height, 0,
                    GL11C.GL_DEPTH_COMPONENT, GL11C.GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);

            glDepthWriteFbo = GL30C.glGenFramebuffers();
            // Both bindings, not just the one being drawn into. The two calls
            // below are not a pair despite reading like one: glDrawBuffers
            // lands on the framebuffer bound for drawing and glReadBuffer on
            // the one bound for reading, so binding only the first sent the
            // second to whatever was bound for reading — the game's own frame,
            // whose read buffer it set to none and left there.
            //
            // What that costs is not this pass, which never reads. It is every
            // later copy taken out of the game's frame: the depth for scene
            // occlusion, the colour for occlusion, the frame for grading. Each
            // is refused with GL_INVALID_OPERATION, each leaves its target as
            // it found it, and the grading pass writes what it read back over
            // the whole world — which is a black world with the hand still on
            // top of it, on the one preset that grades and the one kind of card
            // that takes this path at all.
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, glDepthWriteFbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                    GL11C.GL_TEXTURE_2D, glDepthTexture, 0);
            // Depth only. Without saying so the target has no colour buffer to
            // draw into and is incomplete by the rules, however little colour
            // this pass intends to write.
            GL20C.glDrawBuffers(GL11C.GL_NONE);
            GL11C.glReadBuffer(GL11C.GL_NONE);
            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
            int error = GL11C.glGetError();
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE || error != 0) {
                LOGGER.warn("Depth import target incomplete (0x{}, glGetError 0x{}); the translucent"
                                + " layer stays with the game", Integer.toHexString(status),
                        Integer.toHexString(error));
                destroyDepthImportTargets();
            } else {
                LOGGER.info("Depth handed back to Vulkan by shader — this card has no sampleable"
                        + " 24-bit depth, so the translucent layer would otherwise be refused");
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not build the shader path for depth; the translucent layer stays"
                    + " with the game", t);
            destroyDepthImportTargets();
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevRead);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTexture);
        }
    }

    private void destroyDepthImportTargets() {
        if (gameDepthTexture != 0) {
            GL11C.glDeleteTextures(gameDepthTexture);
            gameDepthTexture = 0;
        }
        if (glDepthWriteFbo != -1) {
            GL30C.glDeleteFramebuffers(glDepthWriteFbo);
            glDepthWriteFbo = -1;
        }
    }

    /**
     * One GL pass, timed by the card itself.
     *
     * Two query objects used in turn, because reading the one just written
     * means waiting for the card to reach it — which stops the processor dead
     * and changes the very thing being measured. The other one holds last
     * frame's answer and is ready by now.
     */
    private static String glTimeText(GlTimer timer) {
        double ms = timer.millis();
        return ms < 0.0 ? "n/a" : String.format("%.2f ms", ms);
    }

    /**
     * How long a piece of OpenGL work took on the card.
     *
     * <h2>Why there are eight query objects and not two</h2>
     *
     * The first version had two and looked at last frame's result at the start
     * of this one. It never once succeeded: the card runs a frame or three
     * behind the processor, so one frame later the answer is not ready, and
     * beginning a query on an object whose result was never collected throws
     * that result away. Every frame threw away the previous frame's answer and
     * the printed time stayed at whatever was collected before the world
     * finished loading — for fifty-seven snapshots running, a number that was
     * being read as a measurement.
     *
     * With a ring, nothing is ever begun on a slot that still owes an answer.
     * A slot is used only when it is free, results are collected whenever the
     * card has them, and if every slot is busy the frame simply is not timed —
     * which is honest, and which the counters beside the time say out loud.
     */
    private static final class GlTimer {
        private static final int SLOTS = 8;
        private final int[] query = new int[SLOTS];
        private final boolean[] pending = new boolean[SLOTS];
        private int active = -1;
        private boolean unavailable;
        private long nanos;
        /**
         * Reads taken, and frames that could not be timed, since last asked.
         *
         * A timer that stops reading keeps printing its last answer, and a
         * number that never changes looks exactly like a measurement. The count
         * beside the time is what makes the difference visible.
         */
        private long reads;
        private long skipped;

        void begin() {
            if (unavailable) {
                return;
            }
            if (query[0] == 0) {
                if (!GL.getCapabilities().OpenGL33) {
                    unavailable = true;
                    return;
                }
                for (int i = 0; i < SLOTS; i++) {
                    query[i] = GL15C.glGenQueries();
                }
            }
            collect();
            active = -1;
            for (int i = 0; i < SLOTS; i++) {
                if (!pending[i]) {
                    active = i;
                    break;
                }
            }
            if (active < 0) {
                // Every slot still owes an answer. Timing this frame would mean
                // discarding one of them, which is how the old version came to
                // print the same number for ten minutes.
                skipped++;
                return;
            }
            GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, query[active]);
        }

        void end() {
            if (unavailable || active < 0) {
                return;
            }
            GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
            pending[active] = true;
            active = -1;
        }

        private void collect() {
            for (int i = 0; i < SLOTS; i++) {
                if (pending[i]
                        && GL15C.glGetQueryObjecti(query[i], GL15C.GL_QUERY_RESULT_AVAILABLE) != 0) {
                    nanos = GL33C.glGetQueryObjecti64(query[i], GL15C.GL_QUERY_RESULT);
                    pending[i] = false;
                    reads++;
                }
            }
        }

        /** Milliseconds, or -1 when the driver will not count for us. */
        double millis() {
            return unavailable ? -1.0 : nanos / 1_000_000.0;
        }

        /** Reads taken and frames left untimed, since the last time this was asked. */
        String health() {
            long r = reads;
            long s = skipped;
            reads = 0;
            skipped = 0;
            return (r == 0 ? "STALE, no result collected" : r + " reads")
                    + (s > 0 ? ", " + s + " frames untimed (all slots busy)" : "");
        }

        void destroy() {
            if (query[0] != 0) {
                for (int i = 0; i < SLOTS; i++) {
                    GL15C.glDeleteQueries(query[i]);
                    query[i] = 0;
                    pending[i] = false;
                }
            }
            active = -1;
        }
    }

    /** Read-only FBO wrapping the shared depth texture; -1 if incomplete. */
    private int createDepthReadFbo() {
        int prevRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int fbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11C.GL_TEXTURE_2D, glDepthTexture, 0);
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_READ_FRAMEBUFFER);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevRead);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.warn("Depth blit FBO incomplete (0x{}), falling back to gl_FragDepth composite",
                    Integer.toHexString(status));
            GL30C.glDeleteFramebuffers(fbo);
            return -1;
        }
        return fbo;
    }

    /** out: image, memory, view, allocationSize */
    private void createExportedTarget(MemoryStack stack, int format, int usage, int aspect, long[] out) {
        VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
                .handleTypes(Interop.MEMORY_HANDLE_TYPE);
        VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(external.address())
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        imageInfo.extent().width(width).height(height).depth(1);
        LongBuffer pImage = stack.mallocLong(1);
        check(vkCreateImage(device(), imageInfo, null, pImage), "vkCreateImage(target)");
        long image = pImage.get(0);

        VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
        vkGetImageMemoryRequirements(device(), image, req);
        VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
                .image(image);
        VkExportMemoryAllocateInfo export = VkExportMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
                .pNext(dedicated.address())
                .handleTypes(Interop.MEMORY_HANDLE_TYPE);
        VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .pNext(Interop.appendWin32MemoryRights(stack, export.address()))
                .allocationSize(req.size())
                .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
        LongBuffer pMemory = stack.mallocLong(1);
        check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(target)");
        long memory = pMemory.get(0);
        check(vkBindImageMemory(device(), image, memory, 0), "vkBindImageMemory(target)");

        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
        viewInfo.subresourceRange()
                .aspectMask(aspect)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        LongBuffer pView = stack.mallocLong(1);
        check(vkCreateImageView(device(), viewInfo, null, pView), "vkCreateImageView(target)");

        out[0] = image;
        out[1] = memory;
        out[2] = pView.get(0);
        out[3] = req.size();
    }

    private int importMemoryToGL(MemoryStack stack, long memory, long size) {
        // The targets are allocated with VkMemoryDedicatedAllocateInfo, so GL
        // must be told so before the import or it assumes a different memory
        // layout and reads garbage (small images happen to match, large ones
        // do not) — Interop.importMemoryToGL does that for us.
        return Interop.importMemoryToGL(stack, device(), memory, size, true);
    }

    private int createGlTexture(int memoryObject, int internalFormat) {
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, EXTMemoryObject.GL_TEXTURE_TILING_EXT,
                EXTMemoryObject.GL_OPTIMAL_TILING_EXT);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        // Held at the edge rather than repeated, and OpenGL's own default is the
        // reason this has to be said out loud: a fresh texture wraps. Nothing
        // noticed while every read was a composite reading the pixel under
        // itself, but a pass that looks at a neighbour can ask for one past the
        // edge of the screen — and a wrapping texture answers with the far side
        // of the picture, so occlusion at the left edge was being decided by
        // whatever stood at the right. Anything that samples with an offset
        // later — motion vectors, reflections — would have inherited it.
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        EXTMemoryObject.glTexStorageMem2DEXT(GL11C.GL_TEXTURE_2D, 1, internalFormat,
                width, height, memoryObject, 0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
        int error = GL11C.glGetError();
        if (error != 0) {
            throw new IllegalStateException("GL error 0x" + Integer.toHexString(error)
                    + " importing terrain target (format 0x" + Integer.toHexString(internalFormat) + ")");
        }
        return texture;
    }

    private int importSemaphore(MemoryStack stack, long vkSemaphore) {
        return Interop.importSemaphoreToGL(stack, device(), vkSemaphore);
    }

    /**
     * Tells GL which fence value the next wait or signal on {@code glSem}
     * targets. Required before every {@code glWaitSemaphoreEXT}/
     * {@code glSignalSemaphoreEXT} call when the semaphore was imported as a
     * D3D12 fence handle; a no-op on the opaque Win32 path and on Linux, where
     * GL derives the value itself from the binary semaphore's own state.
     */
    private static void setFenceValue(int glSem, long value) {
        if (Interop.D3D12_FENCE_SEMAPHORES) {
            EXTSemaphore.glSemaphoreParameterui64EXT(glSem,
                    org.lwjgl.opengl.EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT, value);
        }
    }

    private void ensureQuadIndexCapacity(int quads) {
        quads = Math.max(quads, 4096);
        if (quads <= quadIndexCapacityQuads) {
            return;
        }
        quads = Integer.highestOneBit(quads) * 2; // headroom: chunks keep growing
        if (quadIndexBuffer != 0) {
            // Every frame still in flight names this buffer, and one of them is
            // the translucent pass, whose fence is not the one the opaque frame
            // waited on. Destroying it here without stopping the device is a
            // buffer freed while a command buffer is reading it — which the
            // validation layer reports and a driver is free to fault on. It
            // happens a handful of times a session.
            vkDeviceWaitIdle(device());
            vkDestroyBuffer(device(), quadIndexBuffer, null);
            vkFreeMemory(device(), quadIndexMemory, null);
        }
        try (MemoryStack stack = stackPush()) {
            long byteSize = quads * 6L * 4L;
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(byteSize)
                    // The same indices an acceleration structure is built from,
                    // which needs its address rather than the binding — and the
                    // flag for that can only be asked for at creation.
                    .usage(VK_BUFFER_USAGE_INDEX_BUFFER_BIT
                            | (ctx.isRayTracingEnabled()
                            ? org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                            | org.lwjgl.vulkan.KHRAccelerationStructure
                                    .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            : 0))
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device(), bufferInfo, null, pBuffer), "vkCreateBuffer(quad index)");
            quadIndexBuffer = pBuffer.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), quadIndexBuffer, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            if (ctx.isRayTracingEnabled()) {
                alloc.pNext(org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                        .flags(org.lwjgl.vulkan.VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT)
                        .address());
            }
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(quad index)");
            quadIndexMemory = pMemory.get(0);
            check(vkBindBufferMemory(device(), quadIndexBuffer, quadIndexMemory, 0),
                    "vkBindBufferMemory(quad index)");

            PointerBuffer ppData = stack.mallocPointer(1);
            check(vkMapMemory(device(), quadIndexMemory, 0, byteSize, 0, ppData), "vkMapMemory(quad index)");
            long addr = ppData.get(0);
            for (int q = 0; q < quads; q++) {
                long base = addr + q * 24L;
                int v = q * 4;
                MemoryUtil.memPutInt(base, v);
                MemoryUtil.memPutInt(base + 4, v + 1);
                MemoryUtil.memPutInt(base + 8, v + 2);
                MemoryUtil.memPutInt(base + 12, v);
                MemoryUtil.memPutInt(base + 16, v + 2);
                MemoryUtil.memPutInt(base + 20, v + 3);
            }
            vkUnmapMemory(device(), quadIndexMemory);
        }
        quadIndexCapacityQuads = quads;
        LOGGER.info("Quad index buffer sized for {} quads", quads);
    }

    private void createLightmapResources() {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().width(LIGHTMAP_SIZE).height(LIGHTMAP_SIZE).depth(1);
            LongBuffer pImage = stack.mallocLong(1);
            check(vkCreateImage(device(), imageInfo, null, pImage), "vkCreateImage(lightmap)");
            lightmapImage = pImage.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device(), lightmapImage, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(lightmap)");
            lightmapMemory = pMemory.get(0);
            check(vkBindImageMemory(device(), lightmapImage, lightmapMemory, 0), "vkBindImageMemory(lightmap)");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(lightmapImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(device(), viewInfo, null, pView), "vkCreateImageView(lightmap)");
            lightmapView = pView.get(0);

            int stagingSize = LIGHTMAP_SIZE * LIGHTMAP_SIZE * 4;
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(stagingSize)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer ppData = stack.mallocPointer(1);
            for (int i = 0; i < framesInFlight; i++) {
                check(vkCreateBuffer(device(), bufferInfo, null, pBuffer), "vkCreateBuffer(lightmap)");
                lightmapStagingBuffer[i] = pBuffer.get(0);
                vkGetBufferMemoryRequirements(device(), lightmapStagingBuffer[i], req);
                alloc = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(req.size())
                        .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(),
                                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
                check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(lightmap staging)");
                lightmapStagingMemory[i] = pMemory.get(0);
                check(vkBindBufferMemory(device(), lightmapStagingBuffer[i], lightmapStagingMemory[i], 0),
                        "vkBindBufferMemory(lightmap)");
                check(vkMapMemory(device(), lightmapStagingMemory[i], 0, stagingSize, 0, ppData),
                        "vkMapMemory(lightmap)");
                lightmapStagingMapped[i] = ppData.get(0);
            }
            lightmapReadBuffer = MemoryUtil.memAlloc(stagingSize);
        }
    }

    /** Uploads pixels into a new device-local sampled image via a one-time submit. */
    /**
     * Creates a device-local sampled image and fills every supplied mip level
     * (index 0 is full size, each next one half). Uploads through one staging
     * buffer per level and blocks until done — only ever called on atlas load.
     */
    private void createSampledImage(MemoryStack stack, int imgWidth, int imgHeight, ByteBuffer[] levels,
                                    long[] out) {
        int mipLevels = levels.length;
        VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .mipLevels(mipLevels)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        imageInfo.extent().width(imgWidth).height(imgHeight).depth(1);
        LongBuffer pImage = stack.mallocLong(1);
        check(vkCreateImage(device(), imageInfo, null, pImage), "vkCreateImage(sampled)");
        long image = pImage.get(0);

        VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
        vkGetImageMemoryRequirements(device(), image, req);
        VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
        LongBuffer pMemory = stack.mallocLong(1);
        check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(sampled)");
        long memory = pMemory.get(0);
        check(vkBindImageMemory(device(), image, memory, 0), "vkBindImageMemory(sampled)");

        // Staging upload, one buffer per mip level
        long[] stagingBuffers = new long[mipLevels];
        long[] stagingMemories = new long[mipLevels];
        LongBuffer pBuffer = stack.mallocLong(1);
        LongBuffer pSMemory = stack.mallocLong(1);
        PointerBuffer ppData = stack.mallocPointer(1);
        for (int level = 0; level < mipLevels; level++) {
            int byteCount = levels[level].remaining();
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(byteCount)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateBuffer(device(), bufferInfo, null, pBuffer), "vkCreateBuffer(staging)");
            stagingBuffers[level] = pBuffer.get(0);
            VkMemoryRequirements sreq = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), stagingBuffers[level], sreq);
            VkMemoryAllocateInfo salloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(sreq.size())
                    .memoryTypeIndex(findMemoryType(stack, sreq.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            check(vkAllocateMemory(device(), salloc, null, pSMemory), "vkAllocateMemory(staging)");
            stagingMemories[level] = pSMemory.get(0);
            check(vkBindBufferMemory(device(), stagingBuffers[level], stagingMemories[level], 0),
                    "vkBindBufferMemory(staging)");
            check(vkMapMemory(device(), stagingMemories[level], 0, byteCount, 0, ppData), "vkMapMemory(staging)");
            MemoryUtil.memCopy(MemoryUtil.memAddress(levels[level]), ppData.get(0), byteCount);
            vkUnmapMemory(device(), stagingMemories[level]);
        }

        // One-time command: transition, copy, transition
        ensureBaseResources();
        check(vkWaitForFences(device(), fence, true, 1_000_000_000L), "vkWaitForFences(atlas)");
        vkResetFences(device(), fence);
        vkResetCommandBuffer(commandBuffer, 0);
        VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer(atlas)");

        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.get(0).subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        for (int level = 0; level < mipLevels; level++) {
            final int levelWidth = Math.max(1, imgWidth >> level);
            final int levelHeight = Math.max(1, imgHeight >> level);
            region.get(0).imageExtent(e -> e.width(levelWidth).height(levelHeight).depth(1));
            region.get(0).imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).baseArrayLayer(0).layerCount(1);
            vkCmdCopyBufferToImage(commandBuffer, stagingBuffers[level], image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }

        barrier.get(0)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier);

        check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer(atlas)");
        VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(commandBuffer));
        check(vkQueueSubmit(ctx.getGraphicsQueue(), submit, fence), "vkQueueSubmit(atlas)");
        check(vkWaitForFences(device(), fence, true, 5_000_000_000L), "vkWaitForFences(atlas upload)");
        vkResetFences(device(), fence);
        // Leave the fence signaled for the frame loop's initial wait
        VkSubmitInfo empty = VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
        check(vkQueueSubmit(ctx.getGraphicsQueue(), empty, fence), "vkQueueSubmit(fence reprime)");

        for (int level = 0; level < mipLevels; level++) {
            vkDestroyBuffer(device(), stagingBuffers[level], null);
            vkFreeMemory(device(), stagingMemories[level], null);
        }

        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_UNORM);
        viewInfo.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
        LongBuffer pView = stack.mallocLong(1);
        check(vkCreateImageView(device(), viewInfo, null, pView), "vkCreateImageView(sampled)");

        out[0] = image;
        out[1] = memory;
        out[2] = pView.get(0);
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    private void destroySprites() {
        forgetSpriteSheets();
        if (spriteVertexBuffers != null) {
            for (int i = 0; i < spriteVertexBuffers.length; i++) {
                destroySpriteVertexBuffer(i);
            }
            spriteVertexBuffers = null;
        }
        if (spritePipeline != 0) {
            vkDestroyPipeline(device(), spritePipeline, null);
            spritePipeline = 0;
        }
        if (creatureGlintPipeline != 0) {
            vkDestroyPipeline(device(), creatureGlintPipeline, null);
            creatureGlintPipeline = 0;
        }
        if (spriteOpaquePipeline != 0) {
            vkDestroyPipeline(device(), spriteOpaquePipeline, null);
            spriteOpaquePipeline = 0;
        }
        if (creaturePipeline != 0) {
            vkDestroyPipeline(device(), creaturePipeline, null);
            creaturePipeline = 0;
        }
        if (spritePipelineLayout != 0) {
            vkDestroyPipelineLayout(device(), spritePipelineLayout, null);
            spritePipelineLayout = 0;
        }
        if (spriteDescriptorPool != 0) {
            vkDestroyDescriptorPool(device(), spriteDescriptorPool, null);
            spriteDescriptorPool = 0;
            java.util.Arrays.fill(spriteSets, 0L);
        }
        if (spriteSetLayout != 0) {
            vkDestroyDescriptorSetLayout(device(), spriteSetLayout, null);
            spriteSetLayout = 0;
        }
        if (spriteSampler != 0) {
            vkDestroySampler(device(), spriteSampler, null);
            spriteSampler = 0;
        }
        if (spriteScratch != null) {
            MemoryUtil.memFree(spriteScratch);
            spriteScratch = null;
        }
        clearSprites();
    }

    private void destroyAtlas() {
        // Before anything is freed, and not only before the image is.
        //
        // The staging buffer used to be referenced by a submission this thread
        // had already waited on, so freeing it here could not race anything.
        // It is now read by a copy recorded into the frame's own command
        // buffer, which may be running on the card at this moment — and a
        // resource reload calls this from the game thread, where holding the
        // renderer's lock says nothing about what the card is doing. Freeing
        // memory a copy is reading from is a fault in the driver, not an
        // exception here.
        if (atlasImage != 0 || atlasStagingBuffer != null) {
            vkDeviceWaitIdle(device());
        }
        destroyAtlasStaging();
        if (atlasImage != 0) {
            vkDestroyImageView(device(), atlasView, null);
            vkDestroyImage(device(), atlasImage, null);
            vkFreeMemory(device(), atlasMemory, null);
            atlasImage = 0;
        }
    }

    /**
     * True when the calling thread has a GL context (the client thread). The
     * JVM shutdown hook has none — GL calls there abort the whole JVM.
     */
    private static boolean glContextCurrent() {
        try {
            org.lwjgl.opengl.GL.getCapabilities();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void destroyTargets() {
        if (colorImage == 0) {
            return;
        }
        vkDeviceWaitIdle(device());
        if (glColorTexture != -1 && glContextCurrent()) {
            // Sized from this target, so they go with it.
            destroyBloomTargets();
            destroyAoTargets();
            destroyAccumTargets();
            destroyToneTargets();
            destroySceneOcclusionTargets();
            destroyRayTargets();
            GL11C.glDeleteTextures(glColorTexture);
            GL11C.glDeleteTextures(glDepthTexture);
            EXTMemoryObject.glDeleteMemoryObjectsEXT(glColorMemoryObject);
            EXTMemoryObject.glDeleteMemoryObjectsEXT(glDepthMemoryObject);
            if (glTranslucentTexture != -1) {
                GL11C.glDeleteTextures(glTranslucentTexture);
                EXTMemoryObject.glDeleteMemoryObjectsEXT(glTranslucentMemoryObject);
            }
        }
        glTranslucentTexture = -1;
        if (glDepthBlitFbo != -1 && glContextCurrent()) {
            GL30C.glDeleteFramebuffers(glDepthBlitFbo);
        }
        glDepthBlitFbo = -1;
        if (glContextCurrent()) {
            destroyDepthImportTargets();
            compositeTimer.destroy();
            compositeWaitTimer.destroy();
            depthBlitTimer.destroy();
            depthImportTimer.destroy();
        } else {
            gameDepthTexture = 0;
            glDepthWriteFbo = -1;
        }
        depthBlit = false;
        glColorTexture = -1;
        glDepthTexture = -1;
        // Wait for OpenGL as well, before any of the memory underneath it is
        // handed back.
        //
        // vkDeviceWaitIdle above waits for Vulkan and for nothing else, and
        // the images below are shared: OpenGL has been sampling them all
        // frame, and its own work is queued on the same card. Deleting a GL
        // texture is safe — the driver keeps the object alive until its
        // commands are done — but freeing the Vulkan memory that texture was
        // built on is not, because nothing told Vulkan that GL is still
        // reading it. This runs whenever the window changes size, and the two
        // crashes this project has had were both of exactly this shape: memory
        // released while something still named it.
        //
        // A full stall, and it costs nothing worth counting: the only paths
        // here are a resize, a resource reload and shutting down.
        if (glContextCurrent()) {
            GL11C.glFinish();
        }
        vkDestroyFramebuffer(device(), framebuffer, null);
        if (translucentFramebuffer != 0) {
            vkDestroyFramebuffer(device(), translucentFramebuffer, null);
            vkDestroyImageView(device(), translucentView, null);
            vkDestroyImage(device(), translucentImage, null);
            vkFreeMemory(device(), translucentMemory, null);
            translucentFramebuffer = 0;
            translucentImage = 0;
        }
        vkDestroyImageView(device(), colorView, null);
        vkDestroyImage(device(), colorImage, null);
        vkFreeMemory(device(), colorMemory, null);
        vkDestroyImageView(device(), depthView, null);
        vkDestroyImage(device(), depthImage, null);
        vkFreeMemory(device(), depthMemory, null);
        if (readbackBuffer != 0) {
            vkUnmapMemory(device(), readbackMemory);
            vkDestroyBuffer(device(), readbackBuffer, null);
            vkFreeMemory(device(), readbackMemory, null);
            readbackBuffer = 0;
        }
        colorImage = 0;
    }

    synchronized void destroy() {
        if (!baseReady) {
            return;
        }
        vkDeviceWaitIdle(device());
        if (rayTracing != null) {
            rayTracing.destroy();
            rayTracing = null;
        }
        destroyTargets();
        destroyAtlas();
        destroySprites();
        if (lightmapImage != 0) {
            vkDestroyImageView(device(), lightmapView, null);
            vkDestroyImage(device(), lightmapImage, null);
            vkFreeMemory(device(), lightmapMemory, null);
            for (int i = 0; i < framesInFlight; i++) {
                if (lightmapStagingMemory[i] != 0) {
                    vkUnmapMemory(device(), lightmapStagingMemory[i]);
                    vkDestroyBuffer(device(), lightmapStagingBuffer[i], null);
                    vkFreeMemory(device(), lightmapStagingMemory[i], null);
                    lightmapStagingBuffer[i] = 0;
                    lightmapStagingMemory[i] = 0;
                    lightmapStagingMapped[i] = 0;
                }
            }
            for (int i = 0; i < framesInFlight; i++) {
                if (frameUniformMemories[i] != 0) {
                    vkUnmapMemory(device(), frameUniformMemories[i]);
                    vkDestroyBuffer(device(), frameUniformBuffers[i], null);
                    vkFreeMemory(device(), frameUniformMemories[i], null);
                    frameUniformBuffers[i] = 0;
                    frameUniformMemories[i] = 0;
                    frameUniformMapped[i] = 0;
                }
            }
            MemoryUtil.memFree(lightmapReadBuffer);
            lightmapReadBuffer = null;
            lightmapImage = 0;
        }
        if (quadIndexBuffer != 0) {
            vkDestroyBuffer(device(), quadIndexBuffer, null);
            vkFreeMemory(device(), quadIndexMemory, null);
            quadIndexBuffer = 0;
            quadIndexCapacityQuads = 0;
        }
        destroyDrawBatches();
        for (int i = 0; i < pipelines.length; i++) {
            if (pipelines[i] != 0) {
                vkDestroyPipeline(device(), pipelines[i], null);
                pipelines[i] = 0;
            }
            if (tracingPipelines[i] != 0) {
                vkDestroyPipeline(device(), tracingPipelines[i], null);
                tracingPipelines[i] = 0;
            }
        }
        // Saved here rather than at exit: the device is still alive, and this
        // is the last point at which the driver can be asked for the blob.
        pipelineCache.destroy(device());
        pipelineCacheHandle = VK_NULL_HANDLE;
        vkDestroyPipelineLayout(device(), pipelineLayout, null);
        if (translucentRenderPass != 0) {
            vkDestroyRenderPass(device(), translucentRenderPass, null);
            translucentRenderPass = 0;
        }
        vkDestroyRenderPass(device(), renderPass, null);
        vkDestroyDescriptorPool(device(), descriptorPool, null);
        if (sceneSampler != 0) {
            vkDestroySampler(device(), sceneSampler, null);
            sceneSampler = 0;
        }
        vkDestroyDescriptorSetLayout(device(), descriptorSetLayout, null);
        vkDestroySampler(device(), atlasSampler, null);
        vkDestroySampler(device(), lightmapSampler, null);
        vkDestroySemaphore(device(), vkSignalSemaphore, null);
        vkDestroySemaphore(device(), vkWaitSemaphore, null);
        if (vkTranslucentSignalSemaphore != 0) {
            vkDestroySemaphore(device(), vkTranslucentSignalSemaphore, null);
            vkDestroySemaphore(device(), vkTranslucentWaitSemaphore, null);
            vkTranslucentSignalSemaphore = 0;
            vkTranslucentWaitSemaphore = 0;
        }
        if (fences != null) {
            for (long frameFence : fences) {
                vkDestroyFence(device(), frameFence, null);
            }
            fences = null;
        }
        if (translucentFences != null) {
            for (long frameFence : translucentFences) {
                vkDestroyFence(device(), frameFence, null);
            }
            translucentFences = null;
        }
        if (queryPool != 0) {
            vkDestroyQueryPool(device(), queryPool, null);
            queryPool = 0;
        }
        vkDestroyCommandPool(device(), commandPool, null);
        baseReady = false;
    }

    private int findMemoryType(MemoryStack stack, int typeBits, int properties) {
        int type = findMemoryTypeOrNone(stack, typeBits, properties);
        if (type < 0) {
            throw new IllegalStateException("No suitable memory type");
        }
        return type;
    }

    /** Same, but returns -1 instead of throwing, for properties that are a preference. */
    private int findMemoryTypeOrNone(MemoryStack stack, int typeBits, int properties) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(device().getPhysicalDevice(), memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                    && (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        return -1;
    }

    private long createShaderModule(MemoryStack stack, String resource) {
        byte[] code = readResource(resource);
        ByteBuffer buf = MemoryUtil.memAlloc(code.length);
        buf.put(code);
        buf.rewind();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(buf);
            LongBuffer pModule = stack.mallocLong(1);
            check(vkCreateShaderModule(device(), info, null, pModule), "vkCreateShaderModule " + resource);
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static byte[] readResource(String resource) {
        try (InputStream in = VkTerrainRenderer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Shader " + resource + " not found on classpath");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read shader " + resource, e);
        }
    }

    private static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(call + " failed with VkResult " + result);
        }
    }

}
