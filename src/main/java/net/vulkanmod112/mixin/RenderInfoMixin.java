package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.EnumFacing;
import net.vulkanmod112.client.RenderInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Opens {@code RenderGlobal.ContainerLocalRenderInformation} up for reuse.
 *
 * The class is targeted by string because it is package-private and cannot be
 * named from here, and it is handed out to our code as {@link RenderInfo} for
 * the same reason.
 *
 * Three of its four fields are final. That is the right shape for vanilla,
 * which allocates one of these per chunk the search reaches and drops the lot
 * on the next frame — some four thousand objects a walk, a hundred walks a
 * second. A search that reuses them has to write those fields again, so
 * {@link Mutable} takes the final off. Nothing else in the game writes them,
 * and the objects our pool owns never escape into vanilla's own allocation
 * path, so the guarantee being given up is one we then keep ourselves.
 */
@Mixin(targets = "net.minecraft.client.renderer.RenderGlobal$ContainerLocalRenderInformation")
public abstract class RenderInfoMixin implements RenderInfo {

    @Shadow
    @Final
    @Mutable
    RenderChunk renderChunk;

    @Shadow
    @Final
    @Mutable
    EnumFacing facing;

    @Shadow
    @Final
    @Mutable
    int counter;

    @Shadow
    byte setFacing;

    /**
     * One more than the grid slot, so an untouched zero reads as "not known".
     * The game allocates these records itself on any frame our search hands
     * back, and reading their zero as the corner of the grid would answer a
     * question about the wrong chunk.
     */
    @Unique
    private int vulkanmod112$slotPlusOne;

    @Override
    public int vulkanmod112$gridSlot() {
        return this.vulkanmod112$slotPlusOne - 1;
    }

    @Override
    public void vulkanmod112$setGridSlot(int slot) {
        this.vulkanmod112$slotPlusOne = slot + 1;
    }

    @Override
    public RenderChunk vulkanmod112$chunk() {
        return this.renderChunk;
    }

    @Override
    public EnumFacing vulkanmod112$facing() {
        return this.facing;
    }

    @Override
    public void vulkanmod112$reset(RenderChunk chunk, EnumFacing facingIn, int counterIn) {
        this.renderChunk = chunk;
        this.facing = facingIn;
        this.counter = counterIn;
        this.setFacing = 0;
        this.vulkanmod112$slotPlusOne = 0;
    }

    @Override
    public void vulkanmod112$setDirection(byte parentFacing, EnumFacing step) {
        this.setFacing = (byte) (this.setFacing | parentFacing | 1 << step.ordinal());
    }

    @Override
    public boolean vulkanmod112$hasDirection(EnumFacing direction) {
        return (this.setFacing & 1 << direction.ordinal()) > 0;
    }

    @Override
    public byte vulkanmod112$facingMask() {
        return this.setFacing;
    }
}
