package net.vulkanmodnext;

/**
 * A refusal to start the Vulkan renderer that already carries its own
 * explanation, as opposed to something that merely went wrong.
 *
 * The distinction is what the player is shown. Most failures here are best
 * described by their innermost cause, because the outer layers are this mod's
 * own plumbing wrapping somebody else's real error. A few are the reverse: the
 * root is a library throw with no context — "Out of stack space" — and the
 * sentence worth reading is the one written where the limit is known. Marking
 * those lets the F3 line pick the right end of the chain instead of guessing
 * by depth.
 *
 * Deliberately in this package and not in vkimpl: the isolated Vulkan
 * classloader delegates everything outside org.lwjgl and vkimpl to the game's
 * loader, so this is one type on both sides rather than two that only share a
 * name and would never match an instanceof across the boundary.
 */
public class VulkanUnavailableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public VulkanUnavailableException(String message) {
        super(message);
    }

    public VulkanUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

}
