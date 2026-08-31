package net.vulkanmodnext.core;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * The last chance to not patch a class.
 *
 * Mixin asks this about every patch in both of this mod's configs, one at a
 * time, just before deciding to apply it. Answering no here is the only kind of
 * standing down that carries no risk at all: the patch is never merged, never
 * validated against the target, never able to fail — the class is left exactly
 * as whoever else is rewriting it left it.
 *
 * <h2>What it says no to</h2>
 *
 * <ul>
 * <li>A group the user switched off in the settings screen.</li>
 * <li>A group that failed on an earlier launch and has not been asked for
 *     again — see {@link VulkanMixinErrorHandler}.</li>
 * </ul>
 *
 * Core patches are never refused. There is no state in which it helps to have
 * half a Vulkan renderer installed, and the mod already has a switch for the
 * whole of it that does not need a restart.
 *
 * <h2>Why this must not throw</h2>
 *
 * It runs inside class transformation. An exception here does not report itself
 * as a bug in this file; it reports itself as the target class being missing,
 * which is the exact failure this whole mechanism exists to prevent. So every
 * answer is wrapped, and the fallback answer is yes — behave as the mod did
 * before this file existed.
 */
public class VulkanMixinGate implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        try {
            String group = VulkanPatchGroups.of(mixinClassName);
            if (VulkanPatchGroups.isEssential(group)) {
                return true;
            }
            String quarantined = VulkanPatchState.quarantineReason(group);
            if (quarantined != null) {
                announce(group, "stood down after a failed patch (" + quarantined + ")");
                return false;
            }
            if (!VulkanPatchState.isEnabled(group)) {
                announce(group, "switched off in the settings screen");
                return false;
            }
            return true;
        } catch (Throwable failure) {
            System.out.println("[VulkanModNext] Patch gate failed for " + mixinClassName
                    + "; applying it as normal. " + failure);
            return true;
        }
    }

    /** Says it once per group rather than once per patch. */
    private static void announce(String group, String why) {
        if (VulkanPatchState.wasApplied(group)) {
            System.out.println("[VulkanModNext] " + VulkanPatchGroups.title(group)
                    + " is not installed this launch: " + why + ". "
                    + "Settings, Vulkan, Class Patches has the switch.");
        }
        VulkanPatchState.recordSkipped(group, why);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }
}
