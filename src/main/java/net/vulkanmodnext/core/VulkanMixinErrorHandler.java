package net.vulkanmodnext.core;

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Runs when one of this mod's patches cannot be applied, and makes sure the
 * next launch is not the same as this one.
 *
 * <h2>What goes wrong, and why it is not obvious</h2>
 *
 * A patch fails when the code it was aimed at is no longer shaped the way it
 * was written against — usually because another mod got there first. The
 * failure is not contained: the target class does not finish transforming, the
 * launch classloader caches that, and everything that touches the class from
 * then on reports it as missing. What reaches the crash report is
 * {@code NoClassDefFoundError} for a vanilla class, which names neither the
 * patch, the feature, nor the mod. Removing mods one at a time does not isolate
 * it either, because whichever patch fails first produces the identical
 * message.
 *
 * <h2>What this does about it</h2>
 *
 * It writes the failing group down. On the next launch {@link VulkanMixinGate}
 * refuses that group before Mixin considers it, the class transforms cleanly,
 * and the game starts with one feature missing instead of not starting. The
 * settings screen then shows which group stood down, why, and offers to try it
 * again.
 *
 * <h2>Why it still lets the game stop</h2>
 *
 * Mixin allows this to downgrade the failure to a warning and carry on. That is
 * refused on purpose. By the time a patch fails, the other patches in its group
 * may already be merged into the class, and a group is written on the
 * assumption that all of it is there — capturing creatures with nothing to draw
 * them, replacing a shadow with one that is never cast. Carrying on would trade
 * a crash that says what happened for a session of effects half working, which
 * is both worse to play and far worse to report.
 *
 * So this launch still ends. It ends having fixed the next one, and having said
 * so in words, which is the whole difference.
 */
public class VulkanMixinErrorHandler implements IMixinErrorHandler {

    /** Only this mod's own patches are ours to stand down. */
    private static final String OUR_PACKAGE = "net.vulkanmodnext.mixin.";

    @Override
    public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin,
                                      ErrorAction action) {
        return handle(mixin, th, action);
    }

    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin,
                                    ErrorAction action) {
        return handle(mixin, th, action);
    }

    /**
     * @param action what Mixin was going to do; returned unchanged in every
     *               case, so this handler can only add a note and never alter
     *               the outcome of another mod's failure.
     */
    private static ErrorAction handle(IMixinInfo mixin, Throwable th, ErrorAction action) {
        try {
            String name = mixin == null ? null : mixin.getClassName();
            if (name == null || !name.startsWith(OUR_PACKAGE)) {
                return action;
            }
            String group = VulkanPatchGroups.of(name);
            String simple = name.substring(name.lastIndexOf('.') + 1);
            String reason = simple + ": " + firstLine(th);
            if (VulkanPatchGroups.isEssential(group)) {
                System.out.println("[VulkanModNext] " + reason + ". This patch is part of the "
                        + "renderer itself and cannot be switched off separately. Set "
                        + "terrainEnabled=false in config/vulkanmodnext.cfg to start without it, "
                        + "and please report this with the whole of logs/latest.log.");
                return action;
            }
            VulkanPatchState.quarantine(group, reason);
            System.out.println("[VulkanModNext] " + reason);
            System.out.println("[VulkanModNext] " + VulkanPatchGroups.title(group)
                    + " could not be installed, so it has been switched off. Start the game "
                    + "again and it will load without it. " + VulkanPatchGroups.cost(group)
                    + " Settings, Vulkan, Class Patches can turn it back on.");
        } catch (Throwable failure) {
            System.out.println("[VulkanModNext] Could not record the failed patch. " + failure);
        }
        return action;
    }

    /** The message, without the stack trace that is already in the log. */
    private static String firstLine(Throwable th) {
        if (th == null) {
            return "unknown failure";
        }
        String message = th.getMessage();
        if (message == null || message.isEmpty()) {
            return th.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
