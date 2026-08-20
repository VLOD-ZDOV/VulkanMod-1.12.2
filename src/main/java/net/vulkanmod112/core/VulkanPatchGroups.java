package net.vulkanmod112.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of this mod's class patches belong together, and which of them the mod
 * cannot do without.
 *
 * <h2>Why this exists</h2>
 *
 * A mixin that cannot be applied does not fail alone. The class it was aimed at
 * fails to transform, the launch classloader remembers that failure, and every
 * later attempt to load that class reports it as missing — so a broken patch on
 * {@code RenderGlobal} surfaces as {@code NoClassDefFoundError:
 * net/minecraft/client/renderer/RenderGlobal}, naming neither the patch nor the
 * mod that wrote it. Eleven of the patches below aim at that one class, and so
 * do Alfheim, VintageFix and LoliASM. Whoever loses that argument, the message
 * on screen is the same and points at nobody.
 *
 * A patch that has been switched off before class-load time cannot lose that
 * argument at all, and that is the whole idea here: give the user a switch that
 * removes a set of patches from consideration entirely, rather than a mod that
 * either works or does not start.
 *
 * <h2>Why a group and not a single patch</h2>
 *
 * Because half a feature is worse than none of it. Drawing creatures through
 * Vulkan takes six patches — one to know when the pass starts, three to read
 * the model, one to drop vanilla's blob shadow, one to catch the colour the
 * game lays over a creature that has been hurt — and five of six means
 * creatures captured and never drawn, or drawn without the shadow that was
 * supposed to replace the one just removed. So the unit that can be switched
 * off is the feature, and it goes off whole.
 *
 * <h2>Why not compare the class against a known-good checksum</h2>
 *
 * The obvious version of this is to hash the target class and stand down when
 * the hash is unfamiliar. It cannot work, for three separate reasons:
 *
 * <ul>
 * <li>The decision has to be made before any mixin is applied, and mixins from
 *     every mod are applied together in one pass. At the moment of the decision
 *     another mod's patches have not touched the bytes yet, so there is nothing
 *     to see.</li>
 * <li>There is no single "untouched" hash to compare against. Forge itself
 *     patches {@code RenderGlobal} through its binary patches, so the reference
 *     would differ per Forge build, per fork, and between the development and
 *     the shipped mappings.</li>
 * <li>It answers the wrong question. "Did somebody edit this class" is not
 *     "is the thing I need still here" — most edits are irrelevant to us, and a
 *     hash cannot tell the two apart, so it would stand down constantly and for
 *     no reason.</li>
 * </ul>
 *
 * What replaces it is in {@link VulkanMixinErrorHandler}: rather than guessing
 * in advance whether a patch will fit, the mod finds out, and remembers. A
 * group whose patch failed is written down and skipped on the next launch — the
 * one honest signal that a patch does not fit is that it did not fit.
 */
public final class VulkanPatchGroups {

    /** The Vulkan renderer itself. Without these there is nothing to switch. */
    public static final String CORE = "core";
    public static final String CULLING = "culling";
    public static final String ENTITIES = "entities";
    public static final String LIGHTING = "lighting";
    public static final String PARTICLES = "particles";
    public static final String SKY = "sky";
    public static final String TEXTURES = "textures";
    public static final String TWEAKS = "tweaks";

    /** Group name to the English title shown in the settings screen. */
    private static final Map<String, String> TITLES = new LinkedHashMap<String, String>();
    /** Group name to what turning it off costs, in one sentence. */
    private static final Map<String, String> COSTS = new LinkedHashMap<String, String>();
    /** Mixin simple name to the group it belongs to. */
    private static final Map<String, String> MEMBERS = new LinkedHashMap<String, String>();

    static {
        group(CORE, "Vulkan Terrain",
                "Everything else. Turning this off is turning the renderer off, which the "
                        + "Vulkan Terrain setting already does without restarting the game.",
                "ChunkRenderContainerMixin", "ChunkUploadMixin", "GameShutdownMixin",
                "GlMatrixMixin", "GuiVideoSettingsMixin", "LightmapCoordMixin",
                "MaterialTagMixin", "RenderChunkAccessor", "RenderChunkDirtyMixin",
                "RenderGlobalAccessor", "VboRenderListMixin", "VertexBufferMixin",
                "ViewFrustumAccessor", "ViewFrustumSlotMixin");
        group(CULLING, "Chunk Visibility",
                "The game goes back to deciding which chunks are on screen the way it always "
                        + "did. Nothing looks different; the frame costs more.",
                "FrustumTestMixin", "OwnVisibilityWalkMixin", "RenderInfoMixin",
                "VisibilityWalkCostMixin", "VisibilityWalkMixin");
        group(ENTITIES, "Creatures",
                "Creatures are drawn by the game in OpenGL. The Draw Creatures in Vulkan "
                        + "setting stops doing anything.",
                "BlobShadowMixin", "EntityPassMixin", "EntityOverlayMixin", "ModelBaseMixin",
                "ModelBoxAccessor",
                "ModelPartMixin");
        group(LIGHTING, "Dynamic Lights",
                "A torch in the hand or on the floor stops lighting what is around it. The "
                        + "world keeps the light the game itself calculates.",
                "EntityBrightnessMixin", "HeldItemLightMixin", "ParticleBrightnessMixin");
        group(PARTICLES, "Particles",
                "Particles are drawn by the game in OpenGL, one draw call each, as they "
                        + "always were.",
                "ExplosionParticleMixin", "ParticleRenderMixin", "TntRenderMixin");
        group(SKY, "Sky and Weather",
                "Sky, clouds, rain and the effects drawn over the finished frame — bloom, "
                        + "tone mapping and the fog distance slider — all stop.",
                "CloudTintMixin", "FogDistanceMixin", "SceneBloomMixin", "SkySunMixin",
                "WeatherRenderMixin");
        group(TEXTURES, "Animated Textures",
                "Animated blocks stop being captured for the Vulkan atlas, so water and lava "
                        + "stand still in the parts of the world this renderer draws.",
                "AtlasAnimationMixin", "TextureMapAnimationMixin");
        group(TWEAKS, "Optimisations",
                "The optional speed-ups switch off together: chunk pre-loading, the build "
                        + "thread count, the near plane, the entity distance caps and the "
                        + "diagnostics timers. Everything still draws.",
                "ChunkBuildThreadsMixin", "ChunkPreloadMixin", "EntityRenderDistanceMixin",
                "NearPlaneMixin", "RebuildNearMixin", "ResourcePackIconMixin",
                "TileEntityRenderDistanceMixin", "VanillaFrameMixin", "WorldDisplayMixin");
    }

    private VulkanPatchGroups() {
    }

    private static void group(String name, String title, String cost, String... members) {
        TITLES.put(name, title);
        COSTS.put(name, cost);
        for (String member : members) {
            MEMBERS.put(member, name);
        }
    }

    /** Every group, in the order the settings screen lists them. */
    public static List<String> names() {
        return Collections.unmodifiableList(new java.util.ArrayList<String>(TITLES.keySet()));
    }

    public static String title(String group) {
        String title = TITLES.get(group);
        return title != null ? title : group;
    }

    public static String cost(String group) {
        String cost = COSTS.get(group);
        return cost != null ? cost : "";
    }

    /**
     * The group a mixin belongs to, by its class name — simple or qualified.
     *
     * A patch that is not listed above counts as core, which is the safe way to
     * be wrong: a new patch nobody remembered to file here keeps working and is
     * simply not switchable, rather than silently ending up in a group the user
     * turned off for unrelated reasons.
     */
    public static String of(String mixinClassName) {
        if (mixinClassName == null) {
            return CORE;
        }
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        int inner = simple.lastIndexOf('$');
        if (inner >= 0) {
            simple = simple.substring(0, inner);
        }
        String group = MEMBERS.get(simple);
        return group != null ? group : CORE;
    }

    /** Whether this group has to be applied for the mod to mean anything. */
    public static boolean isEssential(String group) {
        return CORE.equals(group);
    }

}
