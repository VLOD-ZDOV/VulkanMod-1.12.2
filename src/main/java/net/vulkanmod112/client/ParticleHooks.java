package net.vulkanmod112.client;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.vulkanmod112.VulkanBridge;

import java.util.Queue;

/**
 * The particle loop, with the draw redirected into Vulkan.
 *
 * This is vanilla's {@code ParticleManager.renderParticles} with two things
 * taken out and nothing added: the OpenGL state changes, which are pointless
 * when nothing is drawn in OpenGL, and the texture binds, for the same reason.
 * Every particle still builds its own vertices through its own
 * {@code renderParticle}, so a mod that adds a particle type is drawn by its
 * own code exactly as before.
 *
 * <p>The six queues are kept apart and submitted in vanilla's order. Two of the
 * three texture layers matter — the particle sheet and the block atlas, for the
 * cubes a broken block throws off — and the third is vanilla's own fall-through
 * to the particle sheet.
 *
 * <p>What is lost, and it is worth saying rather than discovering: vanilla
 * writes depth for the second queue of each layer, so those particles occlude
 * each other. The pass these are drawn in borrows the game's depth buffer
 * read-only and hands it straight back, so nothing written here could survive
 * anyway. In practice this shows up as two overlapping particles blending
 * instead of one hiding the other, at a scale of a few pixels.
 */
public final class ParticleHooks {

    private ParticleHooks() {
    }

    /**
     * @return true when Vulkan took the particles and the game must not draw them
     */
    public static boolean render(Queue<Particle>[][] fxLayers, Entity viewer, float partialTicks) {
        VulkanBridge bridge = SpriteHooks.target(VulkanConfig.isVulkanParticles());
        if (bridge == null || fxLayers == null || fxLayers.length < 3) {
            return false;
        }
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder builder = tessellator.getBuffer();
        boolean building = false;
        try {
            // The camera basis every particle turns itself to face, and the
            // camera position every particle's vertices are measured from. Both
            // are static fields the game sets here and the particles read; the
            // order is vanilla's and must not change.
            float rotX = ActiveRenderInfo.getRotationX();
            float rotZ = ActiveRenderInfo.getRotationZ();
            float rotYZ = ActiveRenderInfo.getRotationYZ();
            float rotXY = ActiveRenderInfo.getRotationXY();
            float rotXZ = ActiveRenderInfo.getRotationXZ();
            Particle.interpPosX = viewer.lastTickPosX
                    + (viewer.posX - viewer.lastTickPosX) * (double) partialTicks;
            Particle.interpPosY = viewer.lastTickPosY
                    + (viewer.posY - viewer.lastTickPosY) * (double) partialTicks;
            Particle.interpPosZ = viewer.lastTickPosZ
                    + (viewer.posZ - viewer.lastTickPosZ) * (double) partialTicks;
            Particle.cameraViewDir = viewer.getLook(partialTicks);

            for (int layer = 0; layer < 3; layer++) {
                for (int queueIndex = 0; queueIndex < 2; queueIndex++) {
                    Queue<Particle> queue = fxLayers[layer][queueIndex];
                    if (queue == null || queue.isEmpty()) {
                        continue;
                    }
                    // Vanilla's switch: layer 1 is the block atlas, everything
                    // else falls through to the particle sheet.
                    int slot = layer == 1 ? SpriteHooks.SLOT_BLOCK_ATLAS : SpriteHooks.SLOT_PARTICLES;
                    builder.begin(7, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
                    building = true;
                    for (Particle particle : queue) {
                        particle.renderParticle(builder, viewer, partialTicks,
                                rotX, rotXZ, rotZ, rotYZ, rotXY);
                    }
                    SpriteHooks.submit(bridge, builder, slot, SpriteHooks.PARTICLE_CUTOFF);
                    building = false;
                }
            }
            return true;
        } catch (Throwable t) {
            // The builder is shared with the rest of the game and left half
            // open would take down the next thing to use it, which would be
            // reported as a fault in whatever that was.
            if (building) {
                try {
                    builder.finishDrawing();
                    builder.reset();
                } catch (Throwable ignored) {
                    // Nothing better to try; the failure below is the real one.
                }
            }
            SpriteHooks.fail("Drawing particles", t);
            // Vanilla draws them this frame. Its own loop starts from an empty
            // builder and reads the same queues, so nothing is lost but the
            // work already done.
            return false;
        }
    }
}
