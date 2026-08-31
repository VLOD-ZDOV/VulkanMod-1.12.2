package net.vulkanmodnext;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * The earliest code this mod can run — which turned out not to be early enough
 * for the one thing it was written for.
 *
 * <p>It exists because the mixin config needs a plugin class anyway, and
 * because it is the right place to put anything that must happen before the
 * game starts. What it does <em>not</em> do is documented in the method below:
 * the attempt to enlarge LWJGL's scratch stack from here failed, and the
 * measurement that says so is worth more than the line that looked right.
 */
public final class EarlySetup implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        // Nothing is set here, and that is a finding rather than an omission.
        //
        // The obvious thing to do from the earliest hook a mod has is to ask
        // LWJGL for a bigger scratch stack through its system property. It was
        // tried, from exactly here — the main thread, before Minecraft is
        // launched — and it loses: the report read "property 2048, LWJGL heard
        // 64". Something in Forge's own startup touches LWJGL before any mod
        // hook exists. A request that measurably does nothing is worse than no
        // request, because the next person reads it as handled. See VkStack for
        // what is done instead.
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
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
