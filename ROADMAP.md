# Roadmap

What is done, and what is planned next. For the detail behind any line, see `CHANGELOG.md`.

Everything new ships behind a switch, and the defaults never trade looks for frames without
being asked. If the Vulkan path fails for any reason, the game falls back to its own renderer
rather than crashing.

## Done

### 0.7.0 — in development

- **Frame-time graph** in the corner, off by default, with the 1% low — the number that tells a
  steady framerate from one that stalls, which an average cannot.
- **The settings screen is translated** into Russian, Simplified Chinese, German, French, Spanish,
  Brazilian Portuguese and Japanese, following Minecraft's own language setting.
- **Dynamic light distance is adjustable**, 1 to 200 blocks. Not how far the light reaches — how
  far away a source may be and still light the ground it stands on.

### 0.6.0

- **Water and glass drawn in Vulkan.** They now get fog like everything else.
- **Dynamic lights.** A carried torch, a dropped glowing block or a burning mob lights the world
  around it. Chunks are not rebuilt for it. Off by default.
- **Vanilla chunk buffers can be dropped.** The world is then stored once in video memory instead
  of twice. Off by default.
- **Own visibility search.** The game's own search for which chunks are on screen is a quarter to
  a half of the whole frame at high render distances; this replaces it with one that reads flat
  arrays instead of chasing pointers. On by default.
- **Snow and slabs no longer speckle at a distance**, through a nearer clipping plane.
- Faster on-screen test for chunks.
- Chunk copies happen on the threads that build chunks, not on the one that draws.
- Chunk builder thread count is configurable.
- Much deeper diagnostics log.

### 0.5.0

- **Fog**, which the Vulkan terrain had been missing entirely — most obvious underwater.
- **Offscreen chunk preloading**, so the world stops filling in only along wherever you look.
  Off by default; it costs frames while it works.
- Chunk uploads share one staging buffer instead of allocating one per chunk, which is what
  used to cap the render distance.
- Growing the geometry buffer no longer re-sends the whole world across the bus.
- The lightmap is uploaded only when it changes.
- Zoom eases in and out instead of snapping, and zooming back out no longer leaves the world
  drawn as the narrow cone it was.

### 0.4.0

- **Presets** — Stable, Balanced, Performance.
- **Every setting states what it costs** on the CPU, the GPU and in video memory separately.
- **Geometry budget**, so a card with memory to spare can buy away the stutter of a growing
  buffer, and a small one can keep the footprint tight.
- Terrain layers no longer stop at 4096 chunks, which used to leave holes in the world at high
  render distances.
- Reset button, configurable frames in flight.

### 0.3.0

- **Windows support** for the shared-memory path.
- **Hold-to-zoom** on **C**, rebindable, with mouse sensitivity scaled to match.
- **Settings screen** with pages, groups and a description panel.
- **Game-side optimisations**: entity and block-entity draw distances, animated textures off,
  background framerate cap.
- Block atlas mip levels, which removes distant shimmer.
- Depth is copied by the hardware instead of written from a shader.
- GPU timings and the full diagnostics log.

### 0.2.0

- Settings entry in Video Settings, config file, render distance up to 64 chunks.

### 0.1.0

- First working Vulkan terrain: the opaque layers drawn in Vulkan and handed back to the game
  through shared GPU memory.

## Planned

In the order they are likely to be worth doing.

- **Entities and block entities.** The largest part of the frame now that the visibility search
  has been dealt with.
- **Smart animated textures** — updating only the animated blocks actually in view, instead of
  the all-or-nothing switch that exists today.
- **Connected glass textures.**
- **Front-to-back drawing** within a terrain layer.
- Testing the Windows path on real hardware.

## Not planned

- **Shader packs.** A different project rather than a feature — the vertex format, the passes
  and the whole pipeline change with a pack loaded.
- **Replacing the entire renderer.** Entities, particles, the sky and the interface stay on the
  game's OpenGL renderer. Sharing the frame with it is what makes the fallback possible.
- **Running alongside OptiFine's renderer.** They replace the same classes. With OptiFine
  installed this mod's settings and game-side optimisations still work; the Vulkan terrain does
  not load.
