# How it works

This is the mechanism behind [README.md](README.md) and [ADVANCED.md](ADVANCED.md): the
path a chunk takes from being built to being on screen, the path a frame takes to reach the
display, and what happens when either one fails partway through.

The game still owns the window and the OpenGL context, still runs its own chunk builder
threads, and still draws everything this mod does not take: particles, the sky, block
entities such as chests and signs, weather, the hand and the interface. What this mod takes
is the four terrain layers, and — when a separate setting is on — living creatures. Nothing
here replaces the window, the input handling or the asset pipeline; it replaces one part of
the picture, and only while it is working.

---

## The path of a chunk

A chunk is built the way it always was: on the game's own worker threads, into the game's
own `BufferBuilder`, following vanilla's meshing exactly. Nothing about that process is
touched. What changes is what happens to the finished buffer.

In 1.12.2, every piece of world geometry — each render layer of each chunk section, plus the
sky and star meshes — passes through one method: `VertexBuffer#bufferData`. That is the only
choke point all of it goes through, so it is the only place that needs a hook. The mixin at
`src/main/java/net/vulkanmod112/mixin/VertexBufferMixin.java` intercepts it, and the mirror
takes its own copy of the data first, unconditionally, whatever happens next.

What happens next depends on a setting, **Drop Vanilla Chunk Buffers**, which is on by
default (`VulkanConfig.DEF_DROP_VANILLA_BUFFERS` is `true`). When it is on, vanilla's own
upload is cancelled outright: no `glBufferData`, no bind, nothing reaches the driver on the
OpenGL side at all. The vertex count is zeroed *before* the method is cancelled, not after —
if only the upload were skipped, the buffer would keep a nonzero count pointing at memory
that was never written, and any vanilla draw call that slipped past the renderer's own guards
would read past the end of an empty buffer. Zeroing the count first means the worst case is a
chunk that draws nothing, not a chunk that draws garbage. Vanilla's whole upload method is
four lines — bind, upload, unbind, set the count — and with the upload gone the binds have
nothing left to do either.

The practical result: the world's geometry exists in video memory **once**, not once per
renderer. The setting's own tooltip records a measured 900 MiB saved on one world at high
render distance. That saving is not just idle memory sitting unused — it also removes a
second upload from the budget the game reserves each frame for getting new chunks onto the
card, which is what actually decides how quickly the world fills in around the player. Two
renderers each wanting a turn at that budget would each get less of it; one renderer holding
the only copy gets all of it.

That saving is conditional, though, and the condition is worth stating plainly: dropping the
vanilla buffers is only safe once Vulkan is actually drawing the translucent layer too. Water
and glass live in the vanilla buffers and nowhere else until that layer goes through Vulkan;
emptying them before that would leave an ocean with nothing to draw it, in either renderer,
and nothing in any log to explain why. `updateVanillaBufferDrop()` in
`src/main/java/net/vulkanmod112/client/TerrainHooks.java` checks exactly that before turning
the drop on.

---

## The path of a frame

Vulkan draws the three opaque-ish layers — `SOLID`, `CUTOUT_MIPPED`, `CUTOUT` — into its own
colour and depth images, accumulated across the frame rather than drawn straight to the
screen. Those images are allocated on the Vulkan side, exported, and imported into OpenGL as
ordinary textures through `GL_EXT_memory_object_fd` (or the `_win32` variant on Windows) —
the same block of VRAM, read by both APIs, rather than a copy of it. A pair of semaphores
keeps the two sides from stepping on each other: Vulkan signals when a frame is ready and
OpenGL waits for that signal before sampling the texture; OpenGL signals back once it has
used the frame, and Vulkan waits for that before it starts overwriting the same memory with
the next one. `src/main/java/net/vulkanmod112/vkimpl/VkInteropRenderer.java` is where this
scaffolding is built and tested with a single triangle; the same mechanism, extended to a
full colour and depth target, is what `VkTerrainRenderer` composites the terrain through.

Sharing memory this way, instead of reading pixels back to the CPU and re-uploading them, is
the whole point of the design: a CPU round trip stalls the pipeline until the pixels have
crossed the bus twice, once down and once back up, and it has to happen every single frame
this renderer draws. An exported image with a pair of semaphores costs nothing per frame
beyond the wait itself — the data never leaves the card.

Once the opaque terrain is ready, OpenGL waits on the semaphore and draws the shared colour
and depth into the game's own framebuffer, in the game's own draw call — CUTOUT is the point
in vanilla's layer order (`SOLID → CUTOUT_MIPPED → CUTOUT`, then entities, then
`TRANSLUCENT`) where this composite happens, which is before entities so they occlude
correctly against the terrain behind them. Depth is handed over either by
`glBlitFramebuffer`, a hardware copy between matching 24-bit depth formats, or, where the
driver's depth format does not match (a documented split between AMD and NVIDIA on this
renderer), by a shader that writes `gl_FragDepth` explicitly. The first is cheaper; the
second is the fallback that exists because not every card can be asked to do the first.
Whichever path ran, the game then draws entities, particles, weather and the translucent
layer on top, testing correctly against terrain that Vulkan never showed it directly — only
through this shared image.

The translucent layer needs to go the other way once more: after the game has drawn entities
into that same depth buffer, their depth is copied back into Vulkan's image (again by
`glBlitFramebuffer` or the shader fallback) so that water tests against creatures standing in
it rather than through them. Both directions of this hand-off are timed on the card, not
estimated, and the numbers are printed in diagnostics as a line beginning `gl cost: composite
... of work plus ... waiting for Vulkan`, followed by how long handing depth back cost and by
which of the two paths.

---

## What the game still does

Taking a layer over into Vulkan does not remove the work vanilla does to decide what to draw.
The render loop still walks its render-chunk list every frame, still performs its frustum
tests against it, and still calls `renderBlockLayer` for all four layers — including the
three this renderer took — because that call is also where vanilla filters the visible-chunk
list into a draw list, and skipping the draw itself does not skip the filtering. With the
vanilla buffers empty, those draws issue no vertices; the cost that remains is bookkeeping
that no longer produces a pixel.

That cost is measured, not assumed. `VanillaFrame.stats()` in
`src/main/java/net/vulkanmod112/client/VanillaFrame.java` times the three methods that matter
and prints them on a line beginning `vanilla frame:`.

It is worth being exact about what that line contains, because the obvious reading of it is
wrong. `renderBlockLayer` measures about 0.7 ms a frame at thirty-two chunks — but this
renderer's own work happens *inside* that method. The loop it runs over the visible chunks is
what produces the list this mod then packs and submits, and the hook that does the packing and
the submitting sits under `VboRenderList.renderChunkLayer`, one call further in. So that
number is not idle waste to be reclaimed; most of it is the hand-off itself. Cancelling the
method outright removes this renderer along with the loop, and the world goes with it.

The other method that reads as vanilla's own cost is `renderEntities`, and it is not a loop
over creatures. It walks the same visible list — every section on screen, some 17 700 of them
at thirty-two chunks against about 2 700 that hold any blocks — and asks the world which chunk
each section belongs to before finding out whether anything is standing in it. Held to a fixed
scene of five drawn creatures it costs 0.07 ms at eight chunks and 1.10 at thirty-two, and the
part of it that draws block entities grows the same way with none drawn at all. Both halves are
now given a short list instead: the creature list is turned inside out, because there are sixty
entities and each of them already records the section it is filed under, and this renderer's
visibility search answers in one array read whether that section is on screen. The same
creatures are found, in the same order.

The method that is worth naming separately is `setupTerrain`, which decides which chunks are
on screen. Standing still it costs **0.07 ms** a frame; flying at thirty-two chunks it costs
**1.6 to 1.9 ms**, because it is re-run whenever the answer might have changed and, in flight,
a chunk finishes building almost every frame. That is why a measurement taken from a standstill
says nothing about a moving frame, and it is the single largest thing in the frame at a long
render distance. The visible set is now rebuilt at once when the view moves and at a tick's
pace when only a chunk has arrived, which brings the same measurement to 0.3 to 0.5 ms.

Measured against the game's usual choice of renderer at the same settings, this mod's
per-frame cost is higher and its per-chunk cost is much lower, so the two balance out at
around 18 chunks of render distance: below that this renderer is slower, above it faster.
That crossover is the direct consequence of the two costs above — a constant overhead per
frame from the residual walk and the composite, offset by dropping the vanilla renderer's own
per-chunk work once there are enough chunks on screen for the difference to add up.

---

## Failure and fallback

Every failure path in this renderer ends the same way: falling back to the game's own
OpenGL rendering. That fallback is unconditional, and three separate mechanisms exist to
make it safe.

**The `broken` latch.** In `TerrainHooks.renderChunkLayer`, any `Throwable` that escapes the
render path sets a permanent flag, `broken`, for the rest of the session. Once set, the mod
stops drawing terrain at all and the game renders exactly as it always did. It is permanent
rather than retried because a failure inside a Vulkan frame can leave fences reset ahead of
their submit and semaphores signalled on one side before the other has consumed them — half a
synchronisation pair, left that way by whatever threw. Nothing after that point can strand a
*later* frame, because after the latch trips there is no later frame for this renderer;
letting it try again would mean walking every one of those half-finished pairs back to a
known state first, which is exactly the risk the latch avoids by not happening.

**The buffer refill.** This fallback only shows a world because the vanilla buffers still
hold one — and if `Drop Vanilla Chunk Buffers` emptied them, they do not, until every chunk
is rebuilt. So the same method that turns the drop on watches for anything that makes the
Vulkan path unavailable — the `broken` latch tripping, the setting being switched off, the
terrain toggle itself — and the moment any of those happens, it rebuilds every chunk so the
vanilla buffers are filled again. That rebuild is the cost of the memory saving: a pause
while the world reappears in the game's own buffers, not a permanently invisible world.

**Patch groups and quarantine.** The class patches that make the Vulkan terrain possible are
split into eight groups by `src/main/java/net/vulkanmod112/core/VulkanPatchGroups.java` —
core terrain, chunk visibility, creatures, dynamic lights, particles, sky and weather,
animated textures, and the optional speed-ups — and any group can be switched off on its own
without disabling the rest. If a patch in a group fails to apply, that group is quarantined:
the next launch starts without it, with a log line naming what failed and which group it
belongs to. This turns what would otherwise be one shared failure class — a broken mixin
target reported as a missing class, naming neither the patch nor the mod that wrote it — into
something a player or a modder can read and act on without guessing.

**Standing aside for another renderer.** Only one renderer can own the terrain, because two
mods rewriting the same classes at class-load time — before this mod's own runtime checks
could step in — simply fail to apply, and a failure there is fatal in a way a runtime fallback
is not. `src/main/java/net/vulkanmod112/core/VulkanCorePlugin.java` checks, before any mixin
is registered, whether a mod that replaces the terrain renderer is present — by looking for
known tweaker classes on the launch classloader, and by scanning jar names in the mods folder
for known fragments, with a text file
(`config/vulkanmod112-standaside.txt`) and a JVM property for naming one this build has not
heard of. If one is found, this mod's renderer mixin config is never registered at all: the
Vulkan terrain does not load, and everything else — the settings screen, the game-side
optimisations, the draw-distance and animation settings — stays exactly as available as it
would otherwise be, and a line in the log names what it stood aside for. The names the build
knows are in that source file rather than repeated here, since the list grows independently
of how the mechanism works; the text file and the property are how a name gets added without
waiting for a release.

---

## Where the numbers come from

Every number in this document, and in the diagnostics log, is measured rather than estimated.
`src/main/java/net/vulkanmod112/client/Flight.java`, driven by `-Pflight=<route>` at build
time, is an automated flight harness: the game creates a world from a fixed seed, flies a
fixed route at a fixed speed, takes screenshots at fixed points along it, and quits — so that
two builds can be compared frame for frame rather than by two different people flying two
different paths by hand. Alongside it, a per-frame diagnostics report accumulates timings
like the ones quoted above and prints them periodically rather than once. Both are covered in
more detail in [ADVANCED.md](ADVANCED.md#diagnostics) and [ROADMAP.md](ROADMAP.md); this
document only draws on what they produce.
