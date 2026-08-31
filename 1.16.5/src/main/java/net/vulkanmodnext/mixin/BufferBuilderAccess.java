package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Reads what a chunk builder has produced without taking it.
 *
 * The game hands a finished chunk over with {@code popNextBuffer}, which is
 * destructive: it advances a cursor and, on the last layer, clears the builder.
 * Calling it ourselves would take the geometry away from the upload that was
 * about to happen. So the same three numbers it uses are read directly, and
 * nothing moves.
 *
 * <p>What it reads: the bytes of the next layer live at
 * {@code [totalUploadedBytes, totalUploadedBytes + vertexCount * stride)}, and
 * the vertex count and format come from the draw state at
 * {@code lastRenderedCountIndex} — exactly the arithmetic {@code popNextBuffer}
 * performs one line later.
 */
@Mixin(BufferBuilder.class)
public interface BufferBuilderAccess {

    @Accessor("buffer")
    ByteBuffer vulkanmodnext$buffer();

    @Accessor("vertexCounts")
    List<BufferBuilder.DrawState> vulkanmodnext$drawStates();

    @Accessor("lastRenderedCountIndex")
    int vulkanmodnext$nextDrawState();

    @Accessor("totalUploadedBytes")
    int vulkanmodnext$uploadedBytes();
}
