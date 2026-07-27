# Changelog

## [Unreleased]

### Added

- **Near Clipping Plane**, 0.2 blocks by default. Vanilla projects the world with a near plane of 0.05 blocks, and depth resolution falls off as the square of distance divided by that number. With the 24-bit depth buffer the game uses, three hundred blocks out it can no longer separate surfaces closer than about 0.107 blocks — and a snow layer sits 0.125 above the block it covers, whose top face is still drawn, because a one-deep layer is not an opaque cube. That is the grey and sand speckling through distant snow from any high vantage point. Raising the plane to 0.2 puts the resolvable gap at 0.027, and confirmed by eye it removes nearly all of the speckling. It cannot be set for the Vulkan pass alone, since the depth buffer is shared with OpenGL, which draws entities and water into it from the same matrix, so this changes the game's projection and everything follows. It costs nothing to draw — it is one number in the projection matrix — so it ships on. The price is that geometry nearer to the eye than the plane is clipped away, which can open a hole when the head is inside a block; 0 restores vanilla. Clouds set the projection up themselves and put the world's back afterwards, and below cloud height that happens before the terrain is drawn, so their copy of the number has to move with it or the setting reaches nothing but the view from above the clouds.
- **Visibility Walk Interval**, default off. The game decides which chunks are on screen with a flood fill through the chunk graph, and its own profiler puts that at a quarter to a half of the entire frame at render distance 64 — around eight times what drawing the world costs. It reruns whenever the camera moves, which is fair, and also whenever any chunk is queued for rebuild or finishes uploading, which while a world fills in means every frame even standing perfectly still. This limits only the second case. Measured afterwards, it earns very little: camera movement accounts for some 97% of the requests, and there is little left to skip. Kept because it costs nothing when off and the ceiling is a property of this approach rather than of this build.
- **Visibility Seed Cache**, on by default. The same search reads all 4096 block states of the camera's own chunk section every time it runs, to work out which faces are reachable from the block the eye is in. That answer changes only when the camera moves to a different block or that section is rebuilt, and the cache is keyed on exactly those two things. No measurable framerate change in testing; it is here because hundreds of thousands of redundant block reads a second are worth removing regardless, and there is a switch to rule it out.
- **Chunk Build Threads** (Advanced), default 0 for vanilla behaviour. Vanilla sizes its chunk builder pool from the heap rather than from the processor: `threads = clamp(cores, 1, (maxMemory * 0.3 / 10 MiB) / 5)`, which on a 4 GiB heap caps out at 21 no matter how many cores are present. The setting overrides the cap. Measured honestly, it did not buy frames — +11% peak upload rate for +52% threads, and no change in the framerate — so it ships off by default; chunk building was not the bottleneck. It is kept because the heap ceiling bites hardest exactly where it is least expected, on a machine with many cores and a small heap.

### Performance

- Boxes are tested against the view frustum by their far corner instead of by all eight. For each clipping plane, vanilla asks whether every one of a box's eight corners is outside it, at three multiplies and three adds per corner — up to forty-eight of those to reject one box. The dot product is linear and separable per axis, so over the corners it is maximised by the one the plane's normal points towards: if that corner is outside, all of them are, and the value computed for it is the same expression vanilla evaluates for the same corner rather than an approximation of it. Checked against vanilla's version over 2.7 million boxes on a real frustum laid out across the render-chunk grid, and over 20 million random cases including planes exactly parallel to an axis: no disagreement, and 3.4 times fewer dot products. This matters because the search that decides which chunks are on screen runs it once for every chunk it reaches — the game's own profiler puts that search at a quarter to a half of the entire frame at render distance 64, against 4.5% for drawing the world. Boxes stretched to infinity, which the search uses to seed itself when the camera is above or below the world, are handed back to vanilla: an infinite coordinate times a plane normal of exactly zero is NaN, and vanilla's chain of comparisons treats that as "not rejected" in a way the corner test would not reproduce. There is a switch, for ruling it out rather than for choosing.
- The indirect draw batches are allocated in memory the GPU owns rather than in ordinary host memory, where the driver offers such a type. The GPU reads both halves of every batch on each frame — a chunk origin per draw in the vertex shader, and the draw commands themselves in the command processor — so at high render distances that was thousands of small reads across PCIe per frame, competing with chunk geometry streaming over the same bus. Cards without resizable BAR expose only a small window of this memory for the whole system, so an allocation refused there falls back to the old behaviour rather than failing.
- The mirror copy now happens on the thread that built the chunk instead of the thread that draws. Vanilla gives chunk uploads a hard budget of a quarter of a frame minus whatever the frame has already spent, and runs them on the render thread because they need the OpenGL context. This mod's half of the work needs neither OpenGL nor a driver call — it is a memcpy into mapped staging — but it rode along on that same thread, so the budget drained twice as fast as vanilla alone. Builder threads now reserve a range in their own half of the staging ring and copy there directly; the render thread picks up the finished copy. No Vulkan call is made off the render thread, because the queue and the command pool belong to it and are not thread-safe. Measured flying into unexplored terrain at render distance 64: **97.4% of 29 940 uploads copied off the render thread, none refused.**
- The mirror is keyed by a dense slot number carried on the chunk's own vertex buffer instead of by its OpenGL buffer name. The old index held an entry for every live buffer, and the game keeps one per layer for every render chunk in the grid — hundreds of thousands at high render distances — so every lookup reached into a random place in a very large array. Command recording for a frame dropped from 0.19–0.21 ms to 0.11 ms at around 10 500 chunks. That is a tenth of a millisecond in a frame of several, so the real value is that it unblocks the change above: on a builder thread the OpenGL name does not exist yet, but the slot does.

### Changed

- The diagnostics report now carries what the F3 overlay shows — the drawn-against-total chunk counts, entities rendered against loaded, particles and block entities — taken from the same methods the overlay calls. A frame breakdown says how much of the frame this mod accounts for; when that answer came back at four percent, nothing in the report said what the other ninety-six were doing.
- The report also writes out the game's own profiler tree, when the profiler is running. The pie chart it normally draws is laid out in interface coordinates, so on a large display it arrives a few hundred pixels across with labels too small to read. Open it with Shift+F3 and the same numbers land in the log as text, three levels deep, with anything under one percent dropped.
- Timings for the parts of the frame this mod does not own: the layers vanilla still draws, and the two methods that walk the visible-chunk list. Between them they turn "the frame goes somewhere else" into a number.
- The diagnostics report states how much of each video memory heap the driver considers spoken for, against how much it is willing to hand out, and says so when the two cross. That is the point where a driver starts moving allocations into system memory, and until now a session that slowed to a crawl with no change in the scene gave no way to tell whether that was happening. The figures cover everything on the machine, not this game alone.

### Fixed

- The mod no longer loads its isolated LWJGL 3 runtime on a Java version it has not been built against, and it no longer scans the game's classpath for LWJGL when it ships its own. LWJGL 3.3 installs its OpenGL function tables by patching the JVM's JNI function table and only knows the layout of JVMs it has seen. On a newer one it prints `Unsupported JVM detected` and then keeps running with a corrupted table: OpenGL calls start returning their own arguments instead of results, and the process dies seconds later anywhere at all. The failure looks like a broken graphics driver, which is why it is worth naming here.
- Interop now says which of the two device checks failed — a genuine mismatch between the OpenGL and Vulkan GPUs, or an inability to read the driver UUID at all. They need different answers from the user and used to produce the same message.

## [0.5.0] - 2026-07-26

### Added

- Fog. The Vulkan-drawn terrain was the one thing in the scene with no fog at all, which is most obvious underwater: fish and mobs take on the colour of the water while the blocks behind them stay perfectly clear. The parameters are read straight out of OpenGL each frame rather than recomputed, because the game changes fog for water, lava, blindness, the void and render distance, and mods add their own. There is a switch for it, on by default.
- Offscreen chunk preloading, **off by default**. Vanilla flood-fills outward from the player's chunk and ANDs a frustum test into every expansion step, so a chunk that needs rebuilding but is not on screen is never even considered — it is discovered from scratch when the camera turns. At render distance 64 the result is a world that fills in along whatever you look at, and narrowing the field of view makes distant chunks appear because it shrinks the competition. The build queue is now topped up from the rest of the grid, but only once the visible chunks are handled, and by scanning a bounded slice per frame rather than sweeping 266 000 entries. Only the build queue is touched; what gets drawn is still decided by the frustum. It is off by default because it is not free work the game was skipping out of laziness: measured at render distance 64, 330 fps without it against 120-140 with, and a world that finishes loading holds all of its geometry in video memory rather than only the part you have looked at. The settings screen states both numbers.

### Performance

- Chunk uploads go through one shared staging ring instead of a persistently mapped staging buffer per chunk. Every mirrored chunk used to cost a `vkAllocateMemory`, a `vkCreateBuffer` and a `vkMapMemory`, and kept its pinned host copy for as long as the chunk lived — as much pinned system memory as the whole world took in VRAM. At render distance 12 that was already 4321 allocations, past the 4096 the Vulkan spec guarantees; at 64 it was tens of thousands, which is where drivers that hold close to the guarantee simply start failing the allocation.
- Growing the shared geometry buffer copies the old contents on the GPU rather than re-uploading every chunk from the host. The old path pushed the entire mirrored world back across PCIe on each growth, and at high render distances there are several growths.
- The translucent layer no longer walks and packs its whole chunk list every frame for nothing. It stays on the vanilla path and the Vulkan side rejected it anyway, but the work was done first and then discarded — at high render distances water and glass make that list long. It was also inflating the drawn-chunk counter with chunks nothing ever drew.
- The lightmap is only uploaded when it changed. It is 256 texels rewritten every single frame together with two layout barriers and a copy, for data that changes at dawn, at dusk and when you walk into a cave. A hash of the array decides.
- The staging ring is 96 MiB rather than 32. Wrapping it blocks the render thread until the GPU has drained it, so what matters is the interval between wraps, not the size of one upload — this is roughly 2000 chunks of headroom instead of 600.
- Offscreen preloading only tops the build queue up once it has run dry, instead of whenever it is short. Keeping it topped up meant vanilla's chunk builder never idled, which on a CPU that is already the bottleneck is a cost paid every frame for chunks nobody is looking at yet.
- The terrain launch flag is read once instead of on every layer. `System.getProperty` locks the global property table.
- Chunk copies are recorded as one `vkCmdCopyBuffer` carrying every region instead of one call per chunk, and the buffer handed to the mirror is no longer duplicated. Both sit on the path taken by every chunk the game uploads, which is a burst of dozens each time the camera turns.

### Fixed

- The upload fence was created signalled and never reset before its first submission. `vkQueueSubmit` requires an unsignalled fence, and every wait on it afterwards returned immediately without the GPU having finished anything — so the upload command buffer was reset while still executing and its staging memory was reused underneath it. The symptom would have been corrupt geometry or a lost device with no reproducible pattern.
- A geometry buffer replaced by a larger one is now freed only once every frame that could still name it has completed. Draws bind the buffer handle by value when they are recorded, so destroying it at replacement time could hand a destroyed handle to a submit.
- Freed ranges in the geometry buffer are merged with their neighbours. Without that, flying around left the buffer as thousands of small adjacent holes that no rebuilt chunk fitted into, growing the buffer while the space was already there.
- Zooming back out left the world drawn as the narrow cone it was during the zoom, until something made the player turn. RenderGlobal rebuilds its visible-chunk list only when the player moves or turns — the field of view is not part of that condition — so a list rebuilt while zoomed in stayed in use after the view had widened again. Easing outward now invalidates it.
- Zoom is eased over about a tenth of a second instead of snapping, timed off the wall clock. The state is decided on the 20 Hz tick, and interpolating on that clock is what made the transition look stepped. Mouse sensitivity now follows the same curve rather than jumping at the tick boundary.

### Investigated, not a defect here

- Brightness underwater changes in visible steps. The game recomputes the lightmap once per tick, so it arrives as twenty steps a second no matter the framerate, and the ramp underwater is continuous. Confirmed by turning the Vulkan terrain off entirely: the stepping is identical on vanilla rendering. The diagnostics report now counts how often the lightmap actually changes, so this does not have to be re-argued.

## [0.4.0] - 2026-07-26

### Added

- Hold-to-zoom, bound to **C** by default and rebindable under Controls, like OptiFine's. Mouse sensitivity is scaled to match while the key is held — at a quarter of the field of view an unchanged sensitivity sweeps the view four times as far for the same hand movement — and the original is restored on release, on opening a screen and on losing window focus, so a temporary value can never be saved to options.txt. Works even where the Vulkan renderer falls back to OpenGL.
- Presets: Stable, Balanced and Performance, on the Rendering page. Stable is what the mod ships with and what a fresh install uses; the other two trade progressively more detail for frames. A preset is a one-shot write rather than a mode, so anything changed afterwards stays changed.
- Settings now state what they cost on the CPU, the GPU and in VRAM separately, as three bars in the description panel. Which resource is short decides whether a setting will help at all, and a single "impact" rating hid exactly that.
- Geometry budget, on the Advanced page. It sets how much video memory the world geometry may take before the renderer stops growing its buffer generously — every growth stops the GPU and re-uploads every chunk, so a card with memory to spare can buy those stutters away, and a small one can keep the footprint tight. Automatic uses a quarter of what the GPU reports. Chunks are never dropped to stay inside it; the budget steers growth rather than capping it.
- Frames in flight is configurable (1–3, default 2) instead of fixed at 2.
- Reset button, restoring this mod's settings to their defaults. Minecraft's own settings are left alone.
- The GPU's device-local memory is shown in the settings header and recorded in the diagnostics log.

### Fixed

- Terrain layers no longer stop at 4096 chunks. The indirect batch was a fixed size and everything past it was silently dropped, so at high render distances the world had holes and the GPU was handed less geometry than the scene contained. The batch now grows to fit the largest layer.

### Performance

- The alpha test is compiled out of the SOLID pipeline through a specialisation constant. Its cutoff is 0.0, so the test never fired, but a `discard` anywhere in the shader makes the hardware disable early depth testing for the whole pass — and SOLID is both the bulk of the terrain and the layer with the most overdraw. The CUTOUT layers keep the test, from the same shader modules.
- Chunk lookups are resolved once per layer instead of once per chunk, and the mirror's index no longer boxes an `Integer` for every one of them. At render distance 64 that was tens of thousands of locked, boxed lookups per frame on the thread that has to finish the frame.
- The largest mirrored chunk is tracked as it is uploaded instead of being recomputed by scanning every mirrored chunk once a frame.

## [0.3.0] - 2026-07-26

### Added

- GPU timings. Timestamp queries around the terrain pass are read back a frame late and reported alongside the CPU breakdown, so optimisation work can be measured instead of guessed at.
- Ultra logging: a full diagnostics report written to `logs/vulkanmod112-diagnostics.log`, covering versions, installed mods, GPU and driver, every active renderer path and a periodic snapshot of frame costs and settings.
- Animated block textures can be turned off, which vanilla offers no way to do.
- Background framerate cap. A minimised window with the frame limit on "unlimited" kept the GPU at full load drawing frames nobody could see; it now sleeps to 10 fps by default while the window is not active.

- Settings screen rebuilt in VulkanMod's own shape: page tabs, a scrolling list of grouped options, and a panel describing the option under the cursor together with how much it is worth in frames.
- Game-side optimisations with their own page. Entity and block-entity draw distances are capped independently of vanilla's per-object limits, and the vanilla settings that matter most for framerate are reachable without leaving the screen.

### Fixed

- The Video Settings entry point no longer lands on top of the options list; it sits in the free strip above it.

### Also in 0.3.0

- Windows support for the zero-copy path: external memory and semaphores are exported as Win32 handles there and as file descriptors on Linux, chosen at runtime by a single platform layer. Linux behaviour is unchanged.
- Block atlas mip levels. The levels Minecraft already builds per sprite are copied into the Vulkan image, and the atlas sampler filters between them, which removes distant-chunk shimmer and cuts texture-cache misses.

### Changed

- Terrain depth is composited with `glBlitFramebuffer` instead of a `gl_FragDepth` write, so the fullscreen composite keeps early-Z. The depth target is 24-bit where the driver supports it; otherwise, or if the driver rejects the blit, the previous shader path is used automatically.

### Documentation

- `ROADMAP.md` lists the remaining work found while reviewing the renderer, in the order it should be done, including two limits that currently cap render distance.

## [0.2.0] - 2026-07-24

### Added

- OptiFine-style **VulkanMod112 Settings...** entry in Video Settings.
- Config file at `config/vulkanmod112.cfg`, with live terrain and diagnostic-overlay switches.
- Render-distance slider from 2 to 64 chunks. The vanilla render-distance limit is raised to 64 on startup.

### Changed

- Documented manual installation, the MixinBooter runtime dependency, the same-GPU requirement and high-distance caveats.

## [0.1.0] - 2026-07-24

### Fixed

- Restored compilation of the per-frame lightmap staging path.
- Select the correct staging buffer for each frame in flight and release every staging allocation and fence.
- Extract the LWJGL 3 OpenGL native companion required by GL/Vulkan interop.

### Performance

- Move mirrored chunk draw buffers from host-visible memory to device-local VRAM.
- Batch VBO copies into one Vulkan submission before each terrain frame instead of synchronising an upload per chunk.
- Suballocate all chunk VBOs in a shared growable vertex buffer, reducing per-frame vertex-buffer binds to one per terrain layer.
- Align each shared-buffer allocation to the 28-byte vanilla vertex stride, fixing corrupted UV/colour attributes after small or empty VBOs.

### Added

- Safe automatic fallback when OptiFine or legacy shader renderer classes are detected.
- Runtime requirements, diagnostic flags and current renderer scope in the README.
