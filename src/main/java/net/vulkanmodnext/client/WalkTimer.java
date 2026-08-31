package net.vulkanmodnext.client;

/**
 * Lets a walk that did not come from vanilla's own code still be recorded.
 *
 * The throttle in {@code VisibilityWalkMixin} times the interval from when a
 * walk last ran, and it learns that from vanilla clearing its dirty flag. A
 * replacement search skips that instruction entirely, so without this the
 * throttle would measure a stale interval forever and let every suppressible
 * request through.
 */
public interface WalkTimer {

    void vulkanmodnext$noteWalkRan();
}
