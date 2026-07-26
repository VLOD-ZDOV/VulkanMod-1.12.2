# Changelog

## [0.5.0] - unreleased

### Added

- Offscreen chunk preloading, on by default. Vanilla flood-fills outward from the player's chunk and ANDs a frustum test into every expansion step, so a chunk that needs rebuilding but is not on screen is never even considered — it is discovered from scratch when the camera turns. At render distance 64 the result is a world that fills in along whatever you look at, and narrowing the field of view makes distant chunks appear because it shrinks the competition. The build queue is now topped up from the rest of the grid, but only once the visible chunks are handled, and by scanning a bounded slice per frame rather than sweeping 266 000 entries. Only the build queue is touched; what gets drawn is still decided by the frustum.

### Performance

- Chunk uploads go through one shared staging ring instead of a persistently mapped staging buffer per chunk. Every mirrored chunk used to cost a `vkAllocateMemory`, a `vkCreateBuffer` and a `vkMapMemory`, and kept its pinned host copy for as long as the chunk lived — as much pinned system memory as the whole world took in VRAM. At render distance 12 that was already 4321 allocations, past the 4096 the Vulkan spec guarantees; at 64 it was tens of thousands, which is where drivers that hold close to the guarantee simply start failing the allocation.
- Growing the shared geometry buffer copies the old contents on the GPU rather than re-uploading every chunk from the host. The old path pushed the entire mirrored world back across PCIe on each growth, and at high render distances there are several growths.

### Fixed

- The upload fence was created signalled and never reset before its first submission. `vkQueueSubmit` requires an unsignalled fence, and every wait on it afterwards returned immediately without the GPU having finished anything — so the upload command buffer was reset while still executing and its staging memory was reused underneath it. The symptom would have been corrupt geometry or a lost device with no reproducible pattern.
- A geometry buffer replaced by a larger one is now freed only once every frame that could still name it has completed. Draws bind the buffer handle by value when they are recorded, so destroying it at replacement time could hand a destroyed handle to a submit.
- Freed ranges in the geometry buffer are merged with their neighbours. Without that, flying around left the buffer as thousands of small adjacent holes that no rebuilt chunk fitted into, growing the buffer while the space was already there.
- Zoom is eased over about a tenth of a second instead of snapping, timed off the wall clock. The state is decided on the 20 Hz tick, and interpolating on that clock is what made the transition look stepped. Mouse sensitivity now follows the same curve rather than jumping at the tick boundary.

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
